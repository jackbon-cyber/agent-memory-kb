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
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实大模型连通性集成测试。
 *
 * <p>验证「模型装配」这一环节在<b>真实网关</b>上确实可用：
 * 请求能发出、响应能回来、模型名被网关接受。
 *
 * <p><b>没有凭据时整类被跳过</b>，跳过的原因是对话模型属于外部依赖，
 * 不在本工程的单元测试范围内；跳过<b>不等于</b>通过，
 * 验收环节会显式要求这条用例跑通。
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class AgentChatIT {

    /** 真实嵌入网关的凭据环境变量名。 */
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    @Test
    void 真实模型能完成一次对话() {
        AgentProperties props = new AgentProperties();
        props.setApiKey(System.getenv(API_KEY_ENV));

        Model model = OpenAIChatModel.builder()
            .apiKey(props.getApiKey())
            .baseUrl(props.getBaseUrl())
            .modelName(props.getModel())
            .build();

        ScopeResolver resolver = new ScopeResolver();
        AgentFactory factory = new AgentFactory(props, model, new StubRetriever(),
            new StubLongTermStore(), resolver);
        KnowledgeAgent knowledgeAgent = new KnowledgeAgent(factory.create(), resolver);

        ChatResult result = resolver.withScopeResult(MemoryScope.defaultScope(),
            () -> knowledgeAgent.ask("用一句话介绍你自己", "it-session").block());

        assertThat(result).isNotNull();
        assertThat(result.answer()).as("真实模型应返回非空回答").isNotBlank();
        System.out.println("[真实模型回答] " + result.answer());
    }

    /** 检索端口桩：本用例不访问向量库。 */
    static class StubRetriever implements KnowledgeRetriever {

        @Override
        public List<RetrievedChunk> retrieve(String query, List<FilterCondition> filters,
            FilterLogic logic, int topK, Double scoreThreshold) {
            return List.of();
        }
    }

    /** 长期记忆存储桩：本用例不访问向量库。 */
    static class StubLongTermStore implements LongTermStore {

        @Override
        public MemoryRecord remember(String content) {
            return new MemoryRecord("it-id", content, 1.0, 0L, MemoryScope.defaultScope(), false);
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
}
