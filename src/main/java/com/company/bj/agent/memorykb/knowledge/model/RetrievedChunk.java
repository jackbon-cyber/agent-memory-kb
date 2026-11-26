package com.company.bj.agent.memorykb.knowledge.model;

import java.util.Map;

/**
 * 检索命中的切片。
 *
 * <p>检索结果的对外形状：既携带正文与相似度得分，也携带完整元数据，
 * 以便调用方（控制台展示、召回质量评测）直接完成溯源 —— 无需再回查向量库。
 *
 * @param chunkId  切片唯一标识，与入库时的记录 id 一致
 * @param content  切片正文
 * @param score    相似度得分，<b>越大越相关</b>
 * @param metadata 命中记录的完整元数据，不可为 {@code null}
 */
public record RetrievedChunk(
    String chunkId,
    String content,
    double score,
    Map<String, Object> metadata) {

    /**
     * 规范化元数据，保证下游拿到的元数据永不为 {@code null}。
     */
    public RetrievedChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
