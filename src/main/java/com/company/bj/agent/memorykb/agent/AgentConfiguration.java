package com.company.bj.agent.memorykb.agent;

import com.company.bj.agent.memorykb.config.AgentProperties;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.memory.LongTermStore;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能体组件的 Spring 装配。
 *
 * <p>装配集中在本类，避免同一批 Bean 散落在多个配置类里。
 */
@Configuration
public class AgentConfiguration {

    /**
     * 提供对话模型。
     *
     * <p>基址使用 OpenAI 兼容模式的完整路径（含 {@code /v1}）：
     * AgentScope 的 {@code OpenAIClient} 在基址路径以 {@code /v\d+} 结尾时，
     * 会把默认的 {@code /v1/chat/completions} 端点中的 {@code /v1} 前缀去掉，
     * 最终请求的正是 {@code <baseUrl>/chat/completions} —— 与 OpenAI 兼容网关一致。
     *
     * @param props 智能体配置
     * @return 对话模型
     */
    @Bean
    public Model agentChatModel(AgentProperties props) {
        return OpenAIChatModel.builder()
            .apiKey(props.getApiKey())
            .baseUrl(props.getBaseUrl())
            .modelName(props.getModel())
            .build();
    }

    /**
     * 提供智能体工厂。
     *
     * @param props         智能体配置
     * @param model         对话模型
     * @param retriever     知识库检索端口
     * @param longTermStore 长期记忆存储
     * @param resolver      作用域解析器
     * @return 智能体工厂
     */
    @Bean
    public AgentFactory agentFactory(AgentProperties props, Model agentChatModel,
        KnowledgeRetriever retriever, LongTermStore longTermStore, ScopeResolver resolver) {
        return new AgentFactory(props, agentChatModel, retriever, longTermStore, resolver);
    }

    /**
     * 提供 ReAct 智能体。
     *
     * <p><b>单例</b>：AgentScope 的会话状态由 {@code RuntimeContext.sessionId} 隔离，
     * 同一个智能体实例可安全服务多个会话 —— 每个会话在状态存储里各占一格。
     *
     * @param factory 智能体工厂
     * @return ReAct 智能体
     */
    @Bean
    public ReActAgent knowledgeReActAgent(AgentFactory factory) {
        return factory.create();
    }

    /**
     * 提供知识问答智能体。
     *
     * @param agent    ReAct 智能体
     * @param resolver 作用域解析器
     * @return 知识问答智能体
     */
    @Bean
    public KnowledgeAgent knowledgeAgent(ReActAgent knowledgeReActAgent, ScopeResolver resolver) {
        return new KnowledgeAgent(knowledgeReActAgent, resolver);
    }
}
