package com.company.bj.agent.memorykb.chroma;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 针对真实 Chroma 服务的全链路冒烟验证。
 *
 * <p>默认不执行，仅在设置环境变量 {@code CHROMA_IT=true} 时运行，避免常规构建依赖本机服务。
 *
 * <p>使用固定集合名 {@code it_chroma_client}，配合服务端的 {@code get_or_create} 语义，
 * 重复运行不会产生副作用。
 *
 * <p><b>清理范围说明</b>：本测试只删除<b>自己写入</b>的记录，结束后集合内记录数为 0。
 * 但<b>集合本身会保留在服务端</b>——该 Chroma v2 服务没有集合级删除端点
 * （{@code DELETE /api/v2/tenants/{t}/databases/{d}/collections/{id}} 返回 404，
 * 服务端 OpenAPI 中亦无该路径），这是服务端限制而非本测试的遗漏。
 * 若要彻底移除，只能重启 Chroma 服务或执行会清空整个库的 {@code POST /api/v2/reset}。
 */
@EnabledIfEnvironmentVariable(named = "CHROMA_IT", matches = "true")
class ChromaClientIT {

    /** 冒烟测试专用集合名，满足 3-512 长度与 [a-zA-Z0-9._-] 字符集规则。 */
    private static final String COLLECTION_NAME = "it_chroma_client";

    /** 冒烟测试写入的记录 id 前缀，便于清理时精确删除。 */
    private static final String RECORD_PREFIX = "it_rec_";

    private ChromaClient client;
    private String collectionId;

    @BeforeEach
    void 准备集合() {
        ChromaProperties props = new ChromaProperties();
        // 使用 ChromaProperties 的默认 baseUrl（http://localhost:8765），
        // 与 application.yml 的默认值保持一致，不在测试里写死任何地址特例。
        props.setTenant("default_tenant");
        props.setDatabase("default_database");

        client = new ChromaClient(props, HttpClient.newHttpClient(), new ObjectMapper());
        collectionId = client.createCollection(COLLECTION_NAME);
    }

    @Test
    void 建集合写入检索计数删除全链路() {
        // 1. 建集合：同名集合复用同一 id。
        assertThat(collectionId).isNotBlank();
        assertThat(client.listCollections()).contains(collectionId);

        // 2. 写入两条记录。
        client.add(collectionId,
            List.of(RECORD_PREFIX + "1", RECORD_PREFIX + "2"),
            List.of(new double[] {0.1, 0.2, 0.3}, new double[] {0.9, 0.8, 0.7}),
            List.of("first document", "second document"),
            List.of(Map.of("source", "a.md"), Map.of("source", "b.md")));

        // 3. 计数。
        assertThat(client.count(collectionId)).isEqualTo(2L);

        // 4. 按元数据过滤检索：只应命中 source=a.md 的那条。
        ChromaQueryResult result = client.query(collectionId,
            new double[] {0.1, 0.2, 0.3},
            10,
            Map.of("source", "a.md"),
            List.of("documents", "metadatas", "distances"));

        assertThat(result.ids()).hasSize(1);
        assertThat(result.ids().get(0)).containsExactly(RECORD_PREFIX + "1");
        assertThat(result.documents().get(0)).containsExactly("first document");
        assertThat(result.metadatas().get(0).get(0)).containsEntry("source", "a.md");
        assertThat(result.distances().get(0)).hasSize(1);

        // 5. 按 id 读取：/get 返回的是一维数组。
        ChromaGetResult fetched = client.get(collectionId, List.of(RECORD_PREFIX + "2"));
        assertThat(fetched.ids()).containsExactly(RECORD_PREFIX + "2");
        assertThat(fetched.documents()).containsExactly("second document");

        // 6. 改写元数据：/update 的 metadatas 必须以数组发送。
        client.update(collectionId, RECORD_PREFIX + "2", Map.of("source", "b.md", "page", 7));
        ChromaGetResult updated = client.get(collectionId, List.of(RECORD_PREFIX + "2"));
        assertThat(updated.metadatas().get(0)).containsEntry("page", 7);

        // 7. 幂等覆盖：同 id 再次 upsert 不增加条数，只改写内容。
        client.upsert(collectionId,
            List.of(RECORD_PREFIX + "1"),
            List.of(new double[] {0.1, 0.2, 0.3}),
            List.of("first document rewritten"),
            List.of(Map.of("source", "a.md")));
        assertThat(client.count(collectionId)).isEqualTo(2L);

        // 8. 删除记录。
        client.delete(collectionId, List.of(RECORD_PREFIX + "1", RECORD_PREFIX + "2"));

        // 9. 残留记录数必须为 0：测试只清理自己写入的记录，
        //    集合本身因服务端没有集合级删除端点而保留（详见类注释）。
        assertThat(client.count(collectionId)).isZero();
    }

    @Test
    void 版本号可读取() {
        assertThat(client.version()).isNotBlank();
        assertThat(new ChromaHealthIndicator(client).isHealthy()).isTrue();
    }
}
