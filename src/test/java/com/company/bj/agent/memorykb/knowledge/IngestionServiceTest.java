package com.company.bj.agent.memorykb.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.knowledge.chunk.RecursiveCharacterChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.SourceTypeRoutingChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.TableRowChunker;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingException;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.company.bj.agent.memorykb.knowledge.model.IngestionResult;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParserRegistry;
import com.company.bj.agent.memorykb.knowledge.parse.ExcelDocumentParser;
import com.company.bj.agent.memorykb.knowledge.parse.TxtDocumentParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link IngestionService} 摄取管线的端到端行为（解析 → 切片 → 嵌入 → 入库）。
 *
 * <p>重点钉住三处语义：
 * <ul>
 *   <li>入库元数据由摄取服务<b>重新组装</b>，解析期字段与调用方自定义字段都要带上；</li>
 *   <li>重复摄取同一 {@code documentId} 走 {@code /upsert} 而非 {@code /add}，记录数不增长；</li>
 *   <li>嵌入失败时不得发出任何入库请求，避免留下无向量的"已入库"记录。</li>
 * </ul>
 */
class IngestionServiceTest {

    /** 集合 id，用于模拟 Chroma 建集合响应。 */
    private static final String COLLECTION_ID = "c1e3a1f0-1111-2222-3333-444455556666";

    /** 单片字符上限，与全局约束一致。 */
    private static final int MAX_CHUNK_SIZE = 600;

    /** 相邻切片重叠字符数，与全局约束一致。 */
    private static final int OVERLAP = 90;

    /** 嵌入向量维度。 */
    private static final int VECTOR_DIMENSION = 1024;

    /** 与配置声明维度不符的错误维度，用于验证入库前的维度闸门。 */
    private static final int WRONG_DIMENSION = 768;

    /** 测试用凭据，仅为占位符，不是真实 api key。 */
    private static final String TEST_API_KEY = "test-key-placeholder";

    private MockWebServer server;
    private IngestionService ingestionService;
    private ObjectMapper mapper;

    @BeforeEach
    void 启动Mock服务器() throws IOException {
        server = new MockWebServer();
        server.start();
        mapper = new ObjectMapper();
        ingestionService = new IngestionService(
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser())),
            new SourceTypeRoutingChunker(
                new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP),
                new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP)),
            new StubEmbeddingService(),
            new ChromaClient(chromaPropertiesFor(server), HttpClient.newHttpClient(), mapper),
            chromaPropertiesFor(server),
            embeddingProperties());
    }

    @AfterEach
    void 关闭Mock服务器() throws IOException {
        server.shutdown();
    }

    /**
     * 构造嵌入模型配置，维度与 {@link #VECTOR_DIMENSION} 一致。
     *
     * @return 嵌入模型配置
     */
    private EmbeddingProperties embeddingProperties() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setDimensions(VECTOR_DIMENSION);
        return props;
    }

    private ChromaProperties chromaPropertiesFor(MockWebServer mockServer) {
        ChromaProperties props = new ChromaProperties();
        props.setBaseUrl("http://localhost:" + mockServer.getPort());
        props.setTenant("default_tenant");
        props.setDatabase("default_database");
        props.setKnowledgeCollection("kb_main");
        return props;
    }

    /**
     * 入队一次建集合响应与一次入库响应。
     *
     * @param chunkCount 本次入库的切片数
     */
    private void enqueueChromaResponses(int chunkCount) {
        server.enqueue(new MockResponse()
            .setBody("{\"id\":\"" + COLLECTION_ID + "\",\"name\":\"kb_main\"}")
            .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));
    }

    /**
     * 读取最后一次入库请求的请求体。
     *
     * @return 入库请求体
     * @throws Exception 请求读取失败时抛出
     */
    private JsonNode takeUpsertBody() throws Exception {
        RecordedRequest request = server.takeRequest();
        // 第一个请求是建集合，第二个才是入库。
        RecordedRequest upsertRequest = server.takeRequest();
        assertThat(upsertRequest.getPath()).endsWith("/upsert");
        return mapper.readTree(upsertRequest.getBody().readUtf8());
    }

    @Test
    void 摄取入库记录数等于切片数() throws Exception {
        enqueueChromaResponses(2);

        String content = "第一章 总则\n\n第一条 本规定适用于边检业务。\n\n第二条 本规定自发布之日起施行。\n\n";
        IngestionResult result = ingestionService.ingest(
            "手册.txt", "手册.txt", content.getBytes(StandardCharsets.UTF_8), Map.of());

        assertThat(result.documentId()).isEqualTo("手册.txt");
        assertThat(result.chunkCount()).isGreaterThan(0);
        assertThat(result.ingestedCount()).isEqualTo(result.chunkCount());
        assertThat(result.chunkIds()).hasSize(result.chunkCount());
    }

    @Test
    void 摄取写入的元数据包含来源与位置字段() throws Exception {
        enqueueChromaResponses(1);

        String content = "边检业务手册正文，包含边检流程说明。";
        ingestionService.ingest("手册.txt", "手册.txt", content.getBytes(StandardCharsets.UTF_8), Map.of());

        JsonNode body = takeUpsertBody();
        JsonNode metadata = body.get("metadatas").get(0);
        assertThat(metadata.get("document_id").asText()).isEqualTo("手册.txt");
        assertThat(metadata.get("file_name").asText()).isEqualTo("手册.txt");
        assertThat(metadata.get("source_type").asText()).isEqualTo("txt");
        assertThat(metadata.get("sequence").asInt()).isZero();
        assertThat(metadata.get("start_offset").asInt()).isZero();
        assertThat(metadata.get("end_offset").asInt()).isEqualTo(content.length());
        // 入库时间是 ISO-8601，可被 Instant 直接解析。
        assertThat(Instant.parse(metadata.get("ingested_at").asText())).isNotNull();
    }

    @Test
    void 摄取合并调用方自定义元数据() throws Exception {
        enqueueChromaResponses(1);

        String content = "边检业务手册正文，包含边检流程说明。";
        ingestionService.ingest("手册.txt", "手册.txt", content.getBytes(StandardCharsets.UTF_8),
            Map.of("业务线", "边检", "密级", "内部"));

        JsonNode metadata = takeUpsertBody().get("metadatas").get(0);
        assertThat(metadata.get("业务线").asText()).isEqualTo("边检");
        assertThat(metadata.get("密级").asText()).isEqualTo("内部");
    }

    @Test
    void 自定义元数据覆盖同名字段() throws Exception {
        enqueueChromaResponses(1);

        String content = "边检业务手册正文，包含边检流程说明。";
        ingestionService.ingest("手册.txt", "手册.txt", content.getBytes(StandardCharsets.UTF_8),
            Map.of("file_name", "覆盖后的名字.txt"));

        JsonNode metadata = takeUpsertBody().get("metadatas").get(0);
        assertThat(metadata.get("file_name").asText()).isEqualTo("覆盖后的名字.txt");
        // 基础字段不会因为覆盖而重复出现。
        assertThat(metadata.get("document_id").asText()).isEqualTo("手册.txt");
    }

    @Test
    void 解析期元数据被带入入库元数据() throws Exception {
        enqueueChromaResponses(1);

        String content = "# 总则\n\n本规定适用于边检业务。\n";
        ingestionService.ingest("手册.txt", "手册.txt", content.getBytes(StandardCharsets.UTF_8), Map.of());

        JsonNode metadata = takeUpsertBody().get("metadatas").get(0);
        assertThat(metadata.get("section_title").asText()).isEqualTo("总则");
    }

    @Test
    void 重复摄取走upsert而不是add() throws Exception {
        enqueueChromaResponses(1);
        enqueueChromaResponses(1);

        byte[] content = "边检业务手册正文，包含边检流程说明。".getBytes(StandardCharsets.UTF_8);
        ingestionService.ingest("手册.txt", "手册.txt", content, Map.of());
        ingestionService.ingest("手册.txt", "手册.txt", content, Map.of());

        // 4 个请求：建集合 + 入库，建集合 + 入库。
        assertThat(server.getRequestCount()).isEqualTo(4);

        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(server.takeRequest());
        }
        assertThat(requests.get(1).getPath()).endsWith("/upsert");
        assertThat(requests.get(3).getPath()).endsWith("/upsert");
        // 两次入库使用同一批切片 id，Chroma 的 upsert 语义是"覆盖已有 id"，
        // 因此重复摄取不会让集合记录数增长。
        JsonNode firstIds = mapper.readTree(requests.get(1).getBody().readUtf8()).get("ids");
        JsonNode secondIds = mapper.readTree(requests.get(3).getBody().readUtf8()).get("ids");
        assertThat(firstIds).isEqualTo(secondIds);
        assertThat(firstIds.get(0).asText()).isEqualTo("手册.txt#0");
    }

    @Test
    void 嵌入失败时抛EmbeddingException且不发出入库请求() {
        // 嵌入服务直接抛异常，摄取必须整体失败，且不得向 Chroma 发出任何请求
        // —— 建集合与入库都属于"对向量库的写入"，失败时一次都不该发生。
        EmbeddingService failing = texts -> {
            throw new EmbeddingException("embedding gateway is down");
        };
        IngestionService service = serviceWith(failing);

        assertThatThrownBy(() -> service.ingest("手册.txt", "手册.txt",
            "边检业务手册正文。".getBytes(StandardCharsets.UTF_8), Map.of()))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("down");

        // 没有任何 Chroma 请求：既没有建集合，也没有入库。
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 向量个数与切片数不符时抛EmbeddingException且不发出入库请求() {
        // 嵌入服务只返回 1 个向量，而文档切出了多个切片 —— 数量对不上就不能入库，
        // 否则会出现"部分切片没有向量"的畸形记录。
        String content = longDocument();
        EmbeddingService shortVectors = texts -> List.of(new double[VECTOR_DIMENSION]);
        IngestionService service = serviceWith(shortVectors);

        assertThatThrownBy(() -> service.ingest("手册.txt", "手册.txt",
            content.getBytes(StandardCharsets.UTF_8), Map.of()))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("vectors");

        // 没有任何 Chroma 请求：既没有建集合，也没有入库。
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 向量维度不一致时抛EmbeddingException且不发出入库请求() {
        // 同一批切片里出现维度不同的向量，说明嵌入侧出了问题，必须拒绝入库。
        String content = longDocument();
        EmbeddingService mixedDimensions = texts -> {
            List<double[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new double[i == 0 ? VECTOR_DIMENSION : 8]);
            }
            return vectors;
        };
        IngestionService service = serviceWith(mixedDimensions);

        assertThatThrownBy(() -> service.ingest("手册.txt", "手册.txt",
            content.getBytes(StandardCharsets.UTF_8), Map.of()))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("dimension");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 向量维度与配置不符时抛EmbeddingException且不发出入库请求() {
        // 嵌入服务返回 768 维向量，而配置声明的期望维度是 1024。
        // Chroma 集合是维度绑定的，这种数据写进去要到检索时才以服务端维度错误暴露，
        // 因此必须在入库前就拒绝。
        String content = longDocument();
        EmbeddingService wrongDimension = texts -> {
            List<double[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new double[WRONG_DIMENSION]);
            }
            return vectors;
        };
        IngestionService service = serviceWith(wrongDimension);

        assertThatThrownBy(() -> service.ingest("手册.txt", "手册.txt",
            content.getBytes(StandardCharsets.UTF_8), Map.of()))
            .isInstanceOf(EmbeddingException.class)
            // 消息必须同时点明期望维度与实际维度，否则无法定位是哪一侧配错了。
            .hasMessageContaining(String.valueOf(VECTOR_DIMENSION))
            .hasMessageContaining(String.valueOf(WRONG_DIMENSION));

        // 没有任何 Chroma 请求：既没有建集合，也没有入库。
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 不支持的文件类型被拒绝() {
        assertThatThrownBy(() -> ingestionService.ingest("手册.pdf", "手册.pdf",
            "content".getBytes(StandardCharsets.UTF_8), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("手册.pdf");
    }

    @Test
    void 空文档被拒绝且不发出入库请求() {
        assertThatThrownBy(() -> ingestionService.ingest("空.txt", "空.txt",
            "".getBytes(StandardCharsets.UTF_8), Map.of()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 集合名非法时在发起请求前被拒绝() {
        ChromaProperties props = chromaPropertiesFor(server);
        props.setKnowledgeCollection("_kb");
        IngestionService service = new IngestionService(
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser())),
            new SourceTypeRoutingChunker(
                new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP),
                new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP)),
            new StubEmbeddingService(),
            new ChromaClient(props, HttpClient.newHttpClient(), mapper),
            props,
            embeddingProperties());

        assertThatThrownBy(() -> service.ingest("手册.txt", "手册.txt",
            "边检业务手册正文。".getBytes(StandardCharsets.UTF_8), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("collection name");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 切片id由文档名与序号派生() throws Exception {
        enqueueChromaResponses(1);

        ingestionService.ingest("手册.txt", "手册.txt",
            "边检业务手册正文。".getBytes(StandardCharsets.UTF_8), Map.of());

        JsonNode body = takeUpsertBody();
        assertThat(body.get("ids").get(0).asText()).isEqualTo("手册.txt#0");
    }

    /**
     * 构造一份足够长的文档，确保切出多个切片。
     *
     * <p>单片上限是 {@value #MAX_CHUNK_SIZE} 字符，因此这里的段落总长远大于一个切片，
     * 用于验证"多个切片"场景下的向量数量与维度校验。
     *
     * @return 长文档正文
     */
    private String longDocument() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            builder.append("第").append(i + 1)
                .append("条 本条规定用于说明边检业务办理流程的若干具体事项与要求，包括证件查验、"
                    + "人员核查与物品检查等环节的操作规范。\n\n");
        }
        return builder.toString();
    }

    /**
     * 用指定的嵌入服务装配一个摄取服务，其余依赖与默认实例一致。
     *
     * @param embeddings 嵌入服务
     * @return 摄取服务
     */
    private IngestionService serviceWith(EmbeddingService embeddings) {
        return new IngestionService(
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser())),
            new SourceTypeRoutingChunker(
                new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP),
                new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP)),
            embeddings,
            new ChromaClient(chromaPropertiesFor(server), HttpClient.newHttpClient(), mapper),
            chromaPropertiesFor(server),
            embeddingProperties());
    }

    /**
     * 测试替身嵌入服务，按固定维度返回向量。
     *
     * <p>之所以用替身而非 MockWebServer：摄取管线的测试关注的是「元数据组装 + 幂等入库」，
     * 嵌入协议本身由 {@code EmbeddingServiceTest} 单独覆盖，两者职责不重叠。
     */
    private static final class StubEmbeddingService implements EmbeddingService {

        @Override
        public List<double[]> embed(List<String> texts) {
            List<double[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new double[VECTOR_DIMENSION]);
            }
            return vectors;
        }
    }
}
