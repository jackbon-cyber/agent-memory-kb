package com.company.bj.agent.memorykb.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.knowledge.FilterCondition;
import com.company.bj.agent.memorykb.knowledge.FilterLogic;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.company.bj.agent.memorykb.memory.LongTermStore;
import com.company.bj.agent.memorykb.memory.MemoryRecord;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 长期记忆工具的作用域行为测试。
 *
 * <p>验证工具在<b>跨线程</b>场景下仍然强制施加作用域：
 * 读写的必须是提问时确定的那个作用域，而不是工具线程上的任何默认作用域。
 */
class LongTermMemoryToolTest {

    /** 记录读到的记录内容。 */
    static final List<String> READ_RESULTS = new ArrayList<>();

    /** 把观察到的作用域记录下来的长期记忆存储。 */
    static class ScopeObservingStore implements LongTermStore {

        private final ScopeResolver resolver;

        private final List<MemoryRecord> records = new ArrayList<>();

        ScopeObservingStore(ScopeResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public MemoryRecord remember(String content) {
            MemoryRecord record = new MemoryRecord("id-" + records.size(), content, 1.0, 0L,
                resolver.current(), false);
            records.add(record);
            return record;
        }

        @Override
        public MemoryRecord remember(String content, double weight) {
            return remember(content);
        }

        @Override
        public Optional<MemoryRecord> find(String id) {
            return Optional.empty();
        }

        @Override
        public List<MemoryRecord> search(String query, int topK) {
            READ_RESULTS.add(resolver.current().tenantId());
            return records;
        }

        @Override
        public List<MemoryRecord> list(int topK) {
            return records;
        }

        @Override
        public boolean forget(String id) {
            return false;
        }

        @Override
        public List<MemoryRecord> archived(int topK) {
            return List.of();
        }

        @Override
        public Optional<MemoryRecord> consolidate(List<String> ids) {
            return Optional.empty();
        }
    }

    @Test
    void 记录与召回都在提问时确定的作用域内进行() {
        READ_RESULTS.clear();

        ScopeResolver resolver = new ScopeResolver();
        LongTermMemoryTool tool = new LongTermMemoryTool(new ScopeObservingStore(resolver), resolver);

        MemoryScope scope = MemoryScope.of("tenant-x", "owner-x");
        RuntimeContext context = RuntimeContext.builder()
            .sessionId("s1")
            .put(AgentContextKeys.MEMORY_SCOPE, scope)
            .build();

        String recordResult = tool.recordToMemory("用户偏好中文回答", context);
        String retrieveResult = tool.retrieveFromMemory("语言偏好", context);

        assertThat(recordResult).isEqualTo("已记录到长期记忆。");
        assertThat(retrieveResult).contains("用户偏好中文回答");
        // 存储层能读到作用域，说明工具确实在工具线程上重建了作用域。
        // 若工具没有重建作用域，resolver.current() 会抛「no active memory scope」。
        assertThat(READ_RESULTS).containsExactly("tenant-x");
    }

    @Test
    void 缺少作用域时工具拒绝执行而不是落到默认作用域() {
        ScopeResolver resolver = new ScopeResolver();
        LongTermMemoryTool tool = new LongTermMemoryTool(new ScopeObservingStore(resolver), resolver);

        RuntimeContext context = RuntimeContext.builder().sessionId("s1").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> tool.retrieveFromMemory("任意", context))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory scope is missing");
    }

    @Test
    void 检索工具把命中写入引用收集器并支持元数据过滤() {
        CitationCollector collector = new CitationCollector();
        RuntimeContext context = RuntimeContext.builder()
            .sessionId("s1")
            .put(AgentContextKeys.CITATION_COLLECTOR, collector)
            .build();

        RetrievedChunk hit = new RetrievedChunk("chunk-9", "片段正文", 0.88,
            java.util.Map.of("file_name", "手册.txt", "document_id", "手册.txt"));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(new SingleHitRetriever(hit));

        String output = tool.searchKnowledge("查询", 3, "手册.txt", null, context);

        assertThat(output).contains("片段正文").contains("手册.txt");
        assertThat(collector.snapshot()).hasSize(1);
        assertThat(collector.snapshot().get(0).chunkId()).isEqualTo("chunk-9");
    }

    @Test
    void 检索工具命中为空时给出明确提示() {
        CitationCollector collector = new CitationCollector();
        RuntimeContext context = RuntimeContext.builder()
            .sessionId("s1")
            .put(AgentContextKeys.CITATION_COLLECTOR, collector)
            .build();

        KnowledgeSearchTool tool = new KnowledgeSearchTool(new SingleHitRetriever(null));

        String output = tool.searchKnowledge("查询", 3, null, null, context);

        assertThat(output).contains("未找到");
        assertThat(collector.snapshot()).isEmpty();
    }

    /** 返回单个固定命中的检索端口。 */
    static class SingleHitRetriever implements KnowledgeRetriever {

        private final RetrievedChunk hit;

        SingleHitRetriever(RetrievedChunk hit) {
            this.hit = hit;
        }

        @Override
        public List<RetrievedChunk> retrieve(String query, List<FilterCondition> filters,
            FilterLogic logic, int topK, Double scoreThreshold) {
            return hit == null ? List.of() : List.of(hit);
        }
    }
}
