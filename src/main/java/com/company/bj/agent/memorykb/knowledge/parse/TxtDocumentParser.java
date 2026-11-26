package com.company.bj.agent.memorykb.knowledge.parse;

import com.company.bj.agent.memorykb.knowledge.model.ContentBlock;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 纯文本（{@code .txt}）文档解析器。
 *
 * <p>按 UTF-8 读取全文，逐行产生一个 {@code kind = "text"} 的内容块。
 * 空行也保留为块，以免丢失段落结构（段落边界是切片器的首选切分点）。
 */
public class TxtDocumentParser implements DocumentParser {

    /** 纯文本块的类型标识。 */
    public static final String KIND_TEXT = "text";

    /** 受支持的扩展名。 */
    private static final String EXTENSION_TXT = ".txt";

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        return fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION_TXT);
    }

    @Override
    public ParsedDocument parse(String documentId, String fileName, byte[] bytes) throws IOException {
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<ContentBlock> blocks = splitLines(content);
        return new ParsedDocument(documentId, fileName, "txt", content, blocks);
    }

    /**
     * 把全文按行拆成内容块，并计算每行在全文中的字符区间。
     *
     * <p>行分隔符本身不计入任何一块的区间，但计入后续行的起始偏移，
     * 因此 {@code text.substring(startOffset, endOffset)} 恰好还原该行文本。
     *
     * @param content 文档全文
     * @return 按顺序排列的内容块
     */
    private List<ContentBlock> splitLines(String content) {
        List<ContentBlock> blocks = new ArrayList<>();
        int lineStart = 0;
        int lineEnd = 0;
        for (int i = 0; i <= content.length(); i++) {
            boolean atEnd = i == content.length();
            if (atEnd || content.charAt(i) == '\n') {
                // 行尾的 \r 属于 Windows 换行符，不计入区间。
                int textEnd = lineEnd;
                if (textEnd > lineStart && content.charAt(textEnd - 1) == '\r') {
                    textEnd--;
                }
                // 末尾的换行符只是行终止符，不再派生出一个空行块，
                // 否则 "abc\n" 会被误判为两行。
                if (!(atEnd && lineStart >= content.length())) {
                    blocks.add(new ContentBlock(KIND_TEXT, content.substring(lineStart, textEnd),
                        lineStart, textEnd, null, null, -1));
                }
                lineStart = i + 1;
                lineEnd = i + 1;
                continue;
            }
            lineEnd = i + 1;
        }
        return blocks;
    }
}
