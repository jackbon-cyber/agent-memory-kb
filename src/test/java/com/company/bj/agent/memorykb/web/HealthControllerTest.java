package com.company.bj.agent.memorykb.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.bj.agent.memorykb.chroma.ChromaHealthIndicator;
import com.company.bj.agent.memorykb.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 健康检查接口的测试。
 *
 * <p>重点钉住<b>整体状态与分项状态的一致性</b>：任一组件异常时
 * 整体必须是 {@code DOWN}。若整体状态与分项脱节，
 * 编排系统就无法用一个字段判断该不该重启应用。
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChromaHealthIndicator chromaHealthIndicator;

    @MockBean
    private AgentProperties agentProperties;

    @Test
    void 全部组件正常时整体为UP() throws Exception {
        when(chromaHealthIndicator.isHealthy()).thenReturn(true);
        when(agentProperties.getApiKey()).thenReturn("sk-test");
        when(agentProperties.getBaseUrl()).thenReturn("https://example.com/compatible-mode/v1");

        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.application").value("UP"))
            .andExpect(jsonPath("$.components.chroma").value("UP"))
            .andExpect(jsonPath("$.components.model").value("UP"));
    }

    @Test
    void Chroma不可用时整体为DOWN() throws Exception {
        when(chromaHealthIndicator.isHealthy()).thenReturn(false);
        when(agentProperties.getApiKey()).thenReturn("sk-test");
        when(agentProperties.getBaseUrl()).thenReturn("https://example.com/compatible-mode/v1");

        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.components.application").value("UP"))
            .andExpect(jsonPath("$.components.chroma").value("DOWN"));
    }

    @Test
    void 模型未配置凭据时整体为DOWN() throws Exception {
        when(chromaHealthIndicator.isHealthy()).thenReturn(true);
        when(agentProperties.getApiKey()).thenReturn("");
        when(agentProperties.getBaseUrl()).thenReturn("https://example.com/compatible-mode/v1");

        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.components.model").value("DOWN"));
    }
}
