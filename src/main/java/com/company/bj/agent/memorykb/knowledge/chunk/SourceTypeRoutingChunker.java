package com.company.bj.agent.memorykb.knowledge.chunk;

import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.util.List;

/**
 * 按来源类型路由的切片器。
 *
 * <p>不同内容形态使用不同切片策略：纯文本走递归字符切片，Excel 走表格行切片
 * （表格行是不可拆分的最小单位，详见 {@link TableRowChunker}）。
 *
 * <p><b>为什么路由独立成类</b>：调用方（如摄取服务）只依赖 {@link Chunker} 契约，
 * 不感知具体策略；若把类型判断写成调用方方法里的 {@code if (type == X)} 分支，
 * 每新增一种来源类型都要改动调用方，扩展点就退化成了分支堆砌。
 * 本类以"实现同一接口 + 按类型分派"的方式承载该扩展点。
 */
public class SourceTypeRoutingChunker implements Chunker {

    /** Excel 来源类型标识。 */
    public static final String SOURCE_TYPE_EXCEL = "excel";

    private final Chunker textChunker;
    private final Chunker tableChunker;

    /**
     * 构造路由切片器。
     *
     * @param textChunker  文本内容使用的切片器
     * @param tableChunker 表格内容使用的切片器
     */
    public SourceTypeRoutingChunker(Chunker textChunker, Chunker tableChunker) {
        this.textChunker = textChunker;
        this.tableChunker = tableChunker;
    }

    @Override
    public List<Chunk> chunk(ParsedDocument doc) {
        if (doc == null) {
            return List.of();
        }
        return isTable(doc) ? tableChunker.chunk(doc) : textChunker.chunk(doc);
    }

    /**
     * 判断文档是否按表格策略切片。
     *
     * @param doc 解析后的文档
     * @return 来源类型为 Excel 时返回 {@code true}
     */
    private boolean isTable(ParsedDocument doc) {
        return SOURCE_TYPE_EXCEL.equals(doc.sourceType());
    }
}
