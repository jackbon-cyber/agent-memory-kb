package com.company.bj.agent.memorykb.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingNormalizer;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 端到端验收的<b>桩嵌入</b>装配。
 *
 * <p><b>为什么要桩</b>：上传与检索的整条链路依赖嵌入模型，
 * 而嵌入模型是外部服务，需要凭据且有配额。
 * 若把整条链路的验证都挂在凭据上，那么在没有凭据的环境里
 * 「上传能不能用」「过滤是否生效」这些<b>与模型无关</b>的性质
 * 就一条也验证不了 —— 而这些恰恰是最容易出错的结构性环节。
 *
 * <p><b>桩嵌入是什么</b>：把文本的字符哈希累加到固定维度的向量上，
 * 再做 L2 归一化。这是<b>词面</b>相似度，不是语义相似度。
 * 它足以支撑端到端链路的验证：切片是否产出、过滤是否收敛、
 * 文档清单是否正确、作用域是否隔离、删除是否生效。
 *
 * <p><b>桩嵌入不能验证什么</b>：语义召回质量。
 * 那是 {@code EndToEndRealIT} 与 {@code QualityEvaluationIT} 的职责，
 * 它们的结论基于真实嵌入模型。
 *
 * <p><b>维度契约</b>：桩嵌入固定 256 维，测试配置里的
 * {@code memorykb.embedding.dimensions} 也必须是 256 ——
 * Chroma 集合的维度在首次写入时绑定且不可变，
 * 若配置声明的维度与实际写入的不一致，摄取管线会在维度闸门处拒绝入库。
 */
public class StubEmbeddingConfiguration {

    /** 桩嵌入的向量维度。 */
    public static final int STUB_EMBEDDING_DIMENSIONS = 256;

    /**
     * 提供桩嵌入服务。
     *
     * <p><b>标记为 {@code @Primary}</b>：Spring 容器里已有一个真实嵌入服务 Bean，
     * 桩实现必须覆盖它，否则注入点会出现歧义。
     *
     * @return 桩嵌入服务
     */
    @Bean
    @Primary
    public EmbeddingService stubEmbeddingService() {
        return new EmbeddingService() {
            @Override
            public List<double[]> embed(List<String> texts) {
                List<double[]> vectors = new ArrayList<>(texts.size());
                for (String text : texts) {
                    vectors.add(vectorOf(text));
                }
                return vectors;
            }

            /**
             * 把文本的字符哈希累加为向量。
             *
             * @param text 文本
             * @return 单位长度向量
             */
            private double[] vectorOf(String text) {
                double[] vector = new double[STUB_EMBEDDING_DIMENSIONS];
                for (int i = 0; i < text.length(); i++) {
                    int bucket = Math.floorMod(text.charAt(i) * 31 + i, STUB_EMBEDDING_DIMENSIONS);
                    vector[bucket] += 1.0;
                }
                return EmbeddingNormalizer.normalize(vector);
            }
        };
    }
}
