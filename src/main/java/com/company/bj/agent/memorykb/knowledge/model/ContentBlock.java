package com.company.bj.agent.memorykb.knowledge.model;

/**
 * 文档内容块。
 *
 * <p>一个解析后的文档被拆成若干有序的<b>内容块</b>，每个块要么是一段纯文本
 * （{@code kind = "text"}），要么是一行表格数据（{@code kind = "table-row"}）。
 * 切片器以内容块为最小工作单位，从而保证表格行不被拆散。
 *
 * @param kind         块类型标识，取值为 {@code "text"} 或 {@code "table-row"}
 * @param text         块的文本内容
 * @param startOffset  该块在原文全文中的起始字符偏移
 * @param endOffset    该块在原文全文中的结束字符偏移（不含）
 * @param sectionTitle 该块所属的标题，解析阶段识别，无标题时为 {@code null}
 * @param tableName    该块所属的工作表名，文本块为 {@code null}
 * @param rowNumber    该块在表格中的行号（从 0 开始），文本块为 -1
 */
public record ContentBlock(
    String kind,
    String text,
    int startOffset,
    int endOffset,
    String sectionTitle,
    String tableName,
    int rowNumber) {
}
