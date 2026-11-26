package com.company.bj.agent.memorykb.web.dto;

/**
 * 文档入库结果。
 *
 * @param documentId    文档标识（原始文件名，含扩展名）
 * @param chunkCount    本次产生的切片总数
 * @param ingestedCount 实际写入向量库的切片数
 */
public record IngestionResponse(String documentId, int chunkCount, int ingestedCount) {
}
