package com.company.bj.agent.memorykb.agent;

import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用收集器。
 *
 * <p>记录一次问答中实际命中的知识库切片，作为回答的溯源凭据。
 *
 * <p><b>线程安全</b>：收集器实例被放进 {@code RuntimeContext.extras} 后，
 * 会被带到 AgentScope 的工具执行线程上（{@code boundedElastic}），
 * 而提问线程只负责读取结果 —— 两端并发访问同一实例，
 * 因此写入必须用同步手段。
 *
 * <p><b>按切片去重</b>：模型可能对同一份文档重复发起检索，
 * 若不去重，引用列表里会出现多条内容相同的条目，界面展示会失真。
 * 去重按切片标识进行，并保留<b>首次</b>命中的得分。
 */
public class CitationCollector {

    /** 命中切片，按切片标识去重后保持首次命中的顺序。 */
    private final Map<String, RetrievedChunk> chunks = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 记录一次命中。
     *
     * <p>重复命中同一切片时保留首次记录，不覆盖已有条目。
     *
     * @param chunk 命中的切片
     */
    public void add(RetrievedChunk chunk) {
        if (chunk == null) {
            return;
        }
        chunks.putIfAbsent(chunk.chunkId(), chunk);
    }

    /**
     * 记录一批命中。
     *
     * @param hitChunks 命中的切片列表，可为 {@code null}
     */
    public void addAll(List<RetrievedChunk> hitChunks) {
        if (hitChunks == null || hitChunks.isEmpty()) {
            return;
        }
        for (RetrievedChunk chunk : hitChunks) {
            add(chunk);
        }
    }

    /**
     * 取出当前已收集的全部命中。
     *
     * @return 命中快照，按首次命中顺序排列
     */
    public List<RetrievedChunk> snapshot() {
        synchronized (chunks) {
            return new ArrayList<>(chunks.values());
        }
    }

    /**
     * 清空已收集的命中。
     */
    public void clear() {
        chunks.clear();
    }
}
