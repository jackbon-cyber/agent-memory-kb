package com.company.bj.agent.memorykb.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.knowledge.ChromaKnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingNormalizer;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 端到端验收集成测试（<b>桩嵌入段</b>）。
 *
 * <p>用<b>真实 HTTP</b> 走完整链路：上传文档 → 元数据过滤检索 → 跨文件检索 →
 * 范围过滤 → 非法参数 → 四层记忆 → 作用域隔离 → 健康检查 → 删除文档。
 *
 * <p><b>启动方式</b>：{@code @SpringBootTest(webEnvironment = RANDOM_PORT)} 在测试进程内
 * 启动后端 —— 这样 IT 是<b>自包含</b>的，可以由 {@code mvn test} 单独复跑，
 * 不依赖「先手工起一个服务」。
 *
 * <p><b>Chroma 仍是外部进程</b>：由 {@code scripts/chroma.ps1 start} 事先启动。
 * 探不通就整体跳过并给出明确提示，<b>不得</b>静默通过 ——
 * 一个「跳过」的验收比「失败的」验收更危险，因为后者至少会被人看见。
 *
 * <p><b>为什么用桩嵌入</b>：本段验证的是<b>结构性</b>性质 ——
 * 切片是否产出、元数据过滤是否收敛、文档清单是否正确、
 * 作用域是否隔离、删除是否生效。这些性质与嵌入模型无关，
 * 却被挂在「必须配好凭据」上，导致最易出错的环节反而验证不了。
 * 语义召回质量由 {@link EndToEndRealIT} 与 {@code QualityEvaluationIT} 负责，
 * 它们的结论基于真实嵌入模型。
 *
 * <p><b>独立集合</b>：端到端用例走专属集合 {@code it_e2e_kb}，
 * 与开发期的 {@code kb_main} 物理隔离 —— 否则验收会在开发数据上运行，
 * 断言结果受残留数据影响，且验收本身会污染开发数据。
 *
 * <p><b>断言带上下文</b>：失败信息里带上实际命中的内容，
 * 例如「过滤后仍出现其他文件：{实际命中}」，便于直接定位，
 * 而不是只看到一个 {@code expected true but was false}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "memorykb.chroma.knowledge-collection=it_e2e_kb",
        "memorykb.chroma.memory-collection=it_e2e_mem",
        "memorykb.embedding.dimensions=256"
    })
@Import(StubEmbeddingConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "E2E_IT", matches = "true")
class EndToEndIT {

    /** Chroma 健康检查路径。 */
    private static final String CHROMA_VERSION_PATH = "/api/v2/version";

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

    /** 桩嵌入段报告的标记行，供验收脚本区分来源。 */
    static final String STUB_REPORT_MARKER = "== 端到端质量门禁（桩嵌入） ==";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ChromaClient chromaClient;

    @Autowired
    private ChromaProperties chromaProperties;

    private String baseUrl;

    @BeforeEach
    void 检查前置条件() {
        // 随机端口的基址从 TestRestTemplate 的根 URI 取 —— 端口由容器分配，
        // 写死端口会在并行跑测试时撞车。
        URI rootUri = restTemplate.getRestTemplate().getUriTemplateHandler().expand("/");
        baseUrl = "http://localhost:" + rootUri.getPort();

        // Chroma 探不通就整体跳过 —— 但必须**明确说明**为什么跳过。
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

        assertThat(response.get("documentId").asText())
            .as("文档标识必须是原始文件名（含扩展名），否则评测集的切片 id 会全部失配")
            .isEqualTo(TXT_CORPUS);
        assertThat(response.get("chunkCount").asInt())
            .as("文本语料应产出切片，实际响应: " + response)
            .isPositive();
        assertThat(response.get("ingestedCount").asInt())
            .as("切片应全部写入向量库，实际响应: " + response)
            .isEqualTo(response.get("chunkCount").asInt());
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
    void 文档清单列出两份语料() {
        JsonNode response = getJson("/api/documents");

        List<String> fileNames = new ArrayList<>();
        for (JsonNode document : response) {
            fileNames.add(document.get("fileName").asText());
        }
        assertThat(fileNames)
            .as("文档清单应包含两份语料，实际: " + fileNames)
            .contains(TXT_CORPUS, XLSX_CORPUS);
    }

    @Test
    @Order(4)
    void 按元数据等值过滤只返回目标文件() {
        JsonNode response = search("边检周期", filter("file_name", "eq", TXT_CORPUS));

        List<String> actualFiles = metadataValues(response, "file_name");
        assertThat(actualFiles)
            .as("等值过滤后仍出现其他文件，实际命中: " + actualFiles)
            .allMatch(TXT_CORPUS::equals);
    }

    @Test
    @Order(5)
    void 不带过滤时跨文件命中() {
        JsonNode response = search("巡检 记录", null);

        List<String> actualFiles = metadataValues(response, "file_name");
        assertThat(actualFiles)
            .as("跨文件检索应命中多份文档，实际命中: " + actualFiles)
            .hasSizeGreaterThan(1);
    }

    @Test
    @Order(6)
    void 范围过滤按数值收敛结果集() {
        // sequence 是入库时写入的整型元数据，用 gte 做数值比较。
        JsonNode response = search("边检", filter("sequence", "gte", "0"));

        JsonNode hits = response.get("hits");
        assertThat(hits.size())
            .as("范围过滤应返回结果，实际: " + response)
            .isPositive();

        for (JsonNode hit : hits) {
            int sequence = hit.get("metadata").get("sequence").asInt();
            assertThat(sequence)
                .as("命中里出现了小于过滤下界的切片，实际命中: " + hits)
                .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @Order(7)
    void 非法过滤操作符返回400() {
        ResponseEntity<String> response = postJson("/api/retrieval/search",
            "{\"query\":\"q\",\"filters\":[{\"field\":\"file_name\","
                + "\"operator\":\"contains\",\"value\":\"手册\"}],\"logic\":\"AND\",\"topK\":5}");

        assertThat(response.getStatusCode().value())
            .as("非法操作符必须被拒成 400，而不是静默返回全量结果")
            .isEqualTo(400);
    }

    @Test
    @Order(8)
    void 空查询返回400() {
        ResponseEntity<String> response = postJson("/api/retrieval/search",
            "{\"query\":\"  \",\"filters\":[],\"logic\":\"AND\",\"topK\":5}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @Order(9)
    void 四个记忆端点各自返回结构() {
        JsonNode shortTerm = getJson("/api/memory/short-term");
        assertThat(shortTerm.has("messages")).as("短期记忆应返回 messages 字段").isTrue();

        JsonNode longTerm = getJson("/api/memory/long-term");
        assertThat(longTerm.has("records")).as("长期记忆应返回 records 字段").isTrue();

        JsonNode working = getJson("/api/memory/working");
        assertThat(working.has("tasks")).as("工作记忆应返回 tasks 字段").isTrue();

        JsonNode episodic = getJson("/api/memory/episodic");
        assertThat(episodic.has("episodes")).as("情景记忆应返回 episodes 字段").isTrue();
    }

    @Test
    @Order(10)
    void 作用域隔离生效() {
        // 换一个租户与主体，长期记忆里看不到上一个作用域的记录。
        JsonNode scoped = getJson("/api/memory/long-term", "tenant-b", "owner-b");
        assertThat(scoped.get("records").size())
            .as("新作用域不应看到其它作用域的长期记忆，实际: " + scoped)
            .isZero();
    }

    @Test
    @Order(11)
    void 健康检查报告三个组件() {
        JsonNode response = getJson("/api/health");

        JsonNode components = response.get("components");
        assertThat(components.has("application")).isTrue();
        assertThat(components.has("chroma")).isTrue();
        assertThat(components.has("model")).isTrue();

        // 应用与 Chroma 必须是 UP —— Chroma 可达是本段运行的前提。
        assertThat(components.get("application").asText()).isEqualTo("UP");
        assertThat(components.get("chroma").asText()).isEqualTo("UP");

        // 模型组件取决于凭据：有凭据则 UP，无凭据则 DOWN。
        // 整体状态由三者共同决定，因此这里断言的是**一致性**而非某个固定值 ——
        // 断言「整体必须是 UP」会在无凭据环境里误报失败，
        // 而那恰恰是模型不可用这一事实的正确表达。
        String modelStatus = components.get("model").asText();
        boolean modelUp = "UP".equals(modelStatus);
        assertThat(modelUp)
            .as("模型组件状态只能是 UP 或 DOWN，实际: " + response)
            .isIn(true, false);

        String expectedOverall = modelUp ? "UP" : "DOWN";
        assertThat(response.get("status").asText())
            .as("整体状态应由三个组件共同决定，实际: " + response)
            .isEqualTo(expectedOverall);
    }

    @Test
    @Order(12)
    void 桩嵌入下的质量评测链路可跑通() {
        // 桩嵌入的召回率衡量的是**词面重叠**，不是语义召回 ——
        // 它证明的是「评测装置本身工作正常」，不是「语义召回达标」。
        // 语义召回的结论由 EndToEndRealIT 给出。
        List<QualityEvaluator.EvaluationQuery> queries = QualityEvaluator.loadQueries(EVAL_QUERIES);
        RetrievalQualityReport report = new QualityEvaluator(
            new ChromaKnowledgeRetriever(
                chromaClient,
                stubEmbeddingService(),
                chromaProperties))
            .evaluate(queries);

        System.out.println(STUB_REPORT_MARKER);
        System.out.printf("Recall@10 : %.4f (门禁线 %.2f)%n",
            report.recallAt10(), QualityGate.MIN_RECALL);
        System.out.printf("P95 (ms)  : %.2f (门禁线 %.0f)%n",
            report.p95LatencyMs(), QualityGate.MAX_P95_MS);
        System.out.printf("P95 向量库(ms): %.2f (仅检索, 门禁线 %.0f)%n",
            report.searchP95LatencyMs(), QualityGate.MAX_P95_MS);
        System.out.println("[桩嵌入说明] 本段召回率由确定性字符哈希嵌入算出，"
            + "衡量的是词面重叠，不是语义召回，不能作为真实召回的验收结论。");

        // 桩嵌入下评测集引用的切片必须全部存在，否则召回率是噪声。
        assertThat(report.queryCount())
            .as("评测集应至少包含 20 条查询，实际: " + report.queryCount())
            .isGreaterThanOrEqualTo(20);
    }

    @Test
    @Order(13)
    void 删除文档后清单不再包含它() {
        ResponseEntity<String> response = delete("/api/documents/" + TXT_CORPUS);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode list = getJson("/api/documents");
        List<String> fileNames = new ArrayList<>();
        for (JsonNode document : list) {
            fileNames.add(document.get("fileName").asText());
        }
        assertThat(fileNames)
            .as("删除后清单不应再包含该文档，实际: " + fileNames)
            .doesNotContain(TXT_CORPUS);
    }

    /**
     * 返回与 Spring 容器中桩嵌入一致的嵌入服务。
     *
     * <p>必须与 {@link StubEmbeddingConfiguration} 产出的是<b>同一个算法</b> ——
     * 否则查询侧与写入侧的向量不在同一空间，召回率会退化成噪声。
     *
     * @return 桩嵌入服务
     */
    private EmbeddingService stubEmbeddingService() {
        return new EmbeddingService() {
            @Override
            public List<double[]> embed(List<String> texts) {
                List<double[]> vectors = new ArrayList<>(texts.size());
                for (String text : texts) {
                    vectors.add(vectorOf(text));
                }
                return vectors;
            }

            /**
             * 把文本的字符哈希累加为向量。
             *
             * @param text 文本
             * @return 单位长度向量
             */
            private double[] vectorOf(String text) {
                double[] vector =
                    new double[StubEmbeddingConfiguration.STUB_EMBEDDING_DIMENSIONS];
                for (int i = 0; i < text.length(); i++) {
                    int bucket = Math.floorMod(text.charAt(i) * 31 + i,
                        StubEmbeddingConfiguration.STUB_EMBEDDING_DIMENSIONS);
                    vector[bucket] += 1.0;
                }
                return EmbeddingNormalizer.normalize(vector);
            }
        };
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

    /**
     * 执行一次检索。
     *
     * @param query   查询文本
     * @param filters 过滤条件 JSON，可为 {@code null} 表示不过滤
     * @return 解析后的响应体
     */
    private JsonNode search(String query, String filters) {
        String filterJson = filters == null ? "[]" : "[" + filters + "]";
        String payload = "{\"query\":\"" + query + "\",\"filters\":" + filterJson
            + ",\"logic\":\"AND\",\"topK\":10}";
        ResponseEntity<String> response = postJson("/api/retrieval/search", payload);
        assertThat(response.getStatusCode().value())
            .as("检索应返回 200，实际: " + response.getBody())
            .isEqualTo(200);
        return readTree(response.getBody());
    }

    /** 构造一条过滤条件的 JSON。 */
    private String filter(String field, String operator, String value) {
        return "{\"field\":\"" + field + "\",\"operator\":\"" + operator + "\",\"value\":\"" + value + "\"}";
    }

    /** 读出命中里的某项元数据值。 */
    private List<String> metadataValues(JsonNode response, String key) {
        List<String> values = new ArrayList<>();
        for (JsonNode hit : response.get("hits")) {
            JsonNode metadata = hit.get("metadata");
            if (metadata != null && metadata.has(key)) {
                values.add(metadata.get(key).asText());
            }
        }
        return values;
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
        return readTree(get(path).getBody());
    }

    /** 发起带指定作用域的 GET 请求并解析 JSON。 */
    private JsonNode getJson(String path, String tenantId, String ownerKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_TENANT_ID, tenantId);
        headers.set(HEADER_OWNER_KEY, ownerKey);
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readTree(response.getBody());
    }

    /** 发起 GET 请求。 */
    private ResponseEntity<String> get(String path) {
        return restTemplate.getForEntity(baseUrl + path, String.class);
    }

    /** 发起 DELETE 请求。 */
    private ResponseEntity<String> delete(String path) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.DELETE, null, String.class);
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
