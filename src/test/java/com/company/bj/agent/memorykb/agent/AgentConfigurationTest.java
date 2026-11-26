package com.company.bj.agent.memorykb.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 校验智能体相关 Bean 的 Spring 装配结果。
 *
 * <p>重点是<b>模型 Bean 的唯一性</b>与<b>工具挂载的完整性</b>：
 * 若 {@code Model} 出现两个候选，注入点会歧义而上下文根本起不来；
 * 若工具漏挂，智能体就只能凭记忆作答 —— 这类缺陷在功能测试里表现为
 * 「回答里没有引用」，很难定位到是装配丢了工具。
 *
 * <p>本测试不触碰 Chroma 服务与模型网关：所有 Bean 都是惰性构造，不发起任何网络请求。
 */
@SpringBootTest
class AgentConfigurationTest {

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private Model agentChatModel;

    @Autowired
    private AgentFactory agentFactory;

    @Autowired
    private ReActAgent knowledgeReActAgent;

    @Autowired
    private KnowledgeAgent knowledgeAgent;

    @Test
    void 智能体相关Bean被正确装配() {
        assertThat(agentProperties.getModel()).isEqualTo("deepseek-v4.1-flash-1017");
        assertThat(agentChatModel).isNotNull();
        assertThat(agentFactory).isNotNull();
        assertThat(knowledgeReActAgent).isNotNull();
        assertThat(knowledgeAgent).isNotNull();
    }

    @Test
    void 智能体挂载了知识检索与长期记忆工具() {
        assertThat(knowledgeReActAgent.getToolkit().getToolNames())
            .contains("search_knowledge", "record_to_memory", "retrieve_from_memory");
    }

    @Test
    void 模型名与配置一致() {
        assertThat(agentChatModel.getModelName()).isEqualTo(agentProperties.getModel());
    }
}
