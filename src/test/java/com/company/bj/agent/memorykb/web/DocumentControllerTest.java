package com.company.bj.agent.memorykb.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.bj.agent.memorykb.knowledge.IngestionService;
import com.company.bj.agent.memorykb.knowledge.KnowledgeCatalogService;
import com.company.bj.agent.memorykb.knowledge.model.IngestionResult;
import com.company.bj.agent.memorykb.web.dto.DocumentSummary;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 文档管理接口的测试。
 *
 * <p>重点钉住三处最容易出错的契约：
 * <ul>
 *   <li><b>文档标识就是原始文件名</b>：切片 id 由此派生，
 *       一旦被规范化，Task 6 的评测集就会整体失配；</li>
 *   <li><b>不支持的格式必须在入口被拒绝</b>：否则会为一份必然失败的请求
 *       白白调用嵌入模型，失败信息里还混进模型服务的噪音；</li>
 *   <li><b>空文件被拒绝</b>：空文档会解析出零个切片，
 *       而摄取管线对零切片是拒绝入库的。</li>
 * </ul>
 */
@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestionService ingestionService;

    @MockBean
    private KnowledgeCatalogService catalogService;

    @Test
    void 上传文本文件返回切片数量() throws Exception {
        when(ingestionService.ingest(eq("手册.txt"), eq("手册.txt"), any(), any()))
            .thenReturn(new IngestionResult("手册.txt", 12, 12, List.of("手册.txt#0")));

        MockMultipartFile file = new MockMultipartFile("file", "手册.txt",
            "text/plain", "边检业务手册内容".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value("手册.txt"))
            .andExpect(jsonPath("$.chunkCount").value(12))
            .andExpect(jsonPath("$.ingestedCount").value(12));
    }

    @Test
    void 上传文件把原始文件名原样作为文档标识() throws Exception {
        when(ingestionService.ingest(anyString(), anyString(), any(), any()))
            .thenAnswer(invocation -> {
                String documentId = invocation.getArgument(0);
                return new IngestionResult(documentId, 1, 1, List.of(documentId + "#0"));
            });

        MockMultipartFile file = new MockMultipartFile("file", "边检业务手册 (V2).txt",
            "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value("边检业务手册 (V2).txt"));

        // 文档标识与文件名都必须原样传入摄取服务 —— 切片 id 由它派生。
        verify(ingestionService).ingest(eq("边检业务手册 (V2).txt"), eq("边检业务手册 (V2).txt"),
            any(), any());
    }

    @Test
    void 上传不支持的文件类型返回400且不调用摄取服务() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "手册.pdf",
            "application/pdf", "内容".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INVALID_REQUEST))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(".txt")));

        // 格式不支持的请求不该走到摄取服务：否则会白白消耗一次嵌入调用。
        verify(ingestionService, never()).ingest(anyString(), anyString(), any(), any());
    }

    @Test
    void 上传空文件返回400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "空.txt",
            "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/documents").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(GlobalExceptionHandler.ERROR_INVALID_REQUEST));
    }

    @Test
    void 上传携带知识库标识时写入附加元数据() throws Exception {
        when(ingestionService.ingest(anyString(), anyString(), any(), any()))
            .thenReturn(new IngestionResult("手册.txt", 1, 1, List.of("手册.txt#0")));

        MockMultipartFile file = new MockMultipartFile("file", "手册.txt",
            "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents").file(file).param("kb", "k1"))
            .andExpect(status().isOk());

        verify(ingestionService).ingest(eq("手册.txt"), eq("手册.txt"), any(),
            eq(Map.of("kb", "k1")));
    }

    @Test
    void 上传不带知识库标识时不写入空的kb字段() throws Exception {
        when(ingestionService.ingest(anyString(), anyString(), any(), any()))
            .thenReturn(new IngestionResult("手册.txt", 1, 1, List.of("手册.txt#0")));

        MockMultipartFile file = new MockMultipartFile("file", "手册.txt",
            "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents").file(file))
            .andExpect(status().isOk());

        // 空字符串的 kb 会让「按 kb 过滤」的语义变得难以解释，因此不写入。
        verify(ingestionService).ingest(eq("手册.txt"), eq("手册.txt"), any(), eq(Map.of()));
    }

    @Test
    void 列出文档返回文档摘要() throws Exception {
        when(catalogService.listDocuments()).thenReturn(List.of(
            new DocumentSummary("手册.txt", "手册.txt", 12),
            new DocumentSummary("巡检.xlsx", "巡检.xlsx", 8)));

        mockMvc.perform(get("/api/documents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$[0].documentId").value("手册.txt"))
            .andExpect(jsonPath("$[0].chunkCount").value(12))
            .andExpect(jsonPath("$[1].fileName").value("巡检.xlsx"));
    }

    @Test
    void 删除文档返回删除结果() throws Exception {
        when(catalogService.deleteDocument("手册.txt")).thenReturn(12);

        mockMvc.perform(delete("/api/documents/{documentId}", "手册.txt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value("手册.txt"))
            .andExpect(jsonPath("$.deleted").value(true))
            .andExpect(jsonPath("$.chunkCount").value(12));
    }

    @Test
    void 删除不存在的文档返回deleted为false() throws Exception {
        when(catalogService.deleteDocument("缺失.txt")).thenReturn(0);

        mockMvc.perform(delete("/api/documents/{documentId}", "缺失.txt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(false))
            .andExpect(jsonPath("$.chunkCount").value(0));
    }
}
