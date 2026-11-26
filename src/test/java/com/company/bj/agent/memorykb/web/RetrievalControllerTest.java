package com.company.bj.agent.memorykb.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.bj.agent.memorykb.knowledge.FilterCondition;
import com.company.bj.agent.memorykb.knowledge.FilterLogic;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 检索接口的测试。
 *
 * <p>重点钉住两件事：
 * <ul>
 *   <li><b>过滤条件的类型转换规则</b>：{@code eq} 保持字符串、
 *       范围操作符解析为数值 —— 这是前端 Task 10 与后端之间的契约；</li>
 *   <li><b>非法操作符返回 400</b>：而不是被静默忽略后返回一份"看起来正常"的
 *       全量结果 —— 后者会让用户以为过滤生效了。</li>
 * </ul>
 */
@WebMvcTest(RetrievalController.class)
class RetrievalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeRetriever retriever;

    @Test
    void 检索返回命中与耗时() throws Exception {
        when(retriever.retrieve(anyString(), anyList(), any(), anyInt(), isNull()))
            .thenReturn(List.of(
                new RetrievedChunk("手册.txt#0", "边检周期为每周一次", 0.93,
                    Map.of("file_name", "手册.txt", "sequence", 0)),
                new RetrievedChunk("手册.txt#1", "缺陷需在 24 小时内上报", 0.81,
                    Map.of("file_name", "手册.txt", "sequence", 1))));

        mockMvc.perform(post("/api/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"边检周期\",\"filters\":[],\"logic\":\"AND\",\"topK\":10}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.hits", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.hits[0].chunkId").value("手册.txt#0"))
            .andExpect(jsonPath("$.hits[0].content").value("边检周期为每周一次"))
            .andExpect(jsonPath("$.hits[0].score").value(0.93))
            .andExpect(jsonPath("$.hits[0].metadata.file_name").value("手册.txt"))
            .andExpect(jsonPath("$.tookMs").isNumber());
    }

    @Test
    void 等值过滤的取值保持字符串() throws Exception {
        when(retriever.retrieve(anyString(), anyList(), any(), anyInt(), isNull())).thenReturn(List.of());

        mockMvc.perform(post("/api/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"filters\":[{\"field\":\"file_name\","
                    + "\"operator\":\"eq\",\"value\":\"手册.txt\"}],\"logic\":\"AND\",\"topK\":5}"))
            .andExpect(status().isOk());

        ArgumentCaptor<List<FilterCondition>> captor = ArgumentCaptor.forClass(List.class);
        verify(retriever).retrieve(eq("q"), captor.capture(), eq(FilterLogic.AND), eq(5), isNull());

        FilterCondition condition = captor.getValue().get(0);
        assertThat(condition.field()).isEqualTo("file_name");
        assertThat(condition.operator()).isEqualTo("eq");
        // 等值过滤必须保持字符串：Chroma 的等值匹配是类型敏感的，
        // 把字符串悄悄转成数值会制造静默零命中。
        assertThat(condition.value()).isInstanceOf(String.class).isEqualTo("手册.txt");
    }

    @Test
    void 范围过滤的取值被解析为数值() throws Exception {
        when(retriever.retrieve(anyString(), anyList(), any(), anyInt(), isNull())).thenReturn(List.of());

        mockMvc.perform(post("/api/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"filters\":[{\"field\":\"sequence\","
                    + "\"operator\":\"gte\",\"value\":\"3\"}],\"logic\":\"AND\",\"topK\":5}"))
            .andExpect(status().isOk());

        ArgumentCaptor<List<FilterCondition>> captor = ArgumentCaptor.forClass(List.class);
        verify(retriever).retrieve(anyString(), captor.capture(), any(), anyInt(), isNull());

        FilterCondition condition = captor.getValue().get(0);
        assertThat(condition.operator()).isEqualTo("gte");
        assertThat(condition.value()).isInstanceOf(Double.class).isEqualTo(3.0);
    }

    @Test
    void 非法操作符返回400() throws Exception {
        mockMvc.perform(post("/api/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"filters\":[{\"field\":\"file_name\","
                    + "\"operator\":\"contains\",\"value\":\"手册\"}],\"logic\":\"AND\",\"topK\":5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INVALID_REQUEST))
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("unsupported filter operator")));
    }

    @Test
    void 范围过滤的取值不是数字时返回400() throws Exception {
        mockMvc.perform(post("/api/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"filters\":[{\"field\":\"sequence\","
                    + "\"operator\":\"gte\",\"value\":\"abc\"}],\"logic\":\"AND\",\"topK\":5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INVALID_REQUEST));
    }

    @Test
    void 空查询返回400() throws Exception {
        mockMvc.perform(post("/api/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"  \",\"filters\":[],\"logic\":\"AND\",\"topK\":5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INVALID_REQUEST));
    }

    @Test
    void 相似度下限被透传给检索端口() throws Exception {
        when(retriever.retrieve(anyString(), anyList(), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(post("/api/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"filters\":[],\"logic\":\"AND\","
                    + "\"topK\":5,\"scoreThreshold\":0.3}"))
            .andExpect(status().isOk());

        verify(retriever).retrieve(eq("q"), anyList(), any(), eq(5), eq(0.3));
    }
}
