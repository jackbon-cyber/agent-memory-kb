package com.company.bj.agent.memorykb.chroma;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

/**
 * 验证 AgentScope 的 {@code SimpleKnowledge} 能通过 {@link ChromaStore} 完成检索。
 *
 * <p>本测试把 AgentScope 的原生链路真正跑通：
 * {@code SimpleKnowledge.retrieve} → {@code embeddingModel.embed} →
 * {@code VDBStoreBase.search} → 真实 Chroma 服务 → 返回带得分的 {@link Document}。
 *
 * <p><b>两段式验证</b>：
 * <ol>
 *   <li><b>SPI 链路</b>（只需 {@code CHROMA_IT=true}）：用确定性本地嵌入模型驱动
 *       {@code SimpleKnowledge}，真实访问 Chroma 服务。这段验证的是
 *       「AgentScope 的 SPI 契约 ↔ 本工程的 {@link ChromaStore}」是否真的对得上，
 *       不受外部模型网关可用性影响。</li>
 *   <li><b>真实嵌入链路</b>（额外需要 {@code DASHSCOPE_API_KEY}）：
 *       用 {@link OpenAITextEmbedding} 指向本工程的嵌入网关，
 *       验证 AgentScope 的 {@code EmbeddingModel} 适配能真正跑通。</li>
 * </ol>
 *
 * <p>两段都<b>不会静默通过</b>：第二段的环境变量缺失时整段被跳过而非假装成功，
 * 跳过情况在报告中显式说明。
 *
 * <p><b>清理范围说明</b>：本测试只删除<b>自己写入</b>的记录，结束后集合内记录数为 0。
 * 集合本身会保留在服务端 —— 该 Chroma v2 服务没有集合级删除端点（详见
 * {@code ChromaClientIT} 的类注释）。
 */
@EnabledIfEnvironmentVariable(named = "CHROMA_IT", matches = "true")
class SimpleKnowledgeWiringIT {

    /** 冒烟测试专用集合名，满足 3-512 长度与 [a-zA-Z0-9._-] 字符集规则。 */
    private static final String COLLECTION_NAME = "it_simple_knowledge";

    /** 冒烟测试写入的记录 id 前缀，便于清理时精确删除。 */
    private static final String RECORD_PREFIX = "it_snk_";

    /** 本地确定性嵌入模型的向量维度，必须与 Chroma 集合维度一致。 */
    private static final int LOCAL_EMBEDDING_DIMENSIONS = 4;

    /** 真实嵌入网关的凭据环境变量名。 */
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    /**
     * 本测试显式使用的相似度下限。
     *
     * <p><b>取值依据</b>：AgentScope {@code RetrieveConfig} 的默认 {@code scoreThreshold}
     * 是 0.5。在单位向量下得分 {@code = 1/(1+squared_L2)}，而
     * {@code squared_L2 = 2 - 2·cos}，因此 0.5 等价于要求 {@code cos >= 0.5} ——
     * 对语义检索而言偏严（相差 60° 以上的语义即被判为不相关）。
     *
     * <p>本测试取 0.2 作为显式下限：单位向量下平方 L2 的上界是 4，
     * 对应得分下界恰为 {@code 1/(1+4) = 0.2}，因此 0.2 意味着
     * "不丢弃任何几何上可达的命中"，把过滤权完全交给排序，
     * 使本测试聚焦于"检索链路是否连通"而非"阈值调参"。
     *
     * <p>该值<b>不依赖</b> AgentScope 的默认值 —— 默认值一旦被上游改动，
     * 显式设定可保证本测试的行为稳定。
     */
    private static final double SCORE_THRESHOLD = 0.2;

    private ChromaClient client;
    private ChromaStore store;
    private String collectionId;

    @BeforeEach
    void 准备集合() {
        ChromaProperties props = new ChromaProperties();
        props.setTenant("default_tenant");
        props.setDatabase("default_database");
        props.setKnowledgeCollection(COLLECTION_NAME);

        client = new ChromaClient(props, java.net.http.HttpClient.newHttpClient(),
            new com.fasterxml.jackson.databind.ObjectMapper());
        store = new ChromaStore(client, localEmbeddings(), props);
        collectionId = client.createCollection(COLLECTION_NAME);
    }

    /**
     * 返回确定性的本地嵌入模型：把文本按字符累加映射到一个固定维度的向量。
     *
     * <p>目的是让 SPI 链路的验证不依赖外部模型网关 —— 链路本身要验证的是
     * 「AgentScope 的嵌入结果能否被 {@link ChromaStore} 拿去查 Chroma 并原样取回」，
     * 而不是某个具体模型服务的可用性。
     *
     * <p><b>注意</b>：本模型<b>刻意不归一化</b>。归一化由
     * {@link com.company.bj.agent.memorykb.knowledge.embed.EmbeddingNormalizer}
     * 在向量进入 Chroma 之前统一完成（AgentScope 的 {@code EmbeddingModel}
     * 产出的是"外来"向量，必须由 {@link ChromaStore} 这一关口归一化）。
     * 这里保持原始尺度，才能验证"外来非单位向量也会被正确归一化"。
     */
    private EmbeddingService localEmbeddings() {
        return new EmbeddingService() {
            @Override
            public List<double[]> embed(List<String> texts) {
                return texts.stream().map(this::vectorOf).toList();
            }

            /**
             * 把文本按字符累加映射为一个<b>未归一化</b>的向量。
             *
             * <p>刻意不归一化：真实语义嵌入的模长不是 1（1024 维嵌入通常在数倍量级），
             * 若测试数据用归一化向量，就会掩盖"归一化被误删"这一缺陷。
             *
             * @param text 文本
             * @return 原始尺度的向量
             */
            private double[] vectorOf(String text) {
                double[] vector = new double[LOCAL_EMBEDDING_DIMENSIONS];
                for (int i = 0; i < text.length(); i++) {
                    vector[i % LOCAL_EMBEDDING_DIMENSIONS] += text.charAt(i) / (double) Character.MAX_VALUE;
                }
                return vector;
            }
        };
    }

    /**
     * 把本地嵌入服务包装成 AgentScope 的 {@link EmbeddingModel}。
     *
     * @param embeddings 本地嵌入服务
     * @return AgentScope 嵌入模型
     */
    private EmbeddingModel localEmbeddingModel(EmbeddingService embeddings) {
        return new EmbeddingModel() {
            @Override
            public Mono<double[]> embed(ContentBlock contentBlock) {
                String text = contentBlock instanceof TextBlock textBlock ? textBlock.getText() : "";
                return Mono.just(embeddings.embed(text));
            }

            @Override
            public String getModelName() {
                return "local-deterministic";
            }

            @Override
            public int getDimensions() {
                return LOCAL_EMBEDDING_DIMENSIONS;
            }
        };
    }

    /**
     * 造一条 AgentScope 文档。
     *
     * @param id   记录 id
     * @param text 正文
     * @return 文档
     */
    private Document document(String id, String text) {
        DocumentMetadata metadata = DocumentMetadata.builder()
            .content(TextBlock.builder().text(text).build())
            .docId("it-doc")
            .chunkId(id)
            .addPayload("file_name", "手册.txt")
            .build();
        Document document = new Document(metadata);
        document.setEmbedding(localEmbeddings().embed(text));
        return document;
    }

    @Test
    void simpleKnowledge能通过ChromaStore完成检索() {
        List<Document> documents = List.of(
            document(RECORD_PREFIX + "1", "第一章：向量检索的基本原理"),
            document(RECORD_PREFIX + "2", "第二章：元数据过滤的作用"));

        // 1. 通过 ChromaStore 写入两条记录。
        store.add(documents).block();

        // 2. 断言记录确实落到服务端。
        assertThat(client.count(collectionId)).isEqualTo(2L);

        // 3. 用 AgentScope 原生的 SimpleKnowledge 检索：
        //    SimpleKnowledge.retrieve 会用 EmbeddingModel 嵌入查询，
        //    再调用 ChromaStore.search 走真实 Chroma 检索。
        SimpleKnowledge knowledge = SimpleKnowledge.builder()
            .embeddingModel(localEmbeddingModel(localEmbeddings()))
            .embeddingStore(store)
            .build();

        List<Document> hits = knowledge
            .retrieve("向量检索的基本原理",
                RetrieveConfig.builder().limit(3).scoreThreshold(SCORE_THRESHOLD).build())
            .block();

        assertThat(hits).isNotNull();
        assertThat(hits).isNotEmpty();

        // 4. 命中文档必须来自真实 Chroma 记录，并带相似度得分。
        Document top = hits.get(0);
        assertThat(top.getMetadata().getContentText()).contains("向量检索");
        assertThat(top.getScore()).isNotNull();
        assertThat(top.getScore()).isGreaterThan(0.0);

        // 5. AgentScope 侧必须能追溯到原始记录的元数据。
        assertThat(top.getMetadata().getPayload()).containsEntry("file_name", "手册.txt");

        // 6. 钉住 AgentScope 的默认相似度下限：RetrieveConfig.Builder 的 scoreThreshold
        //    默认值是 0.5（不是 0），SimpleKnowledge 会用它过滤掉得分低于 0.5 的文档。
        //    这条断言把该默认值固定下来 —— 若上游改动默认值，本测试会立刻失败，
        //    避免"检索恒为空"这类问题在上线后才暴露。
        assertThat(RetrieveConfig.builder().build().getScoreThreshold()).isEqualTo(0.5);
        assertThat(top.getScore()).isGreaterThanOrEqualTo(0.5);

        // 7. 清理自己写入的记录，集合内记录数归零。
        //    注意：Chroma 里的记录 id 是 AgentScope 的 Document.getId()
        //    （由 docId + chunkId + content 确定性派生的 UUID），
        //    不是我们构造文档时传入的 chunkId，因此清理必须用 getId()。
        client.delete(collectionId, documents.stream().map(Document::getId).toList());
        assertThat(client.count(collectionId)).isZero();
    }

    @Test
    void simpleKnowledge能通过ChromaStore删除记录() {
        Document document = document(RECORD_PREFIX + "3", "待删除的记录");
        store.add(List.of(document)).block();
        assertThat(client.count(collectionId)).isEqualTo(1L);

        Boolean deleted = store.delete(document.getId()).block();

        assertThat(deleted).isTrue();
        assertThat(client.count(collectionId)).isZero();
    }

    /**
     * 防回归：<b>未归一化</b>的向量经检索链路后，用<b>默认</b>阈值仍能召回。
     *
     * <p><b>这条测试要抓住什么</b>：Chroma 返回的是<b>平方</b> L2 距离，
     * 而 AgentScope {@code RetrieveConfig} 的默认 {@code scoreThreshold} 是 0.5，
     * 等价于要求平方 L2 ≤ 1。若写入的向量未经归一化，
     * 平方 L2 会随模长与维度急剧膨胀（实测中等模长的 1024 维向量距离达 4~7），
     * 得分被压到 0.12~0.20，<b>全部命中项都会被默认阈值滤掉</b>，
     * 检索结果恒为空。
     *
     * <p>因此这里写入<b>刻意未归一化</b>的向量（模长明显远离 1），
     * 并用<b>默认</b> {@code RetrieveConfig} 断言召回非空 ——
     * 一旦归一化被误删，这条测试立刻失败。
     *
     * <p>与旧的"归一化后向量召回"用例互为反面：
     * 旧用例验证"归一化正确时能召回"，本用例验证"归一化缺失时不能召回"。
     */
    @Test
    void 未归一化向量经检索链路后仍能被默认阈值召回() {
        // 构造模长明显不等于 1 的向量：全部分量取 3.0，模长 = 3 * sqrt(维度)。
        double[] rawVector = new double[LOCAL_EMBEDDING_DIMENSIONS];
        java.util.Arrays.fill(rawVector, 3.0);

        DocumentMetadata metadata = DocumentMetadata.builder()
            .content(TextBlock.builder().text("未归一化向量写入的记录").build())
            .docId("it-raw-doc")
            .chunkId(RECORD_PREFIX + "raw")
            .addPayload("file_name", "手册.txt")
            .build();
        Document rawDocument = new Document(metadata);
        rawDocument.setEmbedding(rawVector);

        store.add(List.of(rawDocument)).block();
        assertThat(client.count(collectionId)).isEqualTo(1L);

        // 查询向量同样未归一化：模拟"AgentScope 的 EmbeddingModel 产出原始尺度向量"。
        SimpleKnowledge knowledge = SimpleKnowledge.builder()
            .embeddingModel(localEmbeddingModel(localEmbeddings()))
            .embeddingStore(store)
            .build();

        // 关键：使用默认 RetrieveConfig（不手工调低阈值），
        // 若归一化缺失，得分将低于默认的 0.5，结果必为空。
        List<Document> hits = knowledge
            .retrieve("未归一化向量写入的记录", RetrieveConfig.builder().limit(3).build())
            .block();

        assertThat(hits).isNotNull();
        assertThat(hits)
            .as("未归一化向量必须先被归一化，否则平方 L2 膨胀、得分跌破默认阈值 0.5")
            .isNotEmpty();
        assertThat(hits.get(0).getScore()).isGreaterThanOrEqualTo(0.5);

        client.delete(collectionId, List.of(rawDocument.getId()));
        assertThat(client.count(collectionId)).isZero();
    }

    /**
     * 归一化后的向量模长为 1（浮点容差内）。
     *
     * <p>这条断言把"归一化确实发生了、且是正确的 L2 归一化"钉死 ——
     * 只断言"检索非空"无法排除"恰好靠别的机制召回"。
     *
     * <p>验证方式：把一个<b>已知模长</b>的向量与它自身的<b>放大版本</b>分别入库，
     * 两者归一化后方向相同，因此检索该向量时命中二者的得分应当<b>相同</b> ——
     * 若未归一化，放大后的向量距离会被平方放大，得分必然显著更低。
     */
    @Test
    void 归一化后原始尺度与放大尺度的向量得分相同() {
        // 模长为 3 * sqrt(4) = 6 的向量。
        double[] rawVector = {3.0, 3.0, 3.0, 3.0};
        // 同一方向的放大 10 倍版本：模长 60。
        double[] scaledVector = {30.0, 30.0, 30.0, 30.0};

        DocumentMetadata metadata = DocumentMetadata.builder()
            .content(TextBlock.builder().text("归一化对照记录").build())
            .docId("it-norm-doc")
            .chunkId(RECORD_PREFIX + "norm")
            .build();
        Document rawDocument = new Document(metadata);
        rawDocument.setEmbedding(rawVector);

        DocumentMetadata scaledMetadata = DocumentMetadata.builder()
            .content(TextBlock.builder().text("归一化对照记录放大版").build())
            .docId("it-norm-doc")
            .chunkId(RECORD_PREFIX + "norm_scaled")
            .build();
        Document scaledDocument = new Document(scaledMetadata);
        scaledDocument.setEmbedding(scaledVector);

        store.add(List.of(rawDocument, scaledDocument)).block();
        assertThat(client.count(collectionId)).isEqualTo(2L);

        // 查询向量同样未归一化（模长 6）。
        double[] queryVector = {3.0, 3.0, 3.0, 3.0};

        List<Document> hits = store.search(SearchDocumentDto.builder()
            .queryEmbedding(queryVector)
            .limit(2)
            .build()).block();

        assertThat(hits).hasSize(2);
        // 两者方向相同，归一化后与查询向量的平方 L2 都是 0，得分都是 1。
        assertThat(hits.get(0).getScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(1).getScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));

        client.delete(collectionId, List.of(rawDocument.getId(), scaledDocument.getId()));
        assertThat(client.count(collectionId)).isZero();
    }

    /**
     * 防回归：检索命中后，用命中项携带的记录 id 删除，集合计数归零。
     *
     * <p><b>这条测试要抓住什么</b>：AgentScope 的 {@code Document.getId()} 是从
     * {@code docId + chunkId + content} 确定性派生的（{@code UUID.nameUUIDFromBytes}），
     * <b>不等于</b> Chroma 的记录 id。若检索结果不显式携带真实记录 id，
     * "检索 → 删除"这条链路会用错误的 id 去删，
     * 请求发出、服务端不报错、但<b>记录依然存在</b> —— 静默失败。
     *
     * <p>因此命中项必须把 Chroma 记录 id 放在 payload 的 {@code record_id} 键下，
     * 删除时使用它。
     */
    @Test
    void 检索命中后按命中携带的记录id删除使计数归零() {
        Document document = document(RECORD_PREFIX + "4", "检索后删除的记录");
        store.add(List.of(document)).block();
        assertThat(client.count(collectionId)).isEqualTo(1L);

        SimpleKnowledge knowledge = SimpleKnowledge.builder()
            .embeddingModel(localEmbeddingModel(localEmbeddings()))
            .embeddingStore(store)
            .build();

        List<Document> hits = knowledge
            .retrieve("检索后删除的记录",
                RetrieveConfig.builder().limit(3).scoreThreshold(SCORE_THRESHOLD).build())
            .block();

        assertThat(hits).isNotNull();
        assertThat(hits).isNotEmpty();

        Document hit = hits.get(0);
        Object recordId = hit.getMetadata().getPayload().get(ChromaStore.PAYLOAD_RECORD_ID);
        assertThat(recordId).as("命中项必须携带 Chroma 真实记录 id").isNotNull();

        // 用命中项携带的真实记录 id 删除，而不是 Document.getId()。
        client.delete(collectionId, List.of(String.valueOf(recordId)));
        assertThat(client.count(collectionId)).isZero();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = API_KEY_ENV, matches = ".+")
    void simpleKnowledge能通过OpenAITextEmbedding走真实嵌入网关() {
        // 只有在凭据存在时才走真实嵌入路径：AgentScope 的 OpenAITextEmbedding
        // 会把 baseUrl 指向本工程的嵌入网关，用同一套凭据与模型，
        // 因此这一段验证的是「AgentScope 的 EmbeddingModel 适配 ↔ 本工程网关」的对接。
        EmbeddingProperties embeddingProps = new EmbeddingProperties();
        embeddingProps.setApiKey(System.getenv(API_KEY_ENV));

        OpenAITextEmbedding embeddingModel = OpenAITextEmbedding.builder()
            .apiKey(embeddingProps.getApiKey())
            .modelName(embeddingProps.getModel())
            .dimensions(embeddingProps.getDimensions())
            .baseUrl(embeddingProps.getBaseUrl())
            .build();

        // 真实嵌入是 1024 维，而本测试集合是 4 维，两者不能混用；
        // 因此另建一个 1024 维的集合承载真实嵌入的记录。
        ChromaProperties props = new ChromaProperties();
        props.setTenant("default_tenant");
        props.setDatabase("default_database");
        props.setKnowledgeCollection(COLLECTION_NAME + "_real");

        ChromaClient realClient = new ChromaClient(props, java.net.http.HttpClient.newHttpClient(),
            new com.fasterxml.jackson.databind.ObjectMapper());
        ChromaStore realStore = new ChromaStore(realClient, new EmbeddingService() {
            @Override
            public List<double[]> embed(List<String> texts) {
                throw new UnsupportedOperationException("本测试只走 AgentScope 的 EmbeddingModel 路径");
            }
        }, props);
        String realCollectionId = realClient.createCollection(COLLECTION_NAME + "_real");

        SimpleKnowledge knowledge = SimpleKnowledge.builder()
            .embeddingModel(embeddingModel)
            .embeddingStore(realStore)
            .build();

        // 先经 AgentScope 的 addDocuments 写入：它会调用 EmbeddingModel 得到真实向量。
        DocumentMetadata metadata = DocumentMetadata.builder()
            .content(TextBlock.builder().text("真实嵌入网关写入的记录").build())
            .docId("it-real-doc")
            .chunkId(RECORD_PREFIX + "real")
            .addPayload("file_name", "手册.txt")
            .build();
        Document realDocument = new Document(metadata);
        knowledge.addDocuments(List.of(realDocument)).block();

        assertThat(realClient.count(realCollectionId)).isEqualTo(1L);

        // 再经 AgentScope 的 retrieve 检索回来：查询向量同样来自真实网关。
        List<Document> hits = knowledge
            .retrieve("真实嵌入网关",
                RetrieveConfig.builder().limit(3).scoreThreshold(SCORE_THRESHOLD).build())
            .block();

        assertThat(hits).isNotNull();
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getMetadata().getContentText()).contains("真实嵌入网关");

        // 清理自己写入的记录（记录 id 是 AgentScope 的 Document.getId()）。
        realClient.delete(realCollectionId, List.of(realDocument.getId()));
        assertThat(realClient.count(realCollectionId)).isZero();
    }
}
