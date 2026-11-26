package com.company.bj.agent.memorykb.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.bj.agent.memorykb.memory.EpisodicMemoryStore;
import com.company.bj.agent.memorykb.memory.Episode;
import com.company.bj.agent.memorykb.memory.LongTermStore;
import com.company.bj.agent.memorykb.memory.MemoryRecord;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import com.company.bj.agent.memorykb.memory.TaskItem;
import com.company.bj.agent.memorykb.memory.WorkingMemoryStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 四层记忆查看接口的测试。
 *
 * <p><b>作用域用的是真对象而不是 mock</b>：作用域隔离是本工程最不能出错
 * 的约束，如果把 {@code ScopeResolver} 也 mock 掉，测试就只能断言
 * 「控制器调用了存储」而<b>无法断言「存储是在正确的作用域下被调用的」</b> ——
 * 那恰恰是越权防线所在。因此这里装配真实的解析器，
 * 让存储桩在调用时读取当前作用域，从而把隔离语义钉死在测试里。
 */
@WebMvcTest(MemoryController.class)
@Import(MemoryControllerTest.RealScopeConfiguration.class)
class MemoryControllerTest {

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

    /** 记录被调用时观察到的作用域。 */
    static class ScopeObservingLongTermStore implements LongTermStore {

        private final ScopeResolver resolver;

        final List<MemoryScope> observedScopes = new ArrayList<>();

        ScopeObservingLongTermStore(ScopeResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public MemoryRecord remember(String content) {
            return new MemoryRecord("m-1", content, 1.0, 100L, resolver.current(), false);
        }

        @Override
        public MemoryRecord remember(String content, double weight) {
            return remember(content);
        }

        @Override
        public java.util.Optional<MemoryRecord> find(String id) {
            return java.util.Optional.empty();
        }

        @Override
        public List<MemoryRecord> search(String query, int topK) {
            observedScopes.add(resolver.current());
            return List.of();
        }

        @Override
        public List<MemoryRecord> list(int topK) {
            observedScopes.add(resolver.current());
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
        public java.util.Optional<MemoryRecord> consolidate(List<String> ids) {
            return java.util.Optional.empty();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScopeResolver realScopeResolver;

    @MockBean
    private ReActAgent agent;

    @MockBean
    private LongTermStore longTermStore;

    @MockBean
    private WorkingMemoryStore workingMemoryStore;

    @MockBean
    private EpisodicMemoryStore episodicMemoryStore;

    @BeforeEach
    void 准备智能体状态() {
        AgentState state = AgentState.builder()
            .sessionId("default")
            .context(List.of(
                Msg.builder().role(MsgRole.USER).textContent("边检怎么做").build(),
                Msg.builder().role(MsgRole.ASSISTANT).textContent("每周一次").build()))
            .build();
        when(agent.getAgentState(any(), any())).thenReturn(state);
    }

    @Test
    void 短期记忆返回会话消息() throws Exception {
        mockMvc.perform(get("/api/memory/short-term"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.messages[0]")
                .value(org.hamcrest.Matchers.containsString("边检怎么做")))
            .andExpect(jsonPath("$.messages[1]")
                .value(org.hamcrest.Matchers.containsString("每周一次")));
    }

    @Test
    void 长期记忆返回条目列表() throws Exception {
        when(longTermStore.list(anyInt())).thenReturn(List.of(
            new MemoryRecord("m-1", "用户偏好中文", 1.0, 100L, MemoryScope.defaultScope(), false)));

        mockMvc.perform(get("/api/memory/long-term"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.records", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.records[0].id").value("m-1"))
            .andExpect(jsonPath("$.records[0].content").value("用户偏好中文"))
            .andExpect(jsonPath("$.records[0].archived").value(false));
    }

    @Test
    void 工作记忆返回任务项与状态() throws Exception {
        when(workingMemoryStore.tasks()).thenReturn(List.of(
            new TaskItem("t-1", "巡检排班", TaskItem.Status.IN_PROGRESS, 100L)));

        mockMvc.perform(get("/api/memory/working"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.tasks[0].id").value("t-1"))
            .andExpect(jsonPath("$.tasks[0].description").value("巡检排班"))
            .andExpect(jsonPath("$.tasks[0].status").value("IN_PROGRESS"));
    }

    @Test
    void 情景记忆返回事件序列() throws Exception {
        when(episodicMemoryStore.episodes()).thenReturn(List.of(
            new Episode("e-1", "巡检", List.of("到岗", "记录"), 100L, MemoryScope.defaultScope())));

        mockMvc.perform(get("/api/memory/episodic"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.episodes", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.episodes[0].id").value("e-1"))
            .andExpect(jsonPath("$.episodes[0].type").value("巡检"))
            .andExpect(jsonPath("$.episodes[0].events", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void 请求头声明的作用域被原样传给存储层() throws Exception {
        ScopeObservingLongTermStore store = new ScopeObservingLongTermStore(realScopeResolver);
        MemoryController controller =
            new MemoryController(agent, store, workingMemoryStore, episodicMemoryStore, realScopeResolver);

        controller.longTerm("tenant-a", "owner-a");

        // 存储层是在「请求头声明的作用域」下被调用的 ——
        // 越权防线由这一层把守，控制器只负责把作用域建立起来。
        assertThat(store.observedScopes).hasSize(1);
        assertThat(store.observedScopes.get(0).tenantId()).isEqualTo("tenant-a");
        assertThat(store.observedScopes.get(0).ownerKey()).isEqualTo("owner-a");
    }

    @Test
    void 缺少作用域请求头时回落到缺省作用域() throws Exception {
        ScopeObservingLongTermStore store = new ScopeObservingLongTermStore(realScopeResolver);
        MemoryController controller =
            new MemoryController(agent, store, workingMemoryStore, episodicMemoryStore, realScopeResolver);

        controller.longTerm(null, null);

        // 缺少请求头是正常调用形态（单机部署、调试），不应报错。
        assertThat(store.observedScopes).hasSize(1);
        assertThat(store.observedScopes.get(0).tenantId()).isEqualTo("default");
        assertThat(store.observedScopes.get(0).ownerKey()).isEqualTo("default");
    }

    @Test
    void 短期记忆按会话标识读取对应状态() throws Exception {
        mockMvc.perform(get("/api/memory/short-term").param("sessionId", "s-9"))
            .andExpect(status().isOk());

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(agent).getAgentState(Mockito.isNull(), sessionCaptor.capture());
        assertThat(sessionCaptor.getValue()).isEqualTo("s-9");
    }
}
