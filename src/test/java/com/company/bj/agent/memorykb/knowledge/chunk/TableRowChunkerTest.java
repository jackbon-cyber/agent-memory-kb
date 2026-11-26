package com.company.bj.agent.memorykb.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.ContentBlock;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link TableRowChunker} 的表格行切片行为。
 *
 * <p>核心契约：同一表格行**绝不**被拆散到不同切片，且切片携带的行号单调递增。
 */
class TableRowChunkerTest {

    /** 表格行的块类型标识。 */
    private static final String KIND_TABLE_ROW = "table-row";

    private final TableRowChunker chunker = new TableRowChunker(120, 20);

    @Test
    void 同一行不被拆散到不同切片() {
        ParsedDocument document = documentWithRows("姓名 | 年龄 | 城市", "张三 | 28 | 北京", "李四 | 34 | 上海");

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        // 每一行都必须完整地出现在某一个切片中，不能被切成两半。
        assertThat(chunks.get(0).content()).contains("姓名 | 年龄 | 城市");
        assertThat(chunks.get(0).content()).contains("张三 | 28 | 北京");
        assertThat(chunks.get(0).content()).contains("李四 | 34 | 上海");
    }

    @Test
    void 行号单调递增() {
        ParsedDocument document = documentWithRows("姓名 | 年龄 | 城市", "张三 | 28 | 北京", "李四 | 34 | 上海");

        List<Chunk> chunks = chunker.chunk(document);

        List<Integer> rowNumbers = new ArrayList<>();
        for (Chunk chunk : chunks) {
            Object rowNumber = chunk.metadata().get("row_number");
            assertThat(rowNumber).isNotNull();
            rowNumbers.add((Integer) rowNumber);
        }
        for (int i = 1; i < rowNumbers.size(); i++) {
            assertThat(rowNumbers.get(i)).isGreaterThan(rowNumbers.get(i - 1));
        }
    }

    @Test
    void 多行累积到一片直到超过上限() {
        // 上限 120，每行约 20 字符，3 行应累积到同一片。
        ParsedDocument document = documentWithRows("姓名 | 年龄 | 城市", "张三 | 28 | 北京", "李四 | 34 | 上海");

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content().length()).isLessThanOrEqualTo(120);
    }

    @Test
    void 超过上限时按行边界切开() {
        // 每行 58 字符，上限 120：累积 2 行（117 字符）后再加第 3 行会超限，故切成 2 片。
        ParsedDocument document = documentWithRows(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | 1",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb | 2",
            "ccccccccccccccccccccccccccccccccccccccccccccccccc | 3");

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).doesNotContain("ccccccccccccccccccccccccccccccccccccccccccccccccc");
        assertThat(chunks.get(1).content()).contains("ccccccccccccccccccccccccccccccccccccccccccccccccc");
    }

    @Test
    void 单行超长时单独成片且允许超限() {
        String longCell = "x".repeat(200);
        ParsedDocument document = documentWithRows(longCell + " | 1");

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains(longCell);
        // 行不可拆，因此允许超限。
        assertThat(chunks.get(0).content().length()).isGreaterThan(120);
    }

    @Test
    void 切片携带表名与序号() {
        ParsedDocument document = documentWithRows("姓名 | 年龄", "张三 | 28");

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        Chunk chunk = chunks.get(0);
        assertThat(chunk.chunkId()).isEqualTo("d1#0");
        assertThat(chunk.sequence()).isZero();
        assertThat(chunk.metadata()).containsEntry("table_name", "Sheet1");
    }

    @Test
    void 重叠片段不会把切片顶出上限() {
        // 重叠窗口与新行拼装后必须仍不超过上限。
        // 各行内容互不相同，才能同时验证"不超限"与"不拆行"两条约束。
        String rowA = row("A");
        String rowB = row("B");
        String rowC = row("C");
        String rowD = row("D");
        ParsedDocument document = documentWithRows(rowA, rowB, rowC, rowD);
        TableRowChunker chunker = new TableRowChunker(120, 20);

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        // 除"单行超长单独成片"外，其余切片不得超过上限。
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(120));
    }

    @Test
    void 每一行恰好完整出现在一个切片中() {
        // 回归：各行内容必须互不相同，否则"半个行"与"整行"逐字节相同，
        // 断言"恰好出现于一个切片"就失去区分能力。
        // 上限 120、重叠 20、行长 90：重叠窗口落在行内部，历史上会把行拆成 70 + 20。
        String rowA = row("A");
        String rowB = row("B");
        String rowC = row("C");
        String rowD = row("D");
        assertThat(rowA).hasSize(90);
        ParsedDocument document = documentWithRows(rowA, rowB, rowC, rowD);
        TableRowChunker chunker = new TableRowChunker(120, 20);

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String row : List.of(rowA, rowB, rowC, rowD)) {
            long occurrences = chunks.stream().filter(c -> c.content().contains(row)).count();
            assertThat(occurrences).as("行 '%s' 必须完整地且仅出现在一个切片中", row).isEqualTo(1);
        }
        // 任何切片都不得含有某行的"行尾标记"却不含该行的"行头标记" ——
        // 若行被拆开，切片里就会出现只有尾巴的残片。
        for (Chunk chunk : chunks) {
            for (String row : List.of(rowA, rowB, rowC, rowD)) {
                String headMarker = "【" + row.charAt(1) + "头】";
                String tailMarker = "【" + row.charAt(1) + "尾】";
                if (chunk.content().contains(tailMarker)) {
                    assertThat(chunk.content()).as("出现行尾标记却无行头标记，说明该行被拆开")
                        .contains(headMarker);
                }
            }
        }
    }

    @Test
    void 切片区间还原的文本等于切片正文() {
        // 回归：重叠切片的 startOffset 曾比正确值大 1（ExcelDocumentParser 的游标
        // 为行间换行符多留了一位，而上一片正文不含该换行符），
        // 导致 endOffset 越界、正文与区间对不上。
        //
        // 行必须足够短，切分点前才会累积到**多行**，
        // 重叠窗口才有完整的行可以退让（否则重叠为空，两种算法给出同一结果，
        // 断言就失去区分能力）。
        String[] rows = {
            "【A】第一行短内容甲",
            "【B】第二行短内容乙",
            "【C】第三行短内容丙",
            "【D】第四行短内容丁",
            "【E】第五行短内容戊",
            "【F】第六行短内容己",
            "【G】第七行短内容庚",
            "【H】第八行短内容辛",
            "【I】第九行短内容壬",
            "【J】第十行短内容癸",
            "【K】第十一行短内容子",
            "【L】第十二行短内容丑",
            "【M】第十三行短内容寅",
            "【N】第十四行短内容卯",
            "【O】第十五行短内容辰",
        };
        ParsedDocument document = documentWithRows(rows);
        TableRowChunker chunker = new TableRowChunker(120, 20);

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        // 必须真的用上了重叠，否则本用例测不到目标缺陷：
        // 重叠按行边界对齐，因此第二片的开头应是第一片的**最后一行**。
        String lastRowOfFirstChunk = chunks.get(0).content().substring(
            chunks.get(0).content().lastIndexOf('\n') + 1);
        assertThat(lastRowOfFirstChunk).isNotBlank();
        assertThat(chunks.get(1).content()).startsWith(lastRowOfFirstChunk);
        for (Chunk chunk : chunks) {
            assertThat(chunk.startOffset()).isGreaterThanOrEqualTo(0);
            assertThat(chunk.endOffset()).isLessThanOrEqualTo(document.content().length());
            assertThat(document.content().substring(chunk.startOffset(), chunk.endOffset()))
                .as("切片 %s 的区间必须精确还原其正文", chunk.chunkId())
                .isEqualTo(chunk.content());
        }
    }

    @Test
    void 无表格行时不产生切片() {
        ParsedDocument document = new ParsedDocument("d1", "a.txt", "txt", "纯文本", List.of());

        assertThat(chunker.chunk(document)).isEmpty();
    }

    /**
     * 构造一行内容互不相同、长度固定的表格行。
     *
     * <p>各行必须内容不同：若各行逐字节相同，"半个行"与"整行"无法区分，
     * "行恰好完整出现在一个切片中"的断言就失去区分能力。
     *
     * <p>行内容刻意做成<b>非周期</b>：周期性的行（如 {@code "行A-" 重复}）其任意片段
     * 都还是行的合法子串，无法用来判定"行被拆开了"。
     * 这里让每一行的开头与结尾带上各自的标记，
     * 于是"出现行尾片段却没出现行头片段"就等价于"行被拆开"。
     *
     * @param marker 行标识
     * @return 长度为 90 的行文本
     */
    private String row(String marker) {
        String head = "【" + marker + "头】";
        String tail = "【" + marker + "尾】";
        // 4 + 20*4 + 4 = 88，再补 2 个字符凑满 90。
        String row = head + "填充内容".repeat(20) + tail + "!!";
        assertThat(row).hasSize(90);
        return row;
    }

    /**
     * 构造只含表格行的文档。
     *
     * <p>各行的偏移量按行长度连续推算（与 {@code ExcelDocumentParser} 推进游标的方式一致），
     * 行与行之间留一个 {@code \n} 连接符的位置 —— 只有这样，
     * "切片区间还原出的文本等于切片正文" 这一不变量才可能被真实地断言。
     *
     * @param rows 各行的原始内容（以 {@code |} 分隔的单元格文本）
     * @return 解析后的文档
     */
    private ParsedDocument documentWithRows(String... rows) {
        List<ContentBlock> blocks = new ArrayList<>(rows.length);
        int cursor = 0;
        for (int i = 0; i < rows.length; i++) {
            blocks.add(new ContentBlock(KIND_TABLE_ROW, rows[i], cursor, cursor + rows[i].length(),
                null, "Sheet1", i));
            // 换行符占一位，与 ExcelDocumentParser 的游标推进保持一致。
            cursor += rows[i].length() + 1;
        }
        return new ParsedDocument("d1", "a.xlsx", "excel", String.join("\n", rows), blocks);
    }
}
