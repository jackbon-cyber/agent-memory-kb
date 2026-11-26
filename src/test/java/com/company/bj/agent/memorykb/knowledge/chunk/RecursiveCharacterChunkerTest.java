package com.company.bj.agent.memorykb.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link RecursiveCharacterChunker} 的切片行为。
 *
 * <p>钉住四条核心契约：
 * <ul>
 *   <li>单片长度不超过配置上限；</li>
 *   <li>相邻切片之间存在配置长度的重叠；</li>
 *   <li>切片序号自 0 起连续递增，且 chunkId 由 {@code documentId + "#" + sequence} 唯一决定；</li>
 *   <li>短文本产生恰好一个非空切片，中文全角标点不被当作截断点。</li>
 * </ul>
 */
class RecursiveCharacterChunkerTest {

    private final RecursiveCharacterChunker chunker = new RecursiveCharacterChunker(600, 90);

    @Test
    void 切片长度不超上限() {
        String text = "段落内容。".repeat(400);
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(600));
    }

    @Test
    void 相邻切片存在配置的重叠() {
        String text = "甲乙丙丁戊己庚辛壬癸。".repeat(200);
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));
        assertThat(chunks.size()).isGreaterThan(1);
        String tail = chunks.get(0).content();
        String head = chunks.get(1).content();
        assertThat(head).endsWith(tail.substring(Math.max(0, tail.length() - 90)));
    }

    @Test
    void 所有相邻切片对都存在前缀重叠() {
        // 覆盖真实的重叠契约：切片 N+1 的**开头**是切片 N 的**结尾**。
        //
        // 注意 brief 给出的那条断言（endsWith）依赖 fixture 的周期性：
        // "甲乙丙丁戊己庚辛壬癸。" 以 11 为周期，上一片末 90 字符与下一片末 90 字符
        // 落在同一相位上、逐字节相同，于是 endsWith 侥幸成立。
        // 换成非周期文本，即使实现完全正确，endsWith 断言也会失败。
        // 因此这里另立一条刻画真实契约的断言。
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            // 每个句子的长度与内容都不同，避免任何周期性。
            builder.append("第").append(i).append("句内容各不相同用于避免周期性。");
        }
        String text = builder.toString();
        RecursiveCharacterChunker chunker = new RecursiveCharacterChunker(600, 90);

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));

        assertThat(chunks.size()).isGreaterThan(2);
        for (int i = 0; i < chunks.size() - 1; i++) {
            String tail = chunks.get(i).content();
            String head = chunks.get(i + 1).content();
            // 重叠长度受"上一片尾部"与"必须放得下下一段"双重约束，
            // 因此断言的是"存在非空的前缀重叠"，而不是固定长度。
            int overlapLength = Math.min(90, tail.length());
            assertThat(head).as("切片 %s → %s 必须存在前缀重叠", i, i + 1)
                .startsWith(tail.substring(tail.length() - overlapLength));
        }
    }

    @Test
    void 正好填满时也保留重叠() {
        // 回归：缓冲区"正好填满"的切分路径历史上直接清空缓冲区，
        // 没有为下一片预装重叠尾巴，导致该边界上的重叠为 0。
        //
        // 关键参数：上限 150、重叠 90、句子 100 字符。
        // 切片序列为 [0,100) → [50,200) → …：chunk0 之后走"缓冲区溢出"路径，
        // 而 chunk1 之后缓冲区正好填满（50 + 100 = 150），走"正好填满"路径 ——
        // 历史上正是这条路径丢掉了重叠，曾产出 chunk2 = [200,300)（与 chunk1 零重叠）。
        String sentence = "甲".repeat(99) + "。";
        RecursiveCharacterChunker chunker = new RecursiveCharacterChunker(150, 90);

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", sentence.repeat(20), List.of()));

        assertThat(chunks.size()).isGreaterThan(2);
        for (int i = 0; i < chunks.size() - 1; i++) {
            String tail = chunks.get(i).content();
            String head = chunks.get(i + 1).content();
            // 重叠长度受"上一片尾部"与"必须放得下下一段(100 字符)"双重约束：
            // min(90, 150 - 100) = 50。
            int overlapLength = Math.min(90, Math.min(tail.length(), 150 - 100));
            assertThat(overlapLength).isPositive();
            assertThat(head).as("切片 %s → %s 必须存在前缀重叠", i, i + 1)
                .startsWith(tail.substring(tail.length() - overlapLength));
        }
    }

    @Test
    void 序号自零连续() {
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", "内容".repeat(1000), List.of()));
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).sequence()).isEqualTo(i);
            assertThat(chunks.get(i).chunkId()).isEqualTo("d1#" + i);
        }
    }

    @Test
    void 短文本产生单个非空切片() {
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", "很短", List.of()));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isNotBlank();
    }

    @Test
    void 中文全角标点不被截断() {
        String text = "第一句话。第二句话！第三句话？".repeat(80);
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));
        assertThat(chunks).allSatisfy(c -> assertThat(c.content()).isNotBlank());
    }

    @Test
    void chunkId与静态规则一致() {
        // Chunker.chunkId 是同一规则的静态形式，不得出现第二份拼接逻辑。
        assertThat(Chunker.chunkId("d1", 7)).isEqualTo("d1#7");
        assertThat(Chunker.chunkId("中文名.txt", 0)).isEqualTo("中文名.txt#0");
    }

    @Test
    void 切片携带原文区间且区间可回溯原文() {
        String text = "第一段内容。第二段内容！".repeat(60);
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.startOffset()).isGreaterThanOrEqualTo(0);
            assertThat(chunk.endOffset()).isGreaterThan(chunk.startOffset());
            assertThat(text.substring(chunk.startOffset(), chunk.endOffset())).contains(chunk.content());
        });
    }

    @Test
    void 切片携带所属标题且元数据只含解析期字段() {
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt",
            "# 第一章 总则\n\n本章规定适用范围。\n\n第二章 附则\n\n本细则自发布之日起施行。", List.of()));

        assertThat(chunks).isNotEmpty();
        Chunk first = chunks.get(0);
        assertThat(first.sectionTitle()).isEqualTo("第一章 总则");
        assertThat(first.metadata()).containsEntry("section_title", "第一章 总则");
        // 入库元数据由 Task 4 的 IngestionService 重新组装，此处不得重复承载。
        assertThat(first.metadata()).doesNotContainKeys(
            "document_id", "file_name", "source_type", "sequence", "start_offset", "end_offset", "ingested_at");
    }

    @Test
    void 重叠片段不会把切片顶出上限() {
        // 重叠窗口与下一段拼装后必须仍不超过上限：
        // overlap(90) + 单句(100) 若不加截断会得到 190 > 150。
        String text = "甲".repeat(99) + "。";
        RecursiveCharacterChunker chunker = new RecursiveCharacterChunker(150, 90);

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text.repeat(20), List.of()));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(150));
    }

    @Test
    void 切片正文是原文的忠实子串() {
        // 段落分隔符不得在分段过程中丢失，否则切片正文与原文对不上。
        String text = "第一段。\n\n第二段。\n\n第三段。";
        RecursiveCharacterChunker chunker = new RecursiveCharacterChunker(600, 90);

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(text);
    }

    @Test
    void 优先在段落边界切片() {
        // 段落1 = 500 字符，段落2 = 300 字符，上限 600：
        // 第一片应正好停在段落边界（500 + 2 个换行符 = 502），而不是在段落中间截断。
        String paragraph1 = "甲".repeat(499) + "。";
        String paragraph2 = "乙".repeat(299) + "。";
        String text = paragraph1 + "\n\n" + paragraph2;
        RecursiveCharacterChunker chunker = new RecursiveCharacterChunker(600, 90);

        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo(paragraph1 + "\n\n");
        assertThat(chunks.get(0).content().length()).isLessThanOrEqualTo(600);
        // 第二片带 90 字符重叠，且仍以段落内容结尾。
        assertThat(chunks.get(1).content()).endsWith(paragraph2);
        assertThat(chunks.get(1).content().length()).isLessThanOrEqualTo(600);
    }

    @Test
    void 正文中的小数不被识别为标题() {
        // 回归：ARABIC_ENUM 曾允许分隔符后零个空白，导致 "1.5 万元" 被识别为标题。
        // 一旦误判，resolveBlocks 会把它传播给之后每一个块，
        // 使整篇文档余下内容都挂上错误的 section_title。
        assertThat(sectionTitleOf("1.5 万元以上的罚款按第二级处理。")).isNull();
        assertThat(sectionTitleOf("3.14 圆周率是一个常数。")).isNull();
    }

    @Test
    void 正文中的顿号列举不被识别为标题() {
        // 回归："1、2、3 三个数字" 曾被识别为标题。
        assertThat(sectionTitleOf("1、2、3 三个数字需要分别登记。")).isNull();
    }

    @Test
    void 真正的阿拉伯数字编号仍被识别为标题() {
        // 收紧正则后，正当的编号标题必须仍能识别出来。
        assertThat(sectionTitleOf("1. 第一部分总则")).isEqualTo("1. 第一部分总则");
        assertThat(sectionTitleOf("2、第二部分 附则")).isEqualTo("2、第二部分 附则");
    }

    @Test
    void 中文条款标题被识别为标题() {
        // 中文法规文书里 "第二条" 是最常见的标题形式，与"第X章"/"第X节" 同级。
        assertThat(sectionTitleOf("第二条 适用范围")).isEqualTo("第二条 适用范围");
        assertThat(sectionTitleOf("第三章 罚则")).isEqualTo("第三章 罚则");
        assertThat(sectionTitleOf("第四节 附则")).isEqualTo("第四节 附则");
    }

    @Test
    void 普通正文不被识别为标题() {
        assertThat(sectionTitleOf("这是一句普通正文，没有任何编号前缀。")).isNull();
        assertThat(sectionTitleOf("边检知识库用于管理法规文档。")).isNull();
    }

    @Test
    void 空文本不产生切片() {
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", "", List.of()));
        assertThat(chunks).isEmpty();
    }

    /**
     * 解析一段文本并取其识别到的标题。
     *
     * @param text 待识别的文本
     * @return 识别到的标题，未识别到时返回 {@code null}
     */
    private String sectionTitleOf(String text) {
        List<Chunk> chunks = chunker.chunk(new ParsedDocument("d1", "a.txt", "txt", text, List.of()));
        return chunks.isEmpty() ? null : chunks.get(0).sectionTitle();
    }
}
