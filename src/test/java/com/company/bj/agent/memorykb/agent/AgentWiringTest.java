package com.company.bj.agent.memorykb.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.config.AgentProperties;
import com.company.bj.agent.memorykb.knowledge.FilterCondition;
import com.company.bj.agent.memorykb.knowledge.FilterLogic;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.company.bj.agent.memorykb.memory.LongTermStore;
import com.company.bj.agent.memorykb.memory.MemoryRecord;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能体装配与作用域隔离的测试。
 *
 * <p>用脚本化模型替代真实大模型：真实模型需要网络与凭据，
 * 会让「装配是否正确」这类结构性断言变得不稳定。
 * 真实模型的连通性由 {@code AgentChatIT} 单独覆盖。
 */
class AgentWiringTest {

    /** 记录工具实际执行时观察到的作用域。 */
    static final List<MemoryScope> OBSERVED_SCOPES = new ArrayList<>();

    /** 记录长期记忆工具实际读到的记录。 */
    static final List<String> OBSERVED_RECORDS = new ArrayList<>();

    /** 脚本化模型：按预设脚本依次返回工具调用与文本。 */
    static class ScriptedModel implements Model {

        private final AtomicInteger round = new AtomicInteger();

        private final List<ToolUseBlock> toolCalls;

        ScriptedModel(List<ToolUseBlock> toolCalls) {
            this.toolCalls = toolCalls;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
            GenerateOptions options) {
            int index = round.getAndIncrement();
            if (index < toolCalls.size()) {
                return Flux.just(ChatResponse.builder()
                    .id("round-" + index)
                    .content(List.of((ContentBlock) toolCalls.get(index)))
                    .build());
            }
            return Flux.just(ChatResponse.builder()
                .id("round-" + index)
                .content(List.of((ContentBlock) TextBlock.builder().text("最终回答").build()))
                .build());
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    /** 记录工具执行时所在线程与作用域，便于断言跨线程行为。 */
    static class ScopeObservingTool {

        @io.agentscope.core.tool.Tool(name = "observe_scope", description = "观察作用域")
        public String observeScope(
            @io.agentscope.core.tool.ToolParam(name = "query", description = "q") String query,
            RuntimeContext context) {
            Object scope = context.getExtra().get(AgentContextKeys.MEMORY_SCOPE);
            if (scope instanceof MemoryScope memoryScope) {
                OBSERVED_SCOPES.add(memoryScope);
            }
            return "observed";
        }
    }

    /** 固定返回指定命中的检索端口。 */
    static class StubRetriever implements KnowledgeRetriever {

        private final List<RetrievedChunk> hits;

        StubRetriever(List<RetrievedChunk> hits) {
            this.hits = hits;
        }

        @Override
        public List<RetrievedChunk> retrieve(String query, List<FilterCondition> filters,
            FilterLogic logic, int topK, Double scoreThreshold) {
            return hits;
        }
    }

    /** 记录写入内容的长期记忆存储。 */
    static class StubLongTermStore implements LongTermStore {

        @Override
        public MemoryRecord remember(String content) {
            OBSERVED_RECORDS.add(content);
            return new MemoryRecord("id-1", content, 1.0, 0L, MemoryScope.defaultScope(), false);
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
            return List.of();
        }

        @Override
        public List<MemoryRecord> list(int topK) {
            return List.of();
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
    void 智能体装配后挂载了知识检索与长期记忆工具() {
        AgentProperties props = new AgentProperties();
        AgentFactory factory = new AgentFactory(props, new ScriptedModel(List.of()),
            new StubRetriever(List.of()), new StubLongTermStore(), new ScopeResolver());

        ReActAgent agent = factory.create();

        assertThat(agent).isNotNull();
        assertThat(agent.getToolkit().getToolNames())
            .contains("search_knowledge", "record_to_memory", "retrieve_from_memory");
    }

    @Test
    void 模型名与默认值符合规格() {
        AgentProperties props = new AgentProperties();

        assertThat(props.getModel()).isEqualTo("deepseek-v4.1-flash-1017");
        assertThat(props.getName()).isEqualTo("knowledge-agent");
        assertThat(props.getMaxIters()).isEqualTo(8);
        // 模型名不得带 vanchin/ 前缀：带前缀访问百炼网关会返回「产品未激活」。
        assertThat(props.getModel()).doesNotStartWith("vanchin/");
        // 凭据默认留空，只能由环境变量注入。
        assertThat(props.getApiKey()).isEmpty();
    }

    @Test
    void 知识检索命中被记录为引用() {
        RetrievedChunk hit = new RetrievedChunk("chunk-1", "退款政策：7 个工作日", 0.93,
            Map.of("file_name", "policy.txt"));
        ScopeResolver resolver = new ScopeResolver();
        AgentFactory factory = new AgentFactory(new AgentProperties(),
            new ScriptedModel(List.of(toolCall("search_knowledge", Map.of("query", "退款")))),
            new StubRetriever(List.of(hit)), new StubLongTermStore(), resolver);
        KnowledgeAgent knowledgeAgent = new KnowledgeAgent(factory.create(), resolver);

        MemoryScope scope = MemoryScope.of("tenant-a", "owner-a");
        ChatResult result = resolver.withScopeResult(scope,
            () -> knowledgeAgent.ask("退款要多久", "session-1").block());

        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo("最终回答");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(result.citations().get(0).score()).isEqualTo(0.93);
    }

    @Test
    void 长期记忆工具能跨线程读到作用域() {
        OBSERVED_SCOPES.clear();
        ScopeResolver resolver = new ScopeResolver();
        ToolUseBlock call = toolCall("observe_scope", Map.of("query", "x"));

        io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerTool(new ScopeObservingTool());
        ReActAgent agent = ReActAgent.builder()
            .name("scope-observer")
            .sysPrompt("probe")
            .model(new ScriptedModel(List.of(call)))
            .toolkit(toolkit)
            .maxIters(3)
            .defaultSessionId("default")
            .build();

        MemoryScope scope = MemoryScope.of("tenant-b", "owner-b");
        resolver.withScopeResult(scope, () -> agent.call("x", RuntimeContext.builder()
            .sessionId("s1")
            .put(AgentContextKeys.MEMORY_SCOPE, scope)
            .build()).block());

        assertThat(OBSERVED_SCOPES).containsExactly(scope);
    }

    @Test
    void 无作用域时长期记忆工具明确拒绝() {
        ScopeResolver resolver = new ScopeResolver();
        LongTermMemoryTool tool = new LongTermMemoryTool(new StubLongTermStore(), resolver);
        RuntimeContext emptyContext = RuntimeContext.builder().sessionId("s1").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> tool.recordToMemory("记住这条", emptyContext))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory scope is missing");
    }

    @Test
    void 智能体调用链返回Mono而非阻塞() {
        ScopeResolver resolver = new ScopeResolver();
        AgentFactory factory = new AgentFactory(new AgentProperties(),
            new ScriptedModel(List.of()),
            new StubRetriever(List.of()), new StubLongTermStore(), resolver);
        KnowledgeAgent knowledgeAgent = new KnowledgeAgent(factory.create(), resolver);

        Mono<ChatResult> mono = resolver.withScopeResult(MemoryScope.defaultScope(),
            () -> knowledgeAgent.ask("你好", "session-2"));

        assertThat(mono).isInstanceOf(Mono.class);
        ChatResult result = mono.block();
        assertThat(result).isNotNull();
        assertThat(result.citations()).isEmpty();
    }

    private ToolUseBlock toolCall(String name, Map<String, Object> input) {
        // content 必须是 input 的 JSON 字符串表示：AgentScope 的工具入参校验读的是 content，
        // 只填 input 会因「必填参数缺失」被判定为非法调用，工具根本不会执行。
        return ToolUseBlock.builder()
            .id("call-1")
            .name(name)
            .input(input)
            .content(toJson(input))
            .build();
    }

    private String toJson(Map<String, Object> input) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("测试用例构造工具入参失败", e);
        }
    }
}
