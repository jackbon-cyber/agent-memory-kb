package com.company.bj.agent.memorykb.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.knowledge.embed.OpenAiCompatibleEmbeddingService;
import com.company.bj.agent.memorykb.memory.CachedEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 延迟构成诊断（<b>不是验收</b>）。
 *
 * <p>把一次检索的耗时拆成「嵌入调用」与「向量库查询」两段，
 * 用来回答一个问题：P95 超标时，瓶颈究竟在哪一段？
 *
 * <p><b>本测试不做门禁判定</b>，只打印分解数据。
 * 它的产出是<b>决策依据</b>，不是验收结论 ——
 * 验收结论由 {@code QualityEvaluationIT} 与
 * {@code EndToEndRealIT.质量门禁通过()} 给出。
 */
class LatencyBreakdownIT {

    @Test
    void 拆解检索延迟() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setApiKey(System.getenv("DASHSCOPE_API_KEY"));

        OpenAiCompatibleEmbeddingService embedding =
            new OpenAiCompatibleEmbeddingService(props, HttpClient.newHttpClient(),
                new ObjectMapper());

        ChromaProperties chromaProps = new ChromaProperties();
        ChromaClient chroma = new ChromaClient(chromaProps, HttpClient.newHttpClient(),
            new ObjectMapper());

        String collectionId = chroma.createCollection(chromaProps.getKnowledgeCollection());

        // 单条嵌入调用的耗时 —— 这是冷路径上的主要候选瓶颈。
        List<Double> embedLatencies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            embedding.embed(List.of("旅客通关流程包含哪几个环节 " + i));
            embedLatencies.add((System.nanoTime() - start) / 1_000_000.0);
        }

        // 批量嵌入调用的耗时 —— 生产环境可一次批多条。
        long batchStart = System.nanoTime();
        embedding.embed(List.of("查询甲", "查询乙", "查询丙", "查询丁", "查询戊"));
        double batchLatency = (System.nanoTime() - batchStart) / 1_000_000.0;

        // 纯向量库查询的耗时 —— 复用同一个查询向量，不含嵌入开销。
        double[] queryVector = embedding.embed(List.of("旅客通关流程包含哪几个环节")).get(0);
        List<Double> chromaLatencies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            chroma.query(collectionId, queryVector, 10, null, List.of("documents", "metadatas"));
            chromaLatencies.add((System.nanoTime() - start) / 1_000_000.0);
        }

        // 带查询向量缓存的检索路径 —— 生产实际走的就是这一条。
        // 预热一次让缓存里有内容，再测第二次（命中缓存）。
        CachedEmbeddingService cached = new CachedEmbeddingService(embedding, 60);
        cached.embed("旅客通关流程包含哪几个环节");
        List<Double> cachedLatencies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            cached.embed("旅客通关流程包含哪几个环节 " + (i % 2 == 0 ? "" : " "));
            cachedLatencies.add((System.nanoTime() - start) / 1_000_000.0);
        }

        Collections.sort(embedLatencies);
        Collections.sort(chromaLatencies);
        Collections.sort(cachedLatencies);

        System.out.println("========== 延迟构成诊断 ==========");
        System.out.printf("嵌入调用（单条，n=5）      : 最小 %.1f ms / 中位 %.1f ms / 最大 %.1f ms%n",
            embedLatencies.get(0), embedLatencies.get(2), embedLatencies.get(4));
        System.out.printf("嵌入调用（批量 5 条）      : %.1f ms%n", batchLatency);
        System.out.printf("向量库查询（n=20，不含嵌入）: 中位 %.1f ms / 最大 %.1f ms%n",
            chromaLatencies.get(10), chromaLatencies.get(19));
        System.out.printf("查询向量缓存命中           : 中位 %.3f ms%n", cachedLatencies.get(10));
        System.out.println("==================================");

        assertThat(chromaLatencies.get(10)).isPositive();
    }
}
