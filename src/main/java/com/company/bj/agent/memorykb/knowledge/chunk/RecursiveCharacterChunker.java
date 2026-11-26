package com.company.bj.agent.memorykb.knowledge.chunk;

import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.ContentBlock;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 递归字符切片器。
 *
 * <p>切分优先级为 <b>段落 → 句子 → 字符</b>：
 * <ol>
 *   <li>先按空行（{@code \n\n}）把文档切成段落，段落是最优先的切分点；</li>
 *   <li>段内按句子终止符（{@code 。！？.!?} 加换行）切成句子；</li>
 *   <li>单个句子仍超过上限时，退化为按字符硬切。</li>
 * </ol>
 *
 * <p>句子被累积到超过 {@code maxChunkSize} 才切出；切出后从上一片尾部回退
 * {@code overlap} 个字符作为下一片的开头，避免语义在边界被截断。
 *
 * <p>标题识别：形如 {@code #{1,6} 标题} 的行、或以 {@code 第X章} / {@code 第X节} /
 * {@code 第X条} / {@code 一、} / {@code 1.} 开头的行视为标题，
 * 其后的内容块共享该 {@code sectionTitle}。
 *
 * <p>标题识别要求分隔符后有明确的词边界：{@code 1.5 万元}、{@code 3.14 圆周率}、
 * {@code 1、2、3 三个数字} 这类正文不会被误判为标题 ——
 * 否则一次误判会让整篇文档余下内容都挂上错误的 {@code sectionTitle}。
 */
public class RecursiveCharacterChunker implements Chunker {

    /** 段落分隔符（空行）。 */
    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    /** 句子终止符集合：中文全角 + 英文半角 + 换行。 */
    private static final Pattern SENTENCE_TERMINATOR = Pattern.compile("[。！？.!?\\n]");

    /** Markdown 风格标题（1-6 级井号）。 */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");

    /**
     * 中文章节标题：{@code 第X章} / {@code 第X节} / {@code 第X条}。
     *
     * <p>包含"条"是刻意的：本工程的领域是中文知识库，法规与规章文书里
     * "第二条"/"第三条" 是最常见的标题形式，与"第X章"/"第X节" 同属结构化标题。
     */
    private static final Pattern CHINESE_CHAPTER = Pattern.compile("^第\\S{1,8}[章节条]\\s*\\S*.*$");

    /** 中文编号标题：{@code 一、}。 */
    private static final Pattern CHINESE_ENUM = Pattern.compile("^[一二三四五六七八九十百]+、\\s*\\S.*$");

    /**
     * 阿拉伯数字编号标题：{@code 1.} / {@code 1、}。
     *
     * <p>两种分隔符的约束不同：
     * <ul>
     *   <li>{@code .} 形式要求分隔符后<b>至少一个空白</b>（{@code \s+}）——
     *       否则 {@code 3.14 圆周率} 这类小数会被误判为标题；</li>
     *   <li>{@code 、} 形式不要求空白（中文顿号编号后通常直接跟文字），
     *       但同样用 {@code (?!\d)} 排除 {@code 1、2、3 三个数字} 这类顿号列举。</li>
     * </ul>
     */
    private static final Pattern ARABIC_ENUM = Pattern.compile("^\\d{1,3}[.](?!\\d)\\s+\\S.*$");

    /** 阿拉伯数字顿号编号标题：{@code 1、}，分隔符后不要求空白，但不得紧跟数字。 */
    private static final Pattern ARABIC_ENUM_DUNHAO = Pattern.compile("^\\d{1,3}、(?![\\d、])\\s*\\S.*$");

    /** 解析期元数据键：所属标题。 */
    public static final String METADATA_SECTION_TITLE = "section_title";

    /** 解析期元数据键：所属工作表名。 */
    public static final String METADATA_TABLE_NAME = "table_name";

    /** 解析期元数据键：行号。 */
    public static final String METADATA_ROW_NUMBER = "row_number";

    private final int maxChunkSize;
    private final int overlap;

    /**
     * 构造递归字符切片器。
     *
     * @param maxChunkSize 单片字符上限，必须为正
     * @param overlap      相邻切片的重叠字符数，不能为负且不得大于 {@code maxChunkSize}
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public RecursiveCharacterChunker(int maxChunkSize, int overlap) {
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
        if (doc == null || doc.content() == null || doc.content().isEmpty()) {
            return List.of();
        }
        List<ContentBlock> blocks = resolveBlocks(doc);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStart = 0;
        String currentSectionTitle = null;
        // 上一片的内容，用于在切分点计算重叠尾巴。
        // 两条切分路径（缓冲区溢出、缓冲区正好填满）都经由同一处处理，
        // 以保证"切出后回退 overlap 个字符作为下一片的开头"无条件成立。
        String previousContent = null;
        int previousEnd = 0;

        for (ContentBlock block : blocks) {
            String sectionTitle = block.sectionTitle();
            for (Segment segment : splitBlock(block)) {
                String text = segment.text();
                if (text.isEmpty()) {
                    continue;
                }
                if (current.length() > 0 && current.length() + text.length() > maxChunkSize) {
                    // 缓冲区装不下下一段：切出当前片。
                    Chunk emitted = emit(chunks, doc, current, currentStart, currentSectionTitle);
                    previousContent = emitted.content();
                    previousEnd = emitted.endOffset();
                    current.setLength(0);
                    currentSectionTitle = sectionTitle;
                }
                if (current.length() == 0) {
                    // 冷启动：若上一片存在，则预装重叠尾巴作为本片的开头。
                    // 尾巴长度按"尾巴 + 即将追加的片段 <= 上限"计算，
                    // 既保证重叠存在，又不会把本片顶出上限。
                    String tail = overlapTail(previousContent, text.length());
                    current.append(tail);
                    currentStart = previousEnd - tail.length();
                    if (current.length() == 0) {
                        currentStart = segment.startOffset();
                    }
                    currentSectionTitle = sectionTitle;
                }
                current.append(text);
                if (current.length() >= maxChunkSize) {
                    // 缓冲区正好填满：切出当前片。
                    // 不在此处预装重叠尾巴 —— 尾巴长度依赖下一段的长度，
                    // 留给下一次冷启动时按实际长度计算，避免分叉。
                    Chunk emitted = emit(chunks, doc, current, currentStart, currentSectionTitle);
                    previousContent = emitted.content();
                    previousEnd = emitted.endOffset();
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            emit(chunks, doc, current, currentStart, currentSectionTitle);
        }
        return chunks;
    }

    /**
     * 计算下一片开头的重叠片段。
     *
     * <p>重叠片段与随后追加的片段共同构成下一片，因此必须为后者预留额度：
     * 重叠长度取 {@code min(overlap, maxChunkSize - 后续片段长度)}，
     * 保证拼装后单片长度仍不超过上限。
     *
     * @param previousContent 上一片的正文，首次切分时为 {@code null}
     * @param incomingLength 即将追加的片段长度
     * @return 重叠片段文本，长度不超过 {@code overlap} 且不超过剩余额度
     */
    private String overlapTail(String previousContent, int incomingLength) {
        if (previousContent == null || overlap <= 0) {
            return "";
        }
        int budget = maxChunkSize - incomingLength;
        if (budget <= 0) {
            // 片段本身已占满上限，无法再携带重叠内容。
            return "";
        }
        int length = Math.min(overlap, budget);
        if (length <= 0 || previousContent.length() <= length) {
            return "";
        }
        return previousContent.substring(previousContent.length() - length);
    }

    /**
     * 把块文本切分为片段。
     *
     * <p>切分优先级为 <b>段落 → 句子 → 字符</b>：
     * <ol>
     *   <li>先按空行（{@code \n\n}）把块切成段落，段落是最优先的切分点；</li>
     *   <li>段内再按句子终止符切成句子；</li>
     *   <li>单个句子仍超过上限时，退化为按字符硬切。</li>
     * </ol>
     *
     * @param block 内容块
     * @return 片段列表，每个片段记录其在原文中的起始偏移
     */
    private List<Segment> splitBlock(ContentBlock block) {
        List<Segment> segments = new ArrayList<>();
        for (Paragraph paragraph : splitParagraphs(block)) {
            splitSentences(paragraph, segments);
        }
        return segments;
    }

    /**
     * 把块文本切成段落。
     *
     * <p>段落分隔符保留在前一段的末尾，从而保证各段文本拼接后与原文逐字符相同 ——
     * 切片正文必须是原文的忠实子串，不能因为分段而丢失分隔符。
     *
     * @param block 内容块
     * @return 段落列表
     */
    private List<Paragraph> splitParagraphs(ContentBlock block) {
        List<Paragraph> paragraphs = new ArrayList<>();
        String text = block.text();
        int paragraphStart = 0;
        int index = text.indexOf(PARAGRAPH_SEPARATOR);
        while (index >= 0) {
            int paragraphEnd = index + PARAGRAPH_SEPARATOR.length();
            paragraphs.add(new Paragraph(text.substring(paragraphStart, paragraphEnd), block.startOffset() + paragraphStart));
            paragraphStart = paragraphEnd;
            index = text.indexOf(PARAGRAPH_SEPARATOR, paragraphStart);
        }
        if (paragraphStart < text.length()) {
            paragraphs.add(new Paragraph(text.substring(paragraphStart), block.startOffset() + paragraphStart));
        } else if (paragraphs.isEmpty()) {
            // 全文为空或只有分隔符时，仍产出一个空段落以保持结构完整。
            paragraphs.add(new Paragraph("", block.startOffset()));
        }
        return paragraphs;
    }

    /**
     * 把段落切成句子片段并收集，句子过长时按字符硬切。
     *
     * @param paragraph 段落
     * @param segments  片段收集容器
     */
    private void splitSentences(Paragraph paragraph, List<Segment> segments) {
        String text = paragraph.text();
        int sentenceStart = 0;
        Matcher matcher = SENTENCE_TERMINATOR.matcher(text);
        while (matcher.find()) {
            int sentenceEnd = matcher.end();
            appendSegments(segments, text.substring(sentenceStart, sentenceEnd),
                paragraph.startOffset() + sentenceStart);
            sentenceStart = sentenceEnd;
        }
        if (sentenceStart < text.length()) {
            appendSegments(segments, text.substring(sentenceStart), paragraph.startOffset() + sentenceStart);
        }
    }

    /**
     * 把一段文本追加为片段，超过上限时按字符硬切。
     *
     * @param segments   片段收集容器
     * @param text       文本片段
     * @param startOffset 该片段在原文中的起始偏移
     */
    private void appendSegments(List<Segment> segments, String text, int startOffset) {
        if (text.isEmpty()) {
            return;
        }
        int offset = startOffset;
        int remaining = text.length();
        while (remaining > maxChunkSize) {
            segments.add(new Segment(text.substring(0, maxChunkSize), offset));
            offset += maxChunkSize;
            text = text.substring(maxChunkSize);
            remaining = text.length();
        }
        if (remaining > 0) {
            segments.add(new Segment(text, offset));
        }
    }

    /**
     * 产出切片。
     *
     * @param chunks        切片收集容器
     * @param doc           所属文档
     * @param content       切片正文
     * @param startOffset   切片在原文中的起始偏移
     * @param sectionTitle  切片所属标题
     * @return 刚产出的切片
     */
    private Chunk emit(List<Chunk> chunks, ParsedDocument doc, StringBuilder content, int startOffset,
        String sectionTitle) {
        String text = content.toString();
        int sequence = chunks.size();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (sectionTitle != null && !sectionTitle.isBlank()) {
            metadata.put(METADATA_SECTION_TITLE, sectionTitle);
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
            sectionTitle,
            metadata);
        chunks.add(chunk);
        return chunk;
    }

    /**
     * 解析出带标题归属的内容块。
     *
     * <p>优先使用解析阶段已给出的 {@code sectionTitle}；缺失时按标题行规则就地识别，
     * 并让后续块继承最近一次识别到的标题。
     *
     * @param doc 解析后的文档
     * @return 带标题归属的内容块列表
     */
    private List<ContentBlock> resolveBlocks(ParsedDocument doc) {
        List<ContentBlock> blocks = doc.blocks();
        if (blocks == null || blocks.isEmpty()) {
            blocks = List.of(new ContentBlock("text", doc.content(), 0, doc.content().length(), null, null, -1));
        }
        List<ContentBlock> resolved = new ArrayList<>(blocks.size());
        String currentTitle = null;
        for (ContentBlock block : blocks) {
            String title = block.sectionTitle();
            if (title == null || title.isBlank()) {
                String detected = detectSectionTitle(block.text());
                title = detected != null ? detected : currentTitle;
            }
            currentTitle = title;
            resolved.add(new ContentBlock(block.kind(), block.text(), block.startOffset(), block.endOffset(),
                title, block.tableName(), block.rowNumber()));
        }
        return resolved;
    }

    /**
     * 从块文本中识别标题行。
     *
     * @param text 块文本
     * @return 识别到的标题（不含 Markdown 井号等编号标记），未识别到时返回 {@code null}
     */
    private String detectSectionTitle(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String line : text.split("\\R")) {
            String candidate = line.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (MARKDOWN_HEADING.matcher(candidate).matches()) {
                // 去掉 Markdown 的井号标记，标题文本本身才是语义标题。
                return candidate.replaceFirst("^#{1,6}\\s+", "").trim();
            }
            if (CHINESE_CHAPTER.matcher(candidate).matches()
                || CHINESE_ENUM.matcher(candidate).matches()
                || ARABIC_ENUM.matcher(candidate).matches()
                || ARABIC_ENUM_DUNHAO.matcher(candidate).matches()) {
                return candidate;
            }
            // 只看第一行：块的首行不是标题时，块内其它行也不作为标题。
            return null;
        }
        return null;
    }

    /**
     * 文本片段及其在原文中的起始偏移。
     *
     * @param text        片段文本
     * @param startOffset 片段在原文中的起始偏移
     */
    private record Segment(String text, int startOffset) {
    }

    /**
     * 段落及其在原文中的起始偏移。
     *
     * @param text        段落文本
     * @param startOffset 段落在原文中的起始偏移
     */
    private record Paragraph(String text, int startOffset) {
    }
}
