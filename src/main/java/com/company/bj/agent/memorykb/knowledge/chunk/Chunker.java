package com.company.bj.agent.memorykb.knowledge.chunk;

import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.util.List;

/**
 * 切片器扩展点。
 *
 * <p>把解析后的文档切成若干 {@link Chunk}。不同内容形态使用不同策略：
 * 纯文本走递归字符切片，表格走表格行切片。
 */
public interface Chunker {

    /**
     * 把文档切成切片序列。
     *
     * @param doc 解析后的文档
     * @return 按顺序排列的切片，序号自 0 起连续递增
     */
    List<Chunk> chunk(ParsedDocument doc);

    /**
     * 生成切片唯一标识。
     *
     * <p>本方法只是 {@link Chunk#chunkId(String, int)} 的委托，
     * 规则的全工程唯一实现位于 {@link Chunk}，此处不得出现第二份拼接逻辑。
     *
     * @param documentId 所属文档标识
     * @param sequence   切片序号
     * @return 切片唯一标识
     */
    static String chunkId(String documentId, int sequence) {
        return Chunk.chunkId(documentId, sequence);
    }
}
