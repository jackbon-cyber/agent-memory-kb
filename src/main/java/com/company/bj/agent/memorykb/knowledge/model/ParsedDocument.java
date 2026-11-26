package com.company.bj.agent.memorykb.knowledge.model;

import java.util.List;

/**
 * 解析后的文档。
 *
 * <p>是解析器与切片器之间的交接结构：{@code content} 保存全文（切片偏移量的基准），
 * {@code blocks} 保存按顺序切分出的内容块。
 *
 * @param documentId 文档标识，约定直接使用原始文件名（含扩展名），不做任何规范化
 * @param fileName   原始文件名
 * @param sourceType 来源类型，取值为 {@code "txt"} 或 {@code "excel"}
 * @param content    文档全文
 * @param blocks     按顺序排列的内容块
 */
public record ParsedDocument(
    String documentId,
    String fileName,
    String sourceType,
    String content,
    List<ContentBlock> blocks) {
}
