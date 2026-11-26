package com.company.bj.agent.memorykb.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.knowledge.ChromaKnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.embed.OpenAiCompatibleEmbeddingService;
import com.company.bj.agent.memorykb.knowledge.quality.QualityEvaluator;
import com.company.bj.agent.memorykb.knowledge.quality.QualityGate;
import com.company.bj.agent.memorykb.knowledge.quality.RetrievalQualityReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 端到端验收集成测试（<b>真实模型段</b>）。
 *
 * <p>与 {@link EndToEndIT}（桩嵌入段）互补：那一段验证结构性性质，
 * 本段验证<b>依赖真实模型</b>的两件事：
 * <ul>
 *   <li><b>智能体问答</b> —— 对话模型能基于知识库给出非空回答；</li>
 *   <li><b>质量门禁</b> —— 真实嵌入下的 Recall@10 与 P95 达标。</li>
 * </ul>
 *
 * <p><b>凭据缺失即跳过，且明确说明</b>：本段需要 {@code DASHSCOPE_API_KEY}。
 * 没有凭据时整段被显式跳过（JUnit assumption），
 * <b>绝不冒充"通过"</b> —— 桩嵌入的词面重叠召回率不是语义召回的验收依据。
 *
 * <p><b>独立集合</b>：本段走专属集合 {@code it_e2e_real_kb}（1024 维），
 * 与桩段（256 维）物理隔离 —— Chroma 集合维度在首次写入时绑定且不可变，
 * 两段共用一个集合时先跑的那段会把维度钉死，另一段必然撞维度冲突，
 * 且冲突与否取决于测试执行顺序。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "memorykb.chroma.knowledge-collection=it_e2e_real_kb",
        "memorykb.chroma.memory-collection=it_e2e_real_mem"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "E2E_IT", matches = "true")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class EndToEndRealIT {

    /** Chroma 健康检查路径。 */
    private static final String CHROMA_VERSION_PATH = "/api/v2/version";

    /** 聊天与嵌入模型的凭据环境变量名。 */
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    /** 评测语料目录，相对模块目录解析。 */
    private static final Path CORPUS_DIR = Path.of("eval/corpus");

    /** 评测集文件路径，相对模块目录解析。 */
    private static final Path EVAL_QUERIES = Path.of("eval/queries.jsonl");

    /** 纯文本语料文件名。 */
    private static final String TXT_CORPUS = "边检业务手册.txt";

    /** Excel 语料文件名。 */
    private static final String XLSX_CORPUS = "设备巡检记录.xlsx";

    /** 租户标识请求头。 */
    private static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** 主体标识请求头。 */
    private static final String HEADER_OWNER_KEY = "X-Owner-Key";

    /** 真实嵌入段报告的标记行，供验收脚本区分来源。 */
    static final String REAL_REPORT_MARKER = "== 端到端质量门禁（真实嵌入） ==";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ChromaClient chromaClient;

    @Autowired
    private ChromaProperties chromaProperties;

    @Autowired
    private EmbeddingProperties embeddingProperties;

    private String baseUrl;

    @BeforeEach
    void 检查前置条件() {
        URI rootUri = restTemplate.getRestTemplate().getUriTemplateHandler().expand("/");
        baseUrl = "http://localhost:" + rootUri.getPort();

        assumeTrue(chromaReachable(),
            "Chroma 服务不可达（" + chromaProperties.getBaseUrl() + "），"
                + "端到端验收跳过。请先执行 scripts/chroma.ps1 start。");
    }

    /** 判断 Chroma 是否可达。 */
    private boolean chromaReachable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chromaProperties.getBaseUrl() + CHROMA_VERSION_PATH))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    @Order(1)
    void 上传文本语料并产出切片() throws IOException {
        byte[] bytes = Files.readAllBytes(CORPUS_DIR.resolve(TXT_CORPUS));
        JsonNode response = upload(bytes, TXT_CORPUS);

        assertThat(response.get("documentId").asText()).isEqualTo(TXT_CORPUS);
        assertThat(response.get("chunkCount").asInt())
            .as("文本语料应产出切片，实际响应: " + response)
            .isPositive();
    }

    @Test
    @Order(2)
    void 上传Excel语料并产出切片() throws IOException {
        byte[] bytes = Files.readAllBytes(CORPUS_DIR.resolve(XLSX_CORPUS));
        JsonNode response = upload(bytes, XLSX_CORPUS);

        assertThat(response.get("documentId").asText()).isEqualTo(XLSX_CORPUS);
        assertThat(response.get("chunkCount").asInt())
            .as("Excel 语料应产出切片，实际响应: " + response)
            .isPositive();
    }

    @Test
    @Order(3)
    void 智能体问答返回非空回答() throws IOException {
        ResponseEntity<String> response = postJson("/api/chat",
            "{\"question\":\"边检周期是怎样的？\"}");

        assertThat(response.getStatusCode().value())
            .as("问答接口应返回 200，实际: " + response.getBody())
            .isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("answer").asText())
            .as("回答不应为空，实际: " + response.getBody())
            .isNotBlank();
    }

    @Test
    @Order(4)
    void 问答后短期记忆记录了本轮对话() {
        // 问答与记忆查看读的是同一份会话状态 ——
        // 这一点必须验证，否则控制台上的「短期记忆」可能永远为空。
        JsonNode response = getJson("/api/memory/short-term");

        assertThat(response.get("messages").size())
            .as("问答后短期记忆应记录消息，实际: " + response)
            .isPositive();
    }

    @Test
    @Order(5)
    void 质量门禁通过() {
        EmbeddingProperties realEmbeddingProps = new EmbeddingProperties();
        realEmbeddingProps.setApiKey(System.getenv(API_KEY_ENV));

        List<QualityEvaluator.EvaluationQuery> queries = QualityEvaluator.loadQueries(EVAL_QUERIES);
        RetrievalQualityReport report = new QualityEvaluator(
            new ChromaKnowledgeRetriever(
                chromaClient,
                new OpenAiCompatibleEmbeddingService(realEmbeddingProps, HttpClient.newHttpClient(), MAPPER),
                chromaProperties))
            .evaluate(queries);

        System.out.println(REAL_REPORT_MARKER);
        System.out.printf("Recall@10 : %.4f (门禁线 %.2f)%n",
            report.recallAt10(), QualityGate.MIN_RECALL);
        System.out.printf("P95 (ms)  : %.2f (门禁线 %.0f)%n",
            report.p95LatencyMs(), QualityGate.MAX_P95_MS);
        System.out.printf("P95 向量库(ms): %.2f (仅检索, 门禁线 %.0f)%n",
            report.searchP95LatencyMs(), QualityGate.MAX_P95_MS);
        System.out.println("[真实嵌入说明] 本段召回率由真实嵌入模型算出，是本次运行的验收依据。");
        System.out.println("[延迟说明] 端到端 P95 含远程嵌入往返；门禁判的是「仅检索」那一段。");

        assertThat(QualityGate.recallPassed(report))
            .as("Recall@10 应不低于 " + QualityGate.MIN_RECALL + "，实际报告: " + report)
            .isTrue();
        assertThat(QualityGate.latencyPassed(report))
            .as("P95 应不高于 " + QualityGate.MAX_P95_MS + "ms，实际报告: " + report)
            .isTrue();
    }

    /**
     * 上传一份文档。
     *
     * @param bytes    文件字节
     * @param fileName 文件名
     * @return 解析后的响应体
     */
    private JsonNode upload(byte[] bytes, String fileName) throws IOException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        HttpHeaders headers = scopeHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl + "/api/documents", HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value())
            .as("上传 " + fileName + " 应返回 200，实际: " + response.getBody())
            .isEqualTo(200);
        return MAPPER.readTree(response.getBody());
    }

    /** 构造带作用域请求头的头集合。 */
    private HttpHeaders scopeHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_TENANT_ID, "default");
        headers.set(HEADER_OWNER_KEY, "default");
        return headers;
    }

    /** 发起 GET 请求并解析 JSON。 */
    private JsonNode getJson(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + path, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readTree(response.getBody());
    }

    /** 发起 JSON POST 请求。 */
    private ResponseEntity<String> postJson(String path, String payload) {
        HttpHeaders headers = scopeHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(baseUrl + path, HttpMethod.POST,
            new HttpEntity<>(payload, headers), String.class);
    }

    /** 解析 JSON 文本。 */
    private JsonNode readTree(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("无法解析响应: " + body, e);
        }
    }
}
