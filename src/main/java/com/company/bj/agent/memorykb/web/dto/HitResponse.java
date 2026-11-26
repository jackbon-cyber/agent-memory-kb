package com.company.bj.agent.memorykb.web.dto;

import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import java.util.List;
import java.util.Map;

/**
 * 检索命中的对外形状。
 *
 * <p>与领域对象 {@link RetrievedChunk} 字段逐字对应，
 * 但<b>不直接复用</b>该记录类型：领域记录的元数据是不可变 Map，
 * 而 HTTP 契约要求稳定的 JSON 字段名与「元数据可为任意 JSON 值」的宽松度。
 * 直接把领域对象暴露出去会让后续调整领域模型时<b>静默改变</b>对外契约。
 *
 * @param chunkId  切片唯一标识
 * @param content  切片正文
 * @param score    相似度得分，越大越相关
 * @param metadata 命中的完整元数据
 */
public record HitResponse(
    String chunkId,
    String content,
    double score,
    Map<String, Object> metadata) {

    /**
     * 规范化元数据，保证 JSON 中该字段永不为 {@code null}。
     *
     * @param chunkId  切片唯一标识
     * @param content  切片正文
     * @param score    相似度得分
     * @param metadata 命中的完整元数据
     */
    public HitResponse {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 由领域命中转换为对外形状。
     *
     * @param chunk 领域命中
     * @return 对外形状
     */
    public static HitResponse from(RetrievedChunk chunk) {
        return new HitResponse(chunk.chunkId(), chunk.content(), chunk.score(), chunk.metadata());
    }
}
