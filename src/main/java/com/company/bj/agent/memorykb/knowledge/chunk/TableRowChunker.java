package com.company.bj.agent.memorykb.knowledge.chunk;

import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.ContentBlock;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表格行切片器。
 *
 * <p>以 {@code kind = "table-row"} 的内容块为单位逐行累积成片，
 * 同一表格行<b>绝不</b>被拆到两个切片中 —— 这保证一行里的多个单元格始终同片出现，
 * 便于语义检索时把一整行当作一个完整的表格记录。
 *
 * <p><b>重叠与行不可拆的取舍</b>：表格场景下"行不可拆"<b>优先于</b>"重叠长度等于配置值"。
 * 当配置的重叠窗口整个落在某一行内部时，找不到可供退让的完整行，
 * 此时<b>丢弃</b>该次重叠（下一片从新行冷启动），
 * 而不是从行中间切断 —— 后者会把该行拆到两个切片，违反上面的硬约束。
 * 因此本切片器产出的<b>实际</b>重叠长度可能小于配置的 {@code overlap}。
 *
 * <p>若单行本身超过 {@code maxChunkSize}，该行单独成片（允许超限，因为行不可拆）。
 */
public class TableRowChunker implements Chunker {

    /** 表格行块的类型标识。 */
    public static final String KIND_TABLE_ROW = "table-row";

    /** 解析期元数据键：所属标题。 */
    public static final String METADATA_SECTION_TITLE = "section_title";

    /** 解析期元数据键：所属工作表名。 */
    public static final String METADATA_TABLE_NAME = "table_name";

    /** 解析期元数据键：行号。 */
    public static final String METADATA_ROW_NUMBER = "row_number";

    /** 同一片内多行之间的连接符。 */
    public static final String ROW_SEPARATOR = "\n";

    private final int maxChunkSize;
    private final int overlap;

    /**
     * 构造表格行切片器。
     *
     * @param maxChunkSize 单片字符上限，必须为正
     * @param overlap      相邻切片的重叠字符数，不能为负且不得大于 {@code maxChunkSize}
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public TableRowChunker(int maxChunkSize, int overlap) {
        if (maxChunkSize <= 0) {
            throw new IllegalArgumentException("maxChunkSize must be positive, but was " + maxChunkSize);
        }
        if (overlap < 0 || overlap >= maxChunkSize) {
            throw new IllegalArgumentException(
                "overlap must be within [0, maxChunkSize), but was " + overlap + " with maxChunkSize " + maxChunkSize);
        }
        this.maxChunkSize = maxChunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<Chunk> chunk(ParsedDocument doc) {
        if (doc == null) {
            return List.of();
        }
        List<ContentBlock> rows = tableRows(doc);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startOffset = rows.get(0).startOffset();
        ContentBlock lastRow = null;

        for (ContentBlock row : rows) {
            String text = row.text();
            if (current.length() > 0 && current.length() + ROW_SEPARATOR.length() + text.length() > maxChunkSize) {
                Chunk emitted = emit(chunks, doc, current, startOffset, lastRow);
                // 行不可拆：重叠只能落在行边界上，回退已累积的完整行以形成重叠窗口。
                // 重叠片段必须为新行预留额度，否则拼装后单片会超出上限。
                String tail = overlapTail(current.toString(), text.length());
                current.setLength(0);
                current.append(tail);
                // 重叠残片的真实起点是上一片内容的结束位置。
                // 注意不能用 row.startOffset() 反推：ExcelDocumentParser 推进游标时
                // 为行与行之间的 \n 连接符多留了一位，而上一片正文并不包含该换行符，
                // 直接相减会得到偏大 1 的起点，进而让 endOffset 越界。
                startOffset = emitted.endOffset() - tail.length();
            }
            if (current.length() == 0) {
                startOffset = row.startOffset();
            }
            if (current.length() > 0) {
                current.append(ROW_SEPARATOR);
            }
            current.append(text);
            lastRow = row;
        }
        if (current.length() > 0) {
            emit(chunks, doc, current, startOffset, lastRow);
        }
        return chunks;
    }

    /**
     * 从文档中取出表格行内容块。
     *
     * @param doc 解析后的文档
     * @return 表格行内容块列表
     */
    private List<ContentBlock> tableRows(ParsedDocument doc) {
        List<ContentBlock> rows = new ArrayList<>();
        if (doc.blocks() == null) {
            return rows;
        }
        for (ContentBlock block : doc.blocks()) {
            if (KIND_TABLE_ROW.equals(block.kind())) {
                rows.add(block);
            }
        }
        return rows;
    }

    /**
     * 取已累积文本尾部的重叠部分，边界对齐到行分隔符以保证行不被拆散。
     *
     * <p>重叠片段与随后追加的新行共同构成下一片，因此必须为新行预留额度：
     * 可用重叠长度为 {@code maxChunkSize - 新行长度 - 行分隔符长度}，
     * 保证拼装后单片长度仍不超过上限。
     *
     * <p><b>宁可不重叠，也不可拆行</b>：若重叠窗口内找不到行分隔符，
     * 说明窗口整个落在某一行内部，此时<b>丢弃</b>重叠（返回空串），
     * 而不是从行中间切断 —— 否则该行会被拆到两个切片里，
     * 违反"同一行绝不跨切片"的硬约束。
     *
     * @param text          已累积文本
     * @param incomingLength 即将追加的新行长度
     * @return 尾部重叠文本，长度不超过 {@code overlap} 且不超过剩余额度；
     *         窗口内无完整行时返回空串
     */
    private String overlapTail(String text, int incomingLength) {
        if (overlap <= 0 || text.length() <= overlap) {
            return "";
        }
        int budget = maxChunkSize - incomingLength - ROW_SEPARATOR.length();
        if (budget <= 0) {
            // 新行本身已占满上限，无法再携带重叠内容。
            return "";
        }
        int maxLength = Math.min(overlap, budget);
        if (maxLength <= 0) {
            return "";
        }
        int from = text.length() - maxLength;
        int separator = text.indexOf(ROW_SEPARATOR, from);
        if (separator < 0) {
            // 重叠窗口整个落在最后一行内部，没有完整行可退让 —— 放弃重叠，绝不拆行。
            return "";
        }
        return text.substring(separator + ROW_SEPARATOR.length());
    }

    /**
     * 产出切片。
     *
     * @param chunks       切片收集容器
     * @param doc          所属文档
     * @param content      切片正文
     * @param startOffset  切片在原文中的起始偏移
     * @param lastRow      切片中最后一行，用于携带表格元数据
     * @return 刚产出的切片
     */
    private Chunk emit(List<Chunk> chunks, ParsedDocument doc, StringBuilder content, int startOffset,
        ContentBlock lastRow) {
        String text = content.toString();
        int sequence = chunks.size();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (lastRow != null) {
            if (lastRow.tableName() != null && !lastRow.tableName().isBlank()) {
                metadata.put(METADATA_TABLE_NAME, lastRow.tableName());
            }
            metadata.put(METADATA_ROW_NUMBER, lastRow.rowNumber());
            if (lastRow.sectionTitle() != null && !lastRow.sectionTitle().isBlank()) {
                metadata.put(METADATA_SECTION_TITLE, lastRow.sectionTitle());
            }
        }
        Chunk chunk = new Chunk(
            Chunker.chunkId(doc.documentId(), sequence),
            doc.documentId(),
            doc.fileName(),
            doc.sourceType(),
            text,
            sequence,
            startOffset,
            startOffset + text.length(),
            lastRow == null ? null : lastRow.sectionTitle(),
            metadata);
        chunks.add(chunk);
        return chunk;
    }
}
