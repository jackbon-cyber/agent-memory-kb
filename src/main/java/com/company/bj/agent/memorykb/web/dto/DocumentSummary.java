package com.company.bj.agent.memorykb.web.dto;

/**
 * 文档摘要。
 *
 * <p>知识库中已入库文档的概览信息。
 *
 * @param documentId 文档标识（原始文件名，含扩展名）
 * @param fileName   来源文件名
 * @param chunkCount 该文档的切片数
 */
public record DocumentSummary(String documentId, String fileName, int chunkCount) {
}
