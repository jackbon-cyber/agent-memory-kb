package com.company.bj.agent.memorykb.chroma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link ChromaStore} 对 AgentScope {@code VDBStoreBase} SPI 的适配。
 *
 * <p>关注三件事：AgentScope {@link Document} 与 Chroma 记录之间的字段映射是否正确、
 * 检索结果是否回填了相似度得分、删除是否真的落到服务端。
 */
class ChromaStoreTest {

    /** 集合 id，用于断言请求路径。 */
    private static final String COLLECTION_ID = "c1e3a1f0-1111-2222-3333-444455556666";

    /** 查询向量维度。 */
    private static final int EMBEDDING_DIMENSIONS = 3;

    /** 浮点比较容差：归一化涉及除法，不能用精确相等断言。 */
    private static final org.assertj.core.data.Offset<Double> OFFSET =
        org.assertj.core.data.Offset.offset(1e-9);

    /**
     * 计算 JSON 数组形式的向量的模长。
     *
     * @param vector 向量节点
     * @return 模长
     */
    private double normOf(JsonNode vector) {
        double sumOfSquares = 0.0;
        for (JsonNode component : vector) {
            double value = component.asDouble();
            sumOfSquares += value * value;
        }
        return Math.sqrt(sumOfSquares);
    }

    private MockWebServer server;
    private ChromaClient client;
    private ObjectMapper mapper;
    private ChromaStore store;

    @BeforeEach
    void 启动Mock服务器() throws IOException {
        server = new MockWebServer();
        server.start();
        mapper = new ObjectMapper();
        client = new ChromaClient(propertiesFor(server), HttpClient.newHttpClient(), mapper);
        store = new ChromaStore(client, stubEmbeddings(), propertiesFor(server));
    }

    @AfterEach
    void 关闭Mock服务器() throws IOException {
        server.shutdown();
    }

    private ChromaProperties propertiesFor(MockWebServer mockServer) {
        ChromaProperties props = new ChromaProperties();
        props.setBaseUrl("http://localhost:" + mockServer.getPort());
        props.setTenant("default_tenant");
        props.setDatabase("default_database");
        props.setKnowledgeCollection("kb_main");
        return props;
    }

    /**
     * 返回确定性的桩嵌入服务。
     */
    private EmbeddingService stubEmbeddings() {
        return new EmbeddingService() {
            @Override
            public List<double[]> embed(List<String> texts) {
                return texts.stream()
                    .map(text -> new double[] {0.1, 0.2, 0.3})
                    .toList();
            }
        };
    }

    /**
     * 造一个集合创建响应。
     */
    private void enqueueCollection() {
        server.enqueue(new MockResponse()
            .setBody("{\"id\":\"" + COLLECTION_ID + "\",\"name\":\"kb_main\"}")
            .addHeader("Content-Type", "application/json"));
    }

    /**
     * 构造一条带向量、正文与元数据的 AgentScope 文档。
     *
     * @param text 正文
     * @return 文档
     */
    private Document document(String text) {
        DocumentMetadata metadata = DocumentMetadata.builder()
            .content(TextBlock.builder().text(text).build())
            .docId("doc-1")
            .chunkId("chunk-1")
            .addPayload("file_name", "手册.txt")
            .build();
        Document document = new Document(metadata);
        document.setEmbedding(new double[] {0.1, 0.2, 0.3});
        return document;
    }

    @Test
    void add把AgentScope文档映射为Chroma记录() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        store.add(List.of(document("第一章内容"))).block();

        server.takeRequest();
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).endsWith("/" + COLLECTION_ID + "/upsert");

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("ids")).hasSize(1);
        assertThat(body.get("documents").get(0).asText()).isEqualTo("第一章内容");
        assertThat(body.get("metadatas").get(0).get("file_name").asText()).isEqualTo("手册.txt");
        // 入库向量必须已被归一化到单位长度 —— 这是 Chroma 平方 L2 距离有意义的前提。
        assertThat(normOf(body.get("embeddings").get(0))).isCloseTo(1.0, OFFSET);
    }

    @Test
    void add在文档缺少向量时补齐嵌入向量() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        Document document = document("第二章内容");
        document.setEmbedding(null);
        store.add(List.of(document)).block();

        server.takeRequest();
        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("embeddings").get(0)).hasSize(EMBEDDING_DIMENSIONS);
        // 补齐的向量同样经过归一化。
        assertThat(normOf(body.get("embeddings").get(0))).isCloseTo(1.0, OFFSET);
    }

    @Test
    void search返回带得分的文档列表() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[\"r1\"]],\"documents\":[[\"命中内容\"]],"
                + "\"metadatas\":[[{\"file_name\":\"手册.txt\"}]],\"distances\":[[1.0]]}")
            .addHeader("Content-Type", "application/json"));

        List<Document> documents = store.search(SearchDocumentDto.builder()
            .queryEmbedding(new double[] {0.1, 0.2, 0.3})
            .limit(3)
            .build()).block();

        assertThat(documents).hasSize(1);
        Document hit = documents.get(0);
        assertThat(hit.getMetadata().getContentText()).isEqualTo("命中内容");
        assertThat(hit.getMetadata().getPayload()).containsEntry("file_name", "手册.txt");
        assertThat(hit.getScore()).isEqualTo(0.5);
    }

    @Test
    void search使用传入的查询向量与条数() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[]],\"documents\":[[]],\"metadatas\":[[]],\"distances\":[[]]}")
            .addHeader("Content-Type", "application/json"));

        store.search(SearchDocumentDto.builder()
            .queryEmbedding(new double[] {0.4, 0.5, 0.6})
            .limit(7)
            .build()).block();

        server.takeRequest();
        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        // 查询向量在发往 Chroma 前已被归一化。
        assertThat(normOf(body.get("query_embeddings").get(0))).isCloseTo(1.0, OFFSET);
        assertThat(body.get("n_results").asInt()).isEqualTo(7);
    }

    @Test
    void search按相似度下限剔除低分文档() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[\"r1\",\"r2\"]],\"documents\":[[\"内容一\",\"内容二\"]],"
                + "\"metadatas\":[[{\"file_name\":\"a.txt\"},{\"file_name\":\"b.txt\"}]],"
                + "\"distances\":[[0.0,3.0]]}")
            .addHeader("Content-Type", "application/json"));

        List<Document> documents = store.search(SearchDocumentDto.builder()
            .queryEmbedding(new double[] {0.1, 0.2, 0.3})
            .limit(2)
            .scoreThreshold(0.4)
            .build()).block();

        // 距离 3 -> 相似度 0.25，低于下限 0.4。
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getMetadata().getContentText()).isEqualTo("内容一");
    }

    @Test
    void delete按id删除记录() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        Boolean deleted = store.delete("doc-1").block();

        assertThat(deleted).isTrue();
        server.takeRequest();
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).endsWith("/" + COLLECTION_ID + "/delete");
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("ids").get(0).asText()).isEqualTo("doc-1");
    }

    @Test
    void 入库记录id取自AgentScope文档id() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        Document document = document("第三章内容");
        store.add(List.of(document)).block();

        server.takeRequest();
        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("ids").get(0).asText()).isEqualTo(document.getId());
    }

    @Test
    void 空文档列表不产生写入请求() {
        store.add(List.of()).block();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void search缺少查询向量时抛异常() {
        assertThatThrownBy(() -> store.search(SearchDocumentDto.builder().limit(3).build()).block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("query embedding");
    }

    @Test
    void delete传入空id时不产生删除请求() {
        assertThat(store.delete("  ").block()).isFalse();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 元数据携带切片标识可回溯() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[\"r1\"]],\"documents\":[[\"命中内容\"]],"
                + "\"metadatas\":[[{\"file_name\":\"手册.txt\",\"sequence\":3}]],"
                + "\"distances\":[[0.5]]}")
            .addHeader("Content-Type", "application/json"));

        List<Document> documents = store.search(SearchDocumentDto.builder()
            .queryEmbedding(new double[] {0.1, 0.2, 0.3})
            .limit(1)
            .build()).block();

        Map<String, Object> payload = documents.get(0).getMetadata().getPayload();
        assertThat(payload).containsEntry("file_name", "手册.txt");
        assertThat(payload).containsEntry("sequence", 3);
    }

    /**
     * 命中项必须显式携带 Chroma 的真实记录 id。
     *
     * <p>AgentScope 的 {@code Document.getId()} 是从 {@code docId + chunkId + content}
     * 派生的，<b>不等于</b> Chroma 的记录 id。若不把真实记录 id 显式放进 payload，
     * "检索命中 → 按命中项删除"这条链路就会因 id 不一致而<b>静默无效</b>
     * （实测：删除后记录依然存在）。
     */
    @Test
    void 命中项携带Chroma真实记录id() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[\"real-record-id-1\"]],\"documents\":[[\"命中内容\"]],"
                + "\"metadatas\":[[{\"file_name\":\"手册.txt\"}]],\"distances\":[[0.5]]}")
            .addHeader("Content-Type", "application/json"));

        List<Document> documents = store.search(SearchDocumentDto.builder()
            .queryEmbedding(new double[] {0.1, 0.2, 0.3})
            .limit(1)
            .build()).block();

        Document hit = documents.get(0);
        assertThat(hit.getMetadata().getPayload())
            .containsEntry(ChromaStore.PAYLOAD_RECORD_ID, "real-record-id-1");
    }

    /**
     * 命中项的 {@code chunkId} 承载原始切片标识，而不是 Chroma 记录 id。
     *
     * <p>这样 {@code docId} / {@code chunkId} 的语义才与 AgentScope 一致；
     * Chroma 记录 id 另有 {@code record_id} 承载，两者职责不混淆。
     */
    @Test
    void 命中项的chunkId承载原始切片标识() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[\"real-record-id-1\"]],\"documents\":[[\"命中内容\"]],"
                + "\"metadatas\":[[{\"file_name\":\"手册.txt\",\"chunk_id\":\"chunk-42\"}]],"
                + "\"distances\":[[0.5]]}")
            .addHeader("Content-Type", "application/json"));

        List<Document> documents = store.search(SearchDocumentDto.builder()
            .queryEmbedding(new double[] {0.1, 0.2, 0.3})
            .limit(1)
            .build()).block();

        assertThat(documents.get(0).getMetadata().getChunkId()).isEqualTo("chunk-42");
    }

    /**
     * 入库元数据缺少 {@code chunk_id} 时，{@code chunkId} 退回 Chroma 记录 id。
     *
     * <p>至少保证非空，调用方不会拿到 {@code null}。
     */
    @Test
    void 缺少原始切片标识时chunkId退回记录id() throws Exception {
        enqueueCollection();
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[\"real-record-id-1\"]],\"documents\":[[\"命中内容\"]],"
                + "\"metadatas\":[[{\"file_name\":\"手册.txt\"}]],"
                + "\"distances\":[[0.5]]}")
            .addHeader("Content-Type", "application/json"));

        List<Document> documents = store.search(SearchDocumentDto.builder()
            .queryEmbedding(new double[] {0.1, 0.2, 0.3})
            .limit(1)
            .build()).block();

        assertThat(documents.get(0).getMetadata().getChunkId()).isEqualTo("real-record-id-1");
    }
}
