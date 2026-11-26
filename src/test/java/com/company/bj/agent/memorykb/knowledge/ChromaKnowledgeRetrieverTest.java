package com.company.bj.agent.memorykb.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.chroma.ChromaException;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 校验 {@link ChromaKnowledgeRetriever} 的过滤检索行为。
 *
 * <p>覆盖三类语义：
 * <ul>
 *   <li><b>过滤表达式</b>：0 个条件不下发 where，1 个条件直接下发，
 *       多个条件按 {@link FilterLogic} 组装成 {@code $and} / {@code $or}；</li>
 *   <li><b>结果加工</b>：距离转相似度、相似度下限剔除、按得分降序、topK 截断；</li>
 *   <li><b>失败语义</b>：向量库不可达时抛 {@link ChromaException}。</li>
 * </ul>
 */
class ChromaKnowledgeRetrieverTest {

    /** 集合 id，用于断言请求路径。 */
    private static final String COLLECTION_ID = "c1e3a1f0-1111-2222-3333-444455556666";

    /** 查询向量维度，足够小以便构造期望值。 */
    private static final int EMBEDDING_DIMENSIONS = 3;

    /** 检索请求需要回传的字段。 */
    private static final List<String> INCLUDE_FIELDS = List.of("documents", "metadatas", "distances");

    private MockWebServer server;
    private ChromaClient client;
    private ObjectMapper mapper;
    private ChromaKnowledgeRetriever retriever;

    @BeforeEach
    void 启动Mock服务器() throws IOException {
        server = new MockWebServer();
        server.start();
        mapper = new ObjectMapper();
        client = new ChromaClient(propertiesFor(server), HttpClient.newHttpClient(), mapper);
        retriever = new ChromaKnowledgeRetriever(client, stubEmbeddings(), propertiesFor(server));
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
     * 返回确定性的桩嵌入服务：查询文本固定映射到一个已知向量。
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
     * 造一个集合创建响应，供检索器解析集合 id。
     */
    private void enqueueCollection() {
        server.enqueue(new MockResponse()
            .setBody("{\"id\":\"" + COLLECTION_ID + "\",\"name\":\"kb_main\"}")
            .addHeader("Content-Type", "application/json"));
    }

    /**
     * 造一个查询响应。
     *
     * @param ids       命中记录 id
     * @param documents 命中文档文本
     * @param distances 命中距离
     */
    private void enqueueQuery(List<String> ids, List<String> documents, List<Double> distances) {
        StringBuilder body = new StringBuilder("{\"ids\":[[");
        for (int i = 0; i < ids.size(); i++) {
            body.append(i == 0 ? "" : ",").append("\"").append(ids.get(i)).append("\"");
        }
        body.append("]],\"documents\":[[");
        for (int i = 0; i < documents.size(); i++) {
            body.append(i == 0 ? "" : ",").append("\"").append(documents.get(i)).append("\"");
        }
        body.append("]],\"metadatas\":[[");
        for (int i = 0; i < ids.size(); i++) {
            body.append(i == 0 ? "" : ",").append("{\"file_name\":\"手册.txt\"}");
        }
        body.append("]],\"distances\":[[");
        for (int i = 0; i < distances.size(); i++) {
            body.append(i == 0 ? "" : ",").append(distances.get(i));
        }
        body.append("]]}");
        server.enqueue(new MockResponse().setBody(body.toString())
            .addHeader("Content-Type", "application/json"));
    }

    @Test
    void 等值过滤只返回匹配记录() throws Exception {
        enqueueCollection();
        enqueueQuery(List.of("doc1"), List.of("命中内容"), List.of(0.25));

        List<RetrievedChunk> chunks = retriever.retrieve("查询",
            List.of(new FilterCondition("kb", "eq", "k1")),
            FilterLogic.AND, 10, null);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkId()).isEqualTo("doc1");
        assertThat(chunks.get(0).content()).isEqualTo("命中内容");
        assertThat(chunks.get(0).metadata()).containsEntry("file_name", "手册.txt");

        server.takeRequest();
        RecordedRequest queryRequest = server.takeRequest();
        JsonNode body = mapper.readTree(queryRequest.getBody().readUtf8());
        assertThat(body.get("where").get("kb").get("$eq").asText()).isEqualTo("k1");
    }

    @Test
    void 无过滤条件时不下发where字段() throws Exception {
        enqueueCollection();
        enqueueQuery(List.of("doc1"), List.of("命中内容"), List.of(0.25));

        retriever.retrieve("查询", List.of(), FilterLogic.AND, 10, null);

        server.takeRequest();
        RecordedRequest queryRequest = server.takeRequest();
        JsonNode body = mapper.readTree(queryRequest.getBody().readUtf8());
        assertThat(body.has("where")).isFalse();
    }

    @Test
    void 多条件与构造and结构() throws Exception {
        enqueueCollection();
        enqueueQuery(List.of("doc1"), List.of("命中内容"), List.of(0.25));

        retriever.retrieve("查询",
            List.of(new FilterCondition("kb", "eq", "k1"), new FilterCondition("page", "gte", "5")),
            FilterLogic.AND, 10, null);

        server.takeRequest();
        RecordedRequest queryRequest = server.takeRequest();
        JsonNode body = mapper.readTree(queryRequest.getBody().readUtf8());
        JsonNode and = body.get("where").get("$and");
        assertThat(and).hasSize(2);
        assertThat(and.get(0).get("kb").get("$eq").asText()).isEqualTo("k1");
        assertThat(and.get(1).get("page").get("$gte").asDouble()).isEqualTo(5.0);
    }

    @Test
    void 多条件或构造or结构() throws Exception {
        enqueueCollection();
        enqueueQuery(List.of("doc1"), List.of("命中内容"), List.of(0.25));

        retriever.retrieve("查询",
            List.of(new FilterCondition("kb", "eq", "k1"), new FilterCondition("kb", "eq", "k2")),
            FilterLogic.OR, 10, null);

        server.takeRequest();
        RecordedRequest queryRequest = server.takeRequest();
        JsonNode body = mapper.readTree(queryRequest.getBody().readUtf8());
        JsonNode or = body.get("where").get("$or");
        assertThat(or).hasSize(2);
        assertThat(or.get(0).get("kb").get("$eq").asText()).isEqualTo("k1");
        assertThat(or.get(1).get("kb").get("$eq").asText()).isEqualTo("k2");
    }

    @Test
    void topK与相似度下限生效() throws Exception {
        enqueueCollection();
        // 距离 0 -> 相似度 1.0；距离 1 -> 相似度 0.5；距离 3 -> 相似度 0.25。
        enqueueQuery(List.of("d1", "d2", "d3"),
            List.of("内容一", "内容二", "内容三"),
            List.of(0.0, 1.0, 3.0));

        List<RetrievedChunk> chunks = retriever.retrieve("查询", List.of(), FilterLogic.AND, 3, 0.4);

        // n_results 按 topK 传递。
        server.takeRequest();
        RecordedRequest queryRequest = server.takeRequest();
        JsonNode body = mapper.readTree(queryRequest.getBody().readUtf8());
        assertThat(body.get("n_results").asInt()).isEqualTo(3);

        // 相似度 0.25 低于下限 0.4 被剔除。
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(RetrievedChunk::chunkId).containsExactly("d1", "d2");
    }

    @Test
    void 结果按相似度降序() throws Exception {
        enqueueCollection();
        // mock 返回乱序距离，输出必须按得分降序。
        enqueueQuery(List.of("d1", "d2", "d3"),
            List.of("内容一", "内容二", "内容三"),
            List.of(1.0, 0.0, 3.0));

        List<RetrievedChunk> chunks = retriever.retrieve("查询", List.of(), FilterLogic.AND, 3, null);

        assertThat(chunks).extracting(RetrievedChunk::chunkId).containsExactly("d2", "d1", "d3");
        assertThat(chunks.get(0).score()).isGreaterThan(chunks.get(1).score());
        assertThat(chunks.get(1).score()).isGreaterThan(chunks.get(2).score());
    }

    @Test
    void 距离按倒数的形式转成相似度() throws Exception {
        enqueueCollection();
        enqueueQuery(List.of("d1"), List.of("内容一"), List.of(1.0));

        List<RetrievedChunk> chunks = retriever.retrieve("查询", List.of(), FilterLogic.AND, 1, null);

        assertThat(chunks.get(0).score()).isEqualTo(0.5);
    }

    @Test
    void 向量库不可达时抛连接异常() throws IOException {
        ChromaProperties props = propertiesFor(server);
        ChromaClient failingClient = new ChromaClient(props, HttpClient.newHttpClient(), mapper);
        ChromaKnowledgeRetriever failingRetriever =
            new ChromaKnowledgeRetriever(failingClient, stubEmbeddings(), props);
        server.shutdown();

        assertThatThrownBy(() -> failingRetriever.retrieve("查询", List.of(), FilterLogic.AND, 10, null))
            .isInstanceOf(ChromaException.class)
            .hasMessageContaining("connect");
    }
}
