package com.company.bj.agent.memorykb.chroma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.config.ChromaProperties;
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
 * 校验 {@link ChromaClient} 与 Chroma v2 REST 协议的对接细节。
 *
 * <p>重点钉住两处最容易出错的协议细节：
 * <ul>
 *   <li>{@code /query} 响应是二维数组（外层为查询条数），而 {@code /get} 响应是一维数组；</li>
 *   <li>请求路径必须是 v2 的 {@code /api/v2/tenants/{tenant}/databases/{database}/collections/...} 形式。</li>
 * </ul>
 */
class ChromaClientTest {

    private static final String COLLECTION_ID = "c1e3a1f0-1111-2222-3333-444455556666";

    private MockWebServer server;
    private ChromaClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void 启动Mock服务器() throws IOException {
        server = new MockWebServer();
        server.start();
        mapper = new ObjectMapper();
        client = new ChromaClient(propertiesFor(server), HttpClient.newHttpClient(), mapper);
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
        return props;
    }

    @Test
    void version读取服务端版本() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("\"1.0.0\"").addHeader("Content-Type", "application/json"));

        assertThat(client.version()).isEqualTo("1.0.0");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/v2/version");
    }

    @Test
    void createCollection使用v2路径并返回集合id() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"" + COLLECTION_ID + "\",\"name\":\"kb_main\"}")
            .addHeader("Content-Type", "application/json"));

        assertThat(client.createCollection("kb_main")).isEqualTo(COLLECTION_ID);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
            .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections");

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("name").asText()).isEqualTo("kb_main");
        assertThat(body.get("get_or_create").asBoolean()).isTrue();
    }

    @Test
    void createCollection在发起请求前拒绝非法名称() {
        assertThatThrownBy(() -> client.createCollection("_kb"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("collection name");

        // 非法名称不应产生任何 HTTP 请求。
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void listCollections返回集合id列表() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("[{\"id\":\"" + COLLECTION_ID + "\",\"name\":\"kb_main\"},"
                + "{\"id\":\"d2c4b2f1-1111-2222-3333-444455556666\",\"name\":\"mem_longterm\"}]")
            .addHeader("Content-Type", "application/json"));

        assertThat(client.listCollections())
            .containsExactly(COLLECTION_ID, "d2c4b2f1-1111-2222-3333-444455556666");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath())
            .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections");
    }

    @Test
    void upsert请求路径与请求体正确() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        client.upsert(COLLECTION_ID,
            List.of("doc1"),
            List.of(new double[] {0.1, 0.2, 0.3}),
            List.of("hello"),
            List.of(Map.of("source", "a.md")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections/"
            + COLLECTION_ID + "/upsert");

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("ids").get(0).asText()).isEqualTo("doc1");
        assertThat(body.get("embeddings").get(0).get(0).asDouble()).isEqualTo(0.1);
        assertThat(body.get("documents").get(0).asText()).isEqualTo("hello");
        assertThat(body.get("metadatas").get(0).get("source").asText()).isEqualTo("a.md");
    }

    @Test
    void add与upsert请求体结构一致() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        client.add(COLLECTION_ID,
            List.of("doc1", "doc2"),
            List.of(new double[] {0.1, 0.2, 0.3}, new double[] {0.4, 0.5, 0.6}),
            List.of("hello", "world"),
            List.of(Map.of("source", "a.md"), Map.of("source", "b.md")));

        client.upsert(COLLECTION_ID,
            List.of("doc1", "doc2"),
            List.of(new double[] {0.1, 0.2, 0.3}, new double[] {0.4, 0.5, 0.6}),
            List.of("hello", "world"),
            List.of(Map.of("source", "a.md"), Map.of("source", "b.md")));

        RecordedRequest addRequest = server.takeRequest();
        RecordedRequest upsertRequest = server.takeRequest();
        assertThat(addRequest.getPath()).endsWith("/" + COLLECTION_ID + "/add");
        assertThat(upsertRequest.getPath()).endsWith("/" + COLLECTION_ID + "/upsert");
        assertThat(addRequest.getBody().readUtf8()).isEqualTo(upsertRequest.getBody().readUtf8());
    }

    @Test
    void update改写单条记录元数据() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        client.update(COLLECTION_ID, "doc1", Map.of("deleted", true));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
            .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections/"
                + COLLECTION_ID + "/update");

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("ids").get(0).asText()).isEqualTo("doc1");
        assertThat(body.get("metadatas").get(0).get("deleted").asBoolean()).isTrue();
    }

    @Test
    void get按id读取记录且解析一维数组() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[\"doc1\"],\"documents\":[\"hello\"],"
                + "\"metadatas\":[{\"source\":\"a.md\",\"page\":3}]}")
            .addHeader("Content-Type", "application/json"));

        ChromaGetResult result = client.get(COLLECTION_ID, List.of("doc1"));

        assertThat(result.ids()).containsExactly("doc1");
        assertThat(result.documents()).containsExactly("hello");
        assertThat(result.metadatas()).hasSize(1);
        assertThat(result.metadatas().get(0)).containsEntry("source", "a.md").containsEntry("page", 3);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath())
            .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections/"
                + COLLECTION_ID + "/get");
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("ids").get(0).asText()).isEqualTo("doc1");
    }

    @Test
    void query请求体包含过滤条件并解析二维数组() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[\"doc1\"]],\"documents\":[[\"hello\"]],"
                + "\"metadatas\":[[{\"source\":\"a.md\"}]],\"distances\":[[0.25]]}")
            .addHeader("Content-Type", "application/json"));

        ChromaQueryResult result = client.query(COLLECTION_ID,
            new double[] {0.1, 0.2, 0.3},
            10,
            Map.of("source", "a.md"),
            List.of("documents", "metadatas", "distances"));

        assertThat(result.ids()).hasSize(1);
        assertThat(result.ids().get(0)).containsExactly("doc1");
        assertThat(result.documents().get(0)).containsExactly("hello");
        assertThat(result.metadatas().get(0).get(0)).containsEntry("source", "a.md");
        assertThat(result.distances().get(0).get(0)).isEqualTo(0.25);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath())
            .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections/"
                + COLLECTION_ID + "/query");

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("query_embeddings").get(0).get(0).asDouble()).isEqualTo(0.1);
        assertThat(body.get("n_results").asInt()).isEqualTo(10);
        assertThat(body.get("where").get("source").asText()).isEqualTo("a.md");
        assertThat(body.get("include").get(0).asText()).isEqualTo("documents");
    }

    @Test
    void query未指定过滤条件时不发送where字段() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"ids\":[[]],\"documents\":[[]],\"metadatas\":[[]],\"distances\":[[]]}")
            .addHeader("Content-Type", "application/json"));

        client.query(COLLECTION_ID, new double[] {0.1}, 5, null, List.of("documents"));

        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.has("where")).isFalse();
        assertThat(body.get("n_results").asInt()).isEqualTo(5);
    }

    @Test
    void count解析计数结果() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"count\":7}").addHeader("Content-Type", "application/json"));

        assertThat(client.count(COLLECTION_ID)).isEqualTo(7L);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath())
            .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections/"
                + COLLECTION_ID + "/count");
    }

    @Test
    void count兼容服务端返回的裸数字形态() throws Exception {
        // 本机 Chroma v2 服务的 /count 返回裸 JSON 数字而非 {"count": N} 对象。
        server.enqueue(new MockResponse().setBody("3").addHeader("Content-Type", "application/json"));

        assertThat(client.count(COLLECTION_ID)).isEqualTo(3L);
    }

    @Test
    void delete按id删除记录() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        client.delete(COLLECTION_ID, List.of("doc1", "doc2"));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
            .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections/"
                + COLLECTION_ID + "/delete");

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("ids").get(0).asText()).isEqualTo("doc1");
        assertThat(body.get("ids").get(1).asText()).isEqualTo("doc2");
    }

    @Test
    void 非2xx响应抛出ChromaException且包含状态码与响应体() {
        server.enqueue(new MockResponse().setResponseCode(422).setBody("{\"error\":\"invalid name\"}"));

        assertThatThrownBy(() -> client.count(COLLECTION_ID))
            .isInstanceOf(ChromaException.class)
            .hasMessageContaining("422")
            .hasMessageContaining("invalid name");
    }

    @Test
    void 连接失败抛出ChromaException() throws IOException {
        ChromaClient failingClient =
            new ChromaClient(propertiesFor(server), HttpClient.newHttpClient(), mapper);
        server.shutdown();

        assertThatThrownBy(failingClient::version)
            .isInstanceOf(ChromaException.class)
            .hasMessageContaining("connect");
    }

    @Test
    void 健康检查在服务端可用时返回版本号() {
        // isHealthy() 与 version() 各消费一个响应，两个都要入队，
        // 否则第二次调用会因无响应可取而阻塞到请求超时。
        server.enqueue(new MockResponse().setBody("\"1.0.0\"").addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody("\"1.0.0\"").addHeader("Content-Type", "application/json"));

        ChromaHealthIndicator indicator = new ChromaHealthIndicator(client);
        assertThat(indicator.isHealthy()).isTrue();
        assertThat(indicator.version()).isEqualTo("1.0.0");
    }

    @Test
    void 健康检查在服务端异常时判定为不健康() {
        // 同理入队两个 500：isHealthy() 消费第一个，version() 必须真正命中非 2xx 分支，
        // 而不是因队列耗尽落到 30 秒请求超时。
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        ChromaHealthIndicator indicator = new ChromaHealthIndicator(client);
        assertThat(indicator.isHealthy()).isFalse();

        // 断言落在消息内容上：必须含状态码 500，
        // 以此证明走的是非 2xx 分支而不是 "connect failed" 的超时分支。
        assertThatThrownBy(indicator::version)
            .isInstanceOf(ChromaException.class)
            .hasMessageContaining("500")
            .hasMessageContaining("boom")
            .hasMessageNotContaining("connect");
    }
}
