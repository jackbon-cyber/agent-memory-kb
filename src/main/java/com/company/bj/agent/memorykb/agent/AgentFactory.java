package com.company.bj.agent.memorykb.agent;

import com.company.bj.agent.memorykb.config.AgentProperties;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.memory.LongTermStore;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;

/**
 * 智能体工厂。
 *
 * <p>负责把「模型 + 工具 + 状态存储」装配成一个可用的 {@link ReActAgent}。
 *
 * <p><b>工具挂载的是本工程自研的两个工具</b>（{@link KnowledgeSearchTool} 与
 * {@link LongTermMemoryTool}），而不是 AgentScope 自带的
 * {@code KnowledgeRetrievalTools} / {@code LongTermMemoryTools}：
 * <ul>
 *   <li>自带的知识检索工具没有元数据过滤能力，
 *       且不返回结构化命中，引用无法可靠溯源；</li>
 *   <li>自带的长期记忆工具签名里没有 {@code RuntimeContext}，
 *       拿不到请求级作用域，跨线程执行时作用域检查会失效。</li>
 * </ul>
 *
 * <p><b>状态存储</b>使用 {@link InMemoryAgentStateStore}：
 * 会话状态（短期记忆）按会话标识隔离，同一会话的多轮对话共享上下文，
 * 不同会话之间互不可见。
 */
public class AgentFactory {

    private final AgentProperties props;
    private final Model model;
    private final KnowledgeRetriever retriever;
    private final LongTermStore longTermStore;
    private final ScopeResolver resolver;

    /**
     * 构造智能体工厂。
     *
     * @param props         智能体配置
     * @param model         对话模型
     * @param retriever     知识库检索端口
     * @param longTermStore 长期记忆存储
     * @param resolver      作用域解析器
     */
    public AgentFactory(AgentProperties props, Model model, KnowledgeRetriever retriever,
        LongTermStore longTermStore, ScopeResolver resolver) {
        this.props = props;
        this.model = model;
        this.retriever = retriever;
        this.longTermStore = longTermStore;
        this.resolver = resolver;
    }

    /**
     * 装配一个 {@link ReActAgent}。
     *
     * @return 装配好的智能体
     */
    public ReActAgent create() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new KnowledgeSearchTool(retriever));
        toolkit.registerTool(new LongTermMemoryTool(longTermStore, resolver));

        return ReActAgent.builder()
            .name(props.getName())
            .sysPrompt(props.getSysPrompt())
            .model(model)
            .toolkit(toolkit)
            .maxIters(props.getMaxIters())
            .stateStore(new InMemoryAgentStateStore())
            .defaultSessionId(props.getName())
            .build();
    }
}
