package com.company.bj.agent.memorykb.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.ContentBlock;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link SourceTypeRoutingChunker} 的路由行为。
 *
 * <p>设计约束：文本走递归字符切片，Excel 走表格行切片（行是不可拆分的最小单位）。
 * 路由必须由独立实现类完成，而不是在调用方方法里堆 {@code if (type == X)} 分支。
 */
class SourceTypeRoutingChunkerTest {

    /** 单片字符上限。 */
    private static final int MAX_CHUNK_SIZE = 600;

    /** 相邻切片重叠字符数。 */
    private static final int OVERLAP = 90;

    private final SourceTypeRoutingChunker chunker = new SourceTypeRoutingChunker(
        new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP),
        new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP));

    @Test
    void 文本文档走递归字符切片() {
        String content = "第一章 总则\n\n第一条 本规定适用于边检业务。\n\n第二条 本规定自发布之日起施行。\n";
        ParsedDocument doc = new ParsedDocument("手册.txt", "手册.txt", "txt", content,
            List.of(new ContentBlock("text", content, 0, content.length(), null, null, -1)));

        List<Chunk> chunks = chunker.chunk(doc);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).chunkId()).isEqualTo("手册.txt#0");
        assertThat(chunks.get(0).content()).contains("本规定适用于边检业务");
    }

    @Test
    void excel文档走表格行切片() {
        String row1 = "姓名 | 证件号";
        String row2 = "张三 | 110101";
        String content = row1 + "\n" + row2;
        ParsedDocument doc = new ParsedDocument("名单.xlsx", "名单.xlsx", "excel", content,
            List.of(
                new ContentBlock("table-row", row1, 0, row1.length(), null, "Sheet1", 0),
                new ContentBlock("table-row", row2, row1.length() + 1,
                    row1.length() + 1 + row2.length(), null, "Sheet1", 1)));

        List<Chunk> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        // 同一表格行内的单元格必须出现在同一切片中。
        assertThat(chunks.get(0).content()).contains("姓名 | 证件号").contains("张三 | 110101");
        assertThat(chunks.get(0).metadata()).containsEntry("table_name", "Sheet1");
    }

    @Test
    void 未知来源类型回落到递归字符切片() {
        String content = "未知类型正文。";
        ParsedDocument doc = new ParsedDocument("未知.dat", "未知.dat", "unknown", content,
            List.of(new ContentBlock("text", content, 0, content.length(), null, null, -1)));

        List<Chunk> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("未知类型正文。");
    }
}
