package com.company.bj.agent.memorykb.knowledge.model;

import java.util.Map;

/**
 * 知识库切片。
 *
 * <p>切片是入库与检索的最小单位。{@code chunkId} 由 {@code documentId + "#" + sequence}
 * 唯一确定，是全工程<b>唯一</b>的拼接入口（见 {@link #chunkId(String, int)}），
 * {@link com.company.bj.agent.memorykb.knowledge.chunk.Chunker#chunkId(String, int)}
 * 只做委托，不得出现第二份拼接逻辑。
 *
 * <p><b>元数据边界</b>：本记录的 {@code metadata} 只承载<b>解析期</b>信息
 * （如 {@code section_title} / {@code table_name} / {@code row_number}）。
 * 入库时的完整元数据（{@code document_id} / {@code file_name} / {@code source_type} /
 * {@code sequence} / {@code start_offset} / {@code end_offset} / {@code ingested_at}
 * 以及调用方附加的自定义字段）由摄取服务在入库前<b>重新组装</b>，
 * 两者不累加，避免同一字段出现两个来源。
 *
 * @param chunkId      切片唯一标识，格式 {@code documentId + "#" + sequence}
 * @param documentId   所属文档标识
 * @param fileName     所属文件名
 * @param sourceType   所属来源类型
 * @param content      切片正文
 * @param sequence     切片序号，自 0 起连续递增
 * @param startOffset  切片在原文全文中的起始字符偏移
 * @param endOffset    切片在原文全文中的结束字符偏移（不含）
 * @param sectionTitle 切片所属的标题，无标题时为 {@code null}
 * @param metadata     解析期元数据，不可为 {@code null}
 */
public record Chunk(
    String chunkId,
    String documentId,
    String fileName,
    String sourceType,
    String content,
    int sequence,
    int startOffset,
    int endOffset,
    String sectionTitle,
    Map<String, Object> metadata) {

    /**
     * 构造切片并生成唯一标识。
     *
     * <p>唯一标识的生成规则只在本构造器中实现一次，避免调用方各自拼接造成不一致。
     *
     * @param documentId   所属文档标识
     * @param fileName     所属文件名
     * @param sourceType   所属来源类型
     * @param content      切片正文
     * @param sequence     切片序号
     * @param startOffset  切片在原文中的起始偏移
     * @param endOffset    切片在原文中的结束偏移
     * @param sectionTitle 切片所属标题
     * @param metadata     解析期元数据
     */
    public Chunk {
        chunkId = chunkId(documentId, sequence);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 生成切片唯一标识。
     *
     * <p>本方法是 {@code documentId + "#" + sequence} 这一规则的<b>唯一</b>实现，
     * 切片器与外部调用方都应通过它取标识。
     *
     * @param documentId 所属文档标识
     * @param sequence   切片序号
     * @return 切片唯一标识
     */
    public static String chunkId(String documentId, int sequence) {
        return documentId + "#" + sequence;
    }
}
