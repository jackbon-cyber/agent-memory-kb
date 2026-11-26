package com.company.bj.agent.memorykb.knowledge.parse;

import java.util.List;
import java.util.Locale;

/**
 * 文档解析器注册表。
 *
 * <p>按文件名扩展名路由到具体解析器。路由规则集中在本类，
 * 新增文件类型只需注册新的 {@link DocumentParser} 实现，调用方无需改动。
 */
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    /**
     * 构造解析器注册表。
     *
     * @param parsers 可用的解析器列表，按注册顺序匹配
     */
    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    /**
     * 按文件名选择解析器。
     *
     * @param fileName 文件名（可含路径）
     * @return 支持该文件名的解析器
     * @throws IllegalArgumentException 没有解析器支持该文件名时抛出，消息中包含文件名
     */
    public DocumentParser parserFor(String fileName) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileName)) {
                return parser;
            }
        }
        throw new IllegalArgumentException("no document parser supports file: '" + fileName + "'");
    }

    /**
     * 判断文件名是否属于受支持的扩展名。
     *
     * @param fileName 文件名（可含路径）
     * @return 有解析器支持时返回 {@code true}
     */
    public boolean supports(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".txt")
            || normalized.endsWith(".xlsx")
            || normalized.endsWith(".xls");
    }
}
