package com.company.bj.agent.memorykb.knowledge.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link OpenAiCompatibleEmbeddingService} 与 OpenAI 兼容嵌入接口的对接细节。
 *
 * <p>重点钉住三处最容易出错的协议细节：
 * <ul>
 *   <li>请求体固定为 {@code {"model": ..., "input": [...]}}；</li>
 *   <li>响应中 {@code data} 的顺序<b>不保证</b>与 {@code input} 一致，必须按 {@code data[i].index} 重排；</li>
 *   <li>批量嵌入按 {@code batchSize} 切分请求，避免逐条调用拖垮链路。</li>
 * </ul>
 */
class EmbeddingServiceTest {

    /** 测试用凭据，仅为占位符，不是真实 api key。 */
    private static final String TEST_API_KEY = "test-key-placeholder";

    /** 嵌入向量维度，与 {@code qwen3.7-text-embedding} 实际输出一致。 */
    private static final int VECTOR_DIMENSION = 1024;

    /** 单批嵌入的最大条数。 */
    private static final int BATCH_SIZE = 32;

    /** 浮点比较容差：归一化涉及除法，不能用精确相等断言。 */
    private static final org.assertj.core.data.Offset<Double> OFFSET =
        org.assertj.core.data.Offset.offset(1e-9);

    private MockWebServer server;
    private OpenAiCompatibleEmbeddingService service;
    private ObjectMapper mapper;

    @BeforeEach
    void 启动Mock服务器() throws IOException {
        server = new MockWebServer();
        server.start();
        mapper = new ObjectMapper();
        service = new OpenAiCompatibleEmbeddingService(propertiesFor(server), HttpClient.newHttpClient(), mapper);
    }

    @AfterEach
    void 关闭Mock服务器() throws IOException {
        server.shutdown();
    }

    private EmbeddingProperties propertiesFor(MockWebServer mockServer) {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setBaseUrl("http://localhost:" + mockServer.getPort());
        props.setApiKey(TEST_API_KEY);
        props.setModel("qwen3.7-text-embedding");
        props.setDimensions(VECTOR_DIMENSION);
        props.setBatchSize(BATCH_SIZE);
        return props;
    }

    /**
     * 构造一份嵌入响应，{@code data} 按给定顺序排列。
     *
     * <p>每个向量都是完整的 {@value #VECTOR_DIMENSION} 维：首个分量取 {@code markers} 中
     * 对应的标记值，第二个分量取标记值的一半，其余分量为 0。
     *
     * <p><b>为什么第二个分量取一半</b>：本服务会把向量归一化到单位长度，
     * 单轴向量（{@code [m, 0, 0, ...]}）归一化后一律变成 {@code [1, 0, 0, ...]}，
     * 标记值就此丢失，"按 index 重排"这类断言将失去可观测性。
     * 加上一个<b>成比例</b>的第二分量后，归一化只除以同一个模长，
     * 各向量的相对大小得以保留，标记值仍可区分。
     *
     * @param markers 每个向量首个分量的标记值，用于断言向量与输入的对应关系
     * @return 响应体 JSON
     */
    private String responseBody(List<Integer> markers) {
        StringBuilder builder = new StringBuilder("{\"data\":[");
        for (int i = 0; i < markers.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"index\":").append(i).append(",\"embedding\":[")
                .append(markers.get(i));
            for (int d = 1; d < VECTOR_DIMENSION; d++) {
                builder.append(",0.0");
            }
            builder.append("]}");
        }
        return builder.append("]}").toString();
    }

    /**
     * 构造一个指定首分量的完整维度向量 JSON 数组。
     *
     * @param firstComponent 首个分量的取值
     * @return 向量 JSON 数组文本
     */
    private String vectorJson(double firstComponent) {
        StringBuilder builder = new StringBuilder("[");
        builder.append(firstComponent);
        for (int d = 1; d < VECTOR_DIMENSION; d++) {
            builder.append(",0.0");
        }
        return builder.append("]").toString();
    }

    /**
     * 构造一个全零的完整维度向量 JSON 数组。
     *
     * @return 向量 JSON 数组文本
     */
    private String zeroVectorJson() {
        StringBuilder builder = new StringBuilder("[");
        for (int d = 0; d < VECTOR_DIMENSION; d++) {
            if (d > 0) {
                builder.append(',');
            }
            builder.append("0.0");
        }
        return builder.append("]").toString();
    }

    /**
     * 构造一个前两个分量指定的完整维度向量 JSON 数组。
     *
     * <p>双分量向量的模长为 {@code sqrt(a² + b²)}，归一化后两个分量都可预期，
     * 因此用于"归一化后仍能区分不同标记"的场景。
     *
     * @param firstComponent  首个分量的取值
     * @param secondComponent 第二个分量的取值
     * @return 向量 JSON 数组文本
     */
    private String twoAxisVectorJson(double firstComponent, double secondComponent) {
        StringBuilder builder = new StringBuilder("[");
        builder.append(firstComponent).append(',').append(secondComponent);
        for (int d = 2; d < VECTOR_DIMENSION; d++) {
            builder.append(",0.0");
        }
        return builder.append("]").toString();
    }

    @Test
    void 嵌入请求体包含模型与输入文本() throws Exception {
        server.enqueue(new MockResponse().setBody(responseBody(List.of(1)))
            .addHeader("Content-Type", "application/json"));

        service.embed(List.of("hello"));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/embeddings");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TEST_API_KEY);

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("model").asText()).isEqualTo("qwen3.7-text-embedding");
        assertThat(body.get("input").isArray()).isTrue();
        assertThat(body.get("input").get(0).asText()).isEqualTo("hello");
    }

    @Test
    void 嵌入返回向量维度为1024() throws Exception {
        server.enqueue(new MockResponse().setBody(responseBody(List.of(1)))
            .addHeader("Content-Type", "application/json"));

        List<double[]> vectors = service.embed(List.of("hello"));

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).hasSize(VECTOR_DIMENSION);
        // 标记向量为 [1, 0, 0, ...]，模长恰为 1，归一化后原样保留。
        assertThat(vectors.get(0)[0]).isEqualTo(1.0);
    }

    @Test
    void 单条文本嵌入返回单向量() throws Exception {
        server.enqueue(new MockResponse().setBody(responseBody(List.of(7)))
            .addHeader("Content-Type", "application/json"));

        double[] vector = service.embed("hello");

        assertThat(vector).hasSize(VECTOR_DIMENSION);
        // 标记向量为 [7, 0, 0, ...]，模长 7，归一化后首分量为 1。
        assertThat(vector[0]).isEqualTo(1.0);
    }

    /**
     * 非单位向量被归一化到单位长度。
     *
     * <p>这条断言钉住 Critical 修复的核心契约：
     * 本服务产出的向量<b>一律</b>是单位向量。
     * 不归一化则 Chroma 的平方 L2 距离随模长膨胀，
     * 得分 {@code 1/(1+distance)} 被系统性压低、失去区分度。
     */
    @Test
    void 非单位输入向量被归一化到单位长度() throws Exception {
        // [3, 4, 0, ...] 的模长为 5，归一化后应为 [0.6, 0.8, 0, ...]。
        server.enqueue(new MockResponse()
            .setBody("{\"data\":[{\"index\":0,\"embedding\":[3.0,4.0"
                + ",0.0".repeat(VECTOR_DIMENSION - 2) + "]}]}")
            .addHeader("Content-Type", "application/json"));

        double[] vector = service.embed("hello");

        assertThat(vector[0]).isCloseTo(0.6, OFFSET);
        assertThat(vector[1]).isCloseTo(0.8, OFFSET);
        assertThat(EmbeddingNormalizer.norm(vector)).isCloseTo(1.0, OFFSET);
    }

    /**
     * 零向量输入不产生 NaN。
     *
     * <p>零向量无法归一化（除零会产生 NaN，进而污染下游距离计算），
     * 约定原样返回零向量。
     */
    @Test
    void 零向量输入原样返回且不产生NaN() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"data\":[{\"index\":0,\"embedding\":" + zeroVectorJson() + "}]}")
            .addHeader("Content-Type", "application/json"));

        double[] vector = service.embed("hello");

        assertThat(vector).hasSize(VECTOR_DIMENSION);
        for (double component : vector) {
            assertThat(Double.isNaN(component)).isFalse();
            assertThat(Double.isInfinite(component)).isFalse();
        }
        assertThat(EmbeddingNormalizer.norm(vector)).isZero();
    }

    @Test
    void 批量嵌入按批大小切分请求() throws Exception {
        // 70 条文本按 32 条/批切成 32 + 32 + 6 共 3 次请求。
        for (int i = 0; i < 3; i++) {
            int batchSize = i == 2 ? 6 : BATCH_SIZE;
            List<Integer> markers = new ArrayList<>(batchSize);
            for (int j = 0; j < batchSize; j++) {
                markers.add(i * BATCH_SIZE + j);
            }
            server.enqueue(new MockResponse().setBody(responseBody(markers))
                .addHeader("Content-Type", "application/json"));
        }

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            texts.add("t" + i);
        }
        List<double[]> vectors = service.embed(texts);

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(vectors).hasSize(70);
    }

    @Test
    void 批量嵌入按index重排响应顺序() throws Exception {
        // 服务端返回的 data 顺序与 input 顺序不一致：第 0 个元素标记为 99，
        // 第 1 个元素标记为 1。若不按 index 重排，向量就会与输入错位。
        //
        // 两个标记向量都用双分量且第二分量相同(0.1)：归一化后首分量分别是
        // 99/sqrt(99²+0.1²) 与 1/sqrt(1+0.1²)，仍可区分，
        // 而单轴向量归一化后都会变成 [1, 0, ...]、标记丢失。
        server.enqueue(new MockResponse()
            .setBody("{\"data\":[{\"index\":1,\"embedding\":"
                + twoAxisVectorJson(1.0, 0.1) + "},{\"index\":0,\"embedding\":"
                + twoAxisVectorJson(99.0, 0.1) + "}]}")
            .addHeader("Content-Type", "application/json"));

        List<double[]> vectors = service.embed(List.of("first", "second"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)[0]).isCloseTo(99.0 / Math.sqrt(99.0 * 99.0 + 0.01), OFFSET);
        assertThat(vectors.get(1)[0]).isCloseTo(1.0 / Math.sqrt(1.0 + 0.01), OFFSET);
    }

    @Test
    void 空文本列表不发起请求() {
        assertThat(service.embed(List.of())).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 缺少凭据时抛出EmbeddingException并指明环境变量() {
        EmbeddingProperties props = propertiesFor(server);
        props.setApiKey("");
        OpenAiCompatibleEmbeddingService noKeyService =
            new OpenAiCompatibleEmbeddingService(props, HttpClient.newHttpClient(), mapper);

        assertThatThrownBy(() -> noKeyService.embed(List.of("hello")))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("DASHSCOPE_API_KEY");

        // 缺少凭据时不应发出任何请求。
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 服务端返回错误状态时抛出EmbeddingException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));

        assertThatThrownBy(() -> service.embed(List.of("hello")))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("500")
            .hasMessageContaining("boom");
    }

    @Test
    void 响应维度不足时抛出EmbeddingException() {
        server.enqueue(new MockResponse().setBody("{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}")
            .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> service.embed(List.of("hello")))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("1024");
    }

    @Test
    void 响应出现重复index时抛出指向重复index的异常() {
        // 两条结果都标着 index 0，缺的是 index 1。
        // 数量守卫生效（2 == 2），但真实原因是"index 重复"，
        // 不能被误报成"缺少 index 1 的结果" —— 那会把排查引向错误方向。
        server.enqueue(new MockResponse()
            .setBody("{\"data\":[{\"index\":0,\"embedding\":" + vectorJson(1.0)
                + "},{\"index\":0,\"embedding\":" + vectorJson(2.0) + "}]}")
            .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> service.embed(List.of("first", "second")))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("duplicate index")
            // 必须与"缺少结果"语义区分开：后者才是误报。
            .hasMessageNotContaining("missing result");
    }
}
