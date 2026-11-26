package com.company.bj.agent.memorykb.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.bj.agent.memorykb.agent.ChatResult;
import com.company.bj.agent.memorykb.agent.KnowledgeAgent;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 问答接口的测试。
 *
 * <p>重点钉住两件事：
 * <ul>
 *   <li><b>回答与引用都要返回</b>：控制台要展示"智能体依据了哪些片段"，
 *       少了引用，回答就变成无法溯源的一句话；</li>
 *   <li><b>控制器必须先建立作用域</b>：智能体在组装期就要读作用域，
 *       控制器不建立的话，智能体会直接以「no active memory scope」失败。</li>
 * </ul>
 */
@WebMvcTest(ChatController.class)
@Import(ChatControllerTest.RealScopeConfiguration.class)
class ChatControllerTest {

    /** 装配真实的作用域解析器，让作用域语义可被观测。 */
    @TestConfiguration
    static class RealScopeConfiguration {

        /**
         * 提供真实的作用域解析器。
         *
         * @return 作用域解析器
         */
        @Bean
        ScopeResolver realScopeResolver() {
            return new ScopeResolver();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScopeResolver realScopeResolver;

    @MockBean
    private KnowledgeAgent knowledgeAgent;

    @BeforeEach
    void 准备智能体响应() {
        when(knowledgeAgent.ask(anyString(), any()))
            .thenReturn(reactor.core.publisher.Mono.just(new ChatResult("边检周期为每周一次。",
                List.of(new RetrievedChunk("手册.txt#0", "边检周期为每周一次", 0.93,
                    Map.of("file_name", "手册.txt"))))));
    }

    @Test
    void 问答返回回答与引用() throws Exception {
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"边检周期是多久\",\"sessionId\":\"s-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("边检周期为每周一次。"))
            .andExpect(jsonPath("$.citations", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.citations[0].chunkId").value("手册.txt#0"))
            .andExpect(jsonPath("$.citations[0].score").value(0.93))
            .andExpect(jsonPath("$.citations[0].metadata.file_name").value("手册.txt"));
    }

    @Test
    void 空问题返回400() throws Exception {
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INVALID_REQUEST));
    }

    @Test
    void 控制器在调用智能体前建立了作用域() {
        // 用一个会读出当前作用域的智能体桩，断言控制器确实先建立了作用域。
        ScopeObservingAgent observingAgent = new ScopeObservingAgent(realScopeResolver);
        ChatController controller = new ChatController(observingAgent, realScopeResolver);

        controller.chat(new com.company.bj.agent.memorykb.web.dto.ChatRequest("问题", "s-1"),
            "tenant-a", "owner-a");

        // 智能体在组装期就要读作用域：控制器不建立的话，智能体会直接失败。
        assertThat(observingAgent.observedScopes).hasSize(1);
        assertThat(observingAgent.observedScopes.get(0).tenantId()).isEqualTo("tenant-a");
        assertThat(observingAgent.observedScopes.get(0).ownerKey()).isEqualTo("owner-a");
    }

    /** 记录智能体调用时观察到的作用域。 */
    static class ScopeObservingAgent extends KnowledgeAgent {

        private final ScopeResolver resolver;

        final List<com.company.bj.agent.memorykb.memory.MemoryScope> observedScopes = new ArrayList<>();

        ScopeObservingAgent(ScopeResolver resolver) {
            super(null, resolver);
            this.resolver = resolver;
        }

        @Override
        public reactor.core.publisher.Mono<ChatResult> ask(String question, String sessionId) {
            observedScopes.add(resolver.current());
            return reactor.core.publisher.Mono.just(new ChatResult("回答", List.of()));
        }
    }
}
