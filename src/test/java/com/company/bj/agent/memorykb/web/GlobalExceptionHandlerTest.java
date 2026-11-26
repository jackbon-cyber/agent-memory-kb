package com.company.bj.agent.memorykb.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.bj.agent.memorykb.chroma.ChromaException;
import com.company.bj.agent.memorykb.chroma.ChromaHealthIndicator;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingException;
import com.company.bj.agent.memorykb.memory.MemoryPersistenceException;
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
 * 全局异常处理的测试。
 *
 * <p><b>状态码是调用方唯一能稳定依赖的机器可读信号</b>，
 * 因此这张表必须被钉死：
 * <ul>
 *   <li>Chroma 不可用 → 503（该重试）；</li>
 *   <li>嵌入服务不可用 → 502（上游网关错误）；</li>
 *   <li>参数不合法 → 400（重试无用，必须改请求）；</li>
 *   <li>状态冲突 → 409。</li>
 * </ul>
 * 若把前两类也归成 500，调用方就无法区分「该重试」与「该报错」。
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingControllerConfiguration.class)
class GlobalExceptionHandlerTest {

    /** 提供一个专门用于触发各类异常的测试控制器。 */
    @TestConfiguration
    static class ThrowingControllerConfiguration {

        /**
         * 提供测试控制器。
         *
         * @return 测试控制器
         */
        @Bean
        ThrowingController throwingController() {
            return new ThrowingController();
        }
    }

    /** 各类异常的触发端点。 */
    @org.springframework.web.bind.annotation.RestController
    static class ThrowingController {

        /**
         * 触发 Chroma 异常。
         *
         * @return 不会正常返回
         */
        @org.springframework.web.bind.annotation.GetMapping("/boom/chroma")
        public String chroma() {
            throw new ChromaException("chroma down");
        }

        /**
         * 触发嵌入异常。
         *
         * @return 不会正常返回
         */
        @org.springframework.web.bind.annotation.GetMapping("/boom/embedding")
        public String embedding() {
            throw new EmbeddingException("embedding down");
        }

        /**
         * 触发参数异常。
         *
         * @return 不会正常返回
         */
        @org.springframework.web.bind.annotation.GetMapping("/boom/argument")
        public String argument() {
            throw new IllegalArgumentException("字段不能为空");
        }

        /**
         * 触发状态冲突异常。
         *
         * @return 不会正常返回
         */
        @org.springframework.web.bind.annotation.GetMapping("/boom/state")
        public String state() {
            throw new IllegalStateException("no active memory scope");
        }

        /**
         * 触发记忆持久化异常。
         *
         * @return 不会正常返回
         */
        @org.springframework.web.bind.annotation.GetMapping("/boom/persistence")
        public String persistence() {
            throw new MemoryPersistenceException("write failed");
        }

        /**
         * 触发未预期异常。
         *
         * @return 不会正常返回
         */
        @org.springframework.web.bind.annotation.GetMapping("/boom/unexpected")
        public String unexpected() {
            throw new RuntimeException("something odd");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChromaHealthIndicator chromaHealthIndicator;

    @MockBean
    private com.company.bj.agent.memorykb.config.AgentProperties agentProperties;

    @Test
    void Chroma异常映射为503() throws Exception {
        mockMvc.perform(get("/boom/chroma"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_CHROMA_UNAVAILABLE))
            .andExpect(jsonPath("$.path").value("/boom/chroma"));
    }

    @Test
    void 嵌入异常映射为502() throws Exception {
        mockMvc.perform(get("/boom/embedding"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_EMBEDDING_UNAVAILABLE));
    }

    @Test
    void 参数异常映射为400并保留原始说明() throws Exception {
        mockMvc.perform(get("/boom/argument"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INVALID_REQUEST))
            .andExpect(jsonPath("$.message").value("字段不能为空"));
    }

    @Test
    void 状态冲突映射为409() throws Exception {
        mockMvc.perform(get("/boom/state"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_STATE_CONFLICT))
            .andExpect(jsonPath("$.message").value("no active memory scope"));
    }

    @Test
    void 记忆持久化异常映射为500() throws Exception {
        mockMvc.perform(get("/boom/persistence"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_MEMORY_PERSISTENCE));
    }

    @Test
    void 未预期异常映射为500且不泄漏内部细节() throws Exception {
        mockMvc.perform(get("/boom/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INTERNAL))
            // 内部异常消息不外泄，否则会把实现细节暴露给调用方。
            .andExpect(jsonPath("$.message").value("服务内部错误，请联系管理员"));
    }
}
