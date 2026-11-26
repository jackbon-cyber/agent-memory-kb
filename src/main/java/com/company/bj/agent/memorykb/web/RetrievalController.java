package com.company.bj.agent.memorykb.web;

import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.company.bj.agent.memorykb.web.dto.HitResponse;
import com.company.bj.agent.memorykb.web.dto.SearchRequest;
import com.company.bj.agent.memorykb.web.dto.SearchResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库在线检索接口。
 *
 * <p>把「查询文本 + 元数据过滤条件」翻译成一次带过滤的向量检索，
 * 并附上服务端实测耗时供控制台展示。
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final KnowledgeRetriever retriever;

    /**
     * 构造检索接口。
     *
     * @param retriever 知识库检索端口
     */
    public RetrievalController(KnowledgeRetriever retriever) {
        this.retriever = retriever;
    }

    /**
     * 执行一次带元数据过滤的语义检索。
     *
     * @param request 检索请求
     * @return 检索结果
     * @throws IllegalArgumentException 查询为空、过滤条件非法时抛出
     */
    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }

        // 耗时在服务端测量：控制台要展示的是后端真实负载，
        // 客户端往返时间里混着网络与浏览器渲染，不反映检索本身的快慢。
        long startNanos = System.nanoTime();
        List<RetrievedChunk> hits = retriever.retrieve(
            request.query(),
            request.toFilterConditions(),
            request.logic(),
            request.topK(),
            request.scoreThreshold());
        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;

        List<HitResponse> responses = new ArrayList<>(hits.size());
        for (RetrievedChunk hit : hits) {
            responses.add(HitResponse.from(hit));
        }
        return new SearchResponse(responses, tookMs, responses.size());
    }
}
