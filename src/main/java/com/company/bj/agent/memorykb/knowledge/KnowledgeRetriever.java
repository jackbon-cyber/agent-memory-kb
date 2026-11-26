package com.company.bj.agent.memorykb.knowledge;

import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import java.util.List;

/**
 * 知识库检索端口。
 *
 * <p>平台自身的检索契约，<b>不暴露任何向量库类型</b>：调用方只描述
 * "查什么文本、带什么元数据过滤、取几条、相似度下限是多少"，
 * 由具体实现（当前是 {@link ChromaKnowledgeRetriever}）负责翻译成向量库查询。
 *
 * <p>之所以另立端口而不直接复用 AgentScope 的 {@code VDBStoreBase}：
 * 后者的 {@code SearchDocumentDto} 没有 where 过滤字段，
 * 无法承载"按元数据过滤"这一核心需求（见 design.md 决策 D2）。
 */
public interface KnowledgeRetriever {

    /**
     * 按查询文本与元数据过滤条件检索知识库。
     *
     * @param query          查询文本
     * @param filters        元数据过滤条件，无过滤时传空列表
     * @param logic          多条件的组合逻辑，条件为空时该参数无意义
     * @param topK           返回条数上限
     * @param scoreThreshold 相似度下限，得分低于该值的命中被剔除；
     *                       为 {@code null} 时不施加下限
     * @return 命中列表，按相似度<b>降序</b>排列
     */
    List<RetrievedChunk> retrieve(String query, List<FilterCondition> filters,
        FilterLogic logic, int topK, Double scoreThreshold);
}
