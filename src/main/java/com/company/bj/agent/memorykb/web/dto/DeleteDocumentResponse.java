package com.company.bj.agent.memorykb.web.dto;

/**
 * 文档删除结果。
 *
 * @param documentId 被删除的文档标识
 * @param deleted    是否删除成功
 * @param chunkCount 实际删除的切片数
 */
public record DeleteDocumentResponse(String documentId, boolean deleted, int chunkCount) {
}
