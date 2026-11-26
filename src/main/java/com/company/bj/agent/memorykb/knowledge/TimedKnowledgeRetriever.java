package com.company.bj.agent.memorykb.knowledge;

import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import java.util.List;

/**
 * 支持<b>分相耗时</b>统计的检索器。
 *
 * <p>普通 {@link KnowledgeRetriever} 只返回命中列表，调用方无从知道
 * 耗时花在了哪一段。实现本接口后，调用方可以拿到
 * 「查询向量化」与「向量库查询」的分段数据。
 *
 * <p><b>为什么要这个接口</b>：P95 超标时，必须能回答
 * 「是本工程的检索慢，还是外部模型慢」——
 * 只有分开计量才能得出可行动结论，
 * 而不是一句无从落地的「优化延迟」。
 *
 * <p>本接口是<b>可选能力</b>：不实现它不影响检索语义，
 * 只是拿不到分段耗时。实现方以 {@code instanceof} 判断是否可用。
 */
public interface TimedKnowledgeRetriever extends KnowledgeRetriever {

    /**
     * 检索并返回分段耗时。
     *
     * @param query          查询文本
     * @param filters        元数据过滤条件，无过滤时传空列表
     * @param logic          多条件的组合逻辑
     * @param topK           返回条数上限
     * @param scoreThreshold 相似度下限，为 {@code null} 时不施加下限
     * @return 带分段耗时的检索结果
     */
    TimedRetrieval retrieveTimed(String query, List<FilterCondition> filters,
        FilterLogic logic, int topK, Double scoreThreshold);

    /**
     * 一次检索的结果与分段耗时。
     *
     * @param chunks  命中的切片列表
     * @param timings 分段耗时
     */
    record TimedRetrieval(List<RetrievedChunk> chunks, RetrievalTimings timings) {

        /**
         * 规范化命中列表，保证下游拿到的列表永不为 {@code null}。
         */
        public TimedRetrieval {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }
}
