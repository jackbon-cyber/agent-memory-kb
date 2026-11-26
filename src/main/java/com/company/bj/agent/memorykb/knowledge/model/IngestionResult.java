package com.company.bj.agent.memorykb.knowledge.model;

import java.util.List;

/**
 * 一次摄取的结果。
 *
 * <p>报告本次摄取的切片规模与实际入库规模，两者相等即表示全部切片都已带向量写入向量库。
 *
 * @param documentId   被摄取的文档标识
 * @param chunkCount   本次产生的切片总数
 * @param ingestedCount 实际写入向量库的切片数
 * @param chunkIds     写入向量库的切片标识列表
 */
public record IngestionResult(
    String documentId,
    int chunkCount,
    int ingestedCount,
    List<String> chunkIds) {

    /**
     * 构造摄取结果并冻结切片标识列表。
     *
     * @param documentId    被摄取的文档标识
     * @param chunkCount    本次产生的切片总数
     * @param ingestedCount 实际写入向量库的切片数
     * @param chunkIds      写入向量库的切片标识列表
     */
    public IngestionResult {
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
    }
}
