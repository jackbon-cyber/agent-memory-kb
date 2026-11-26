package com.company.bj.agent.memorykb.chroma;

import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingNormalizer;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Chroma 存储的 AgentScope {@code VDBStoreBase} 适配器。
 *
 * <p>让 AgentScope 原生的 {@code SimpleKnowledge} → {@code KnowledgeRetrievalTools}
 * 链路可以直接把本工程的 Chroma 集合当作向量库挂载，无需改上游任何类。
 *
 * <p><b>字段映射</b>：
 * <ul>
 *   <li>AgentScope {@link Document#getEmbedding()} → Chroma 的 {@code embeddings}；</li>
 *   <li>{@code Document.getMetadata().getContentText()} → Chroma 的 {@code documents}；</li>
 *   <li>{@code Document.getMetadata().getPayload()} → Chroma 的 {@code metadatas}；</li>
 *   <li>{@code Document.getId()} → Chroma 的记录 {@code ids}。</li>
 * </ul>
 *
 * <p><b>没有 where 过滤能力</b>：{@link SearchDocumentDto} 只有 {@code queryEmbedding} /
 * {@code limit} / {@code scoreThreshold} / {@code vectorName} 四个字段，
 * 协议层面无处放元数据过滤条件。这是 AgentScope SPI 的固有限制
 * （见 design.md 决策 D2），带过滤的检索由
 * {@code com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever} 提供。
 *
 * <p><b>向量归一化</b>：本适配器是 AgentScope 的向量进入本工程 Chroma 集合的<b>唯一关口</b>，
 * 因此写入与查询两侧的向量都在此归一化到单位长度
 * （见 {@link EmbeddingNormalizer}）。这一点不可省略 ——
 * AgentScope 的 {@code EmbeddingModel}（如 {@code OpenAITextEmbedding}）
 * 产出的向量<b>不经过</b>本工程的 {@code OpenAiCompatibleEmbeddingService}，
 * 若此处不归一化，集合里就会混入尺度不一致的向量，
 * 平方 L2 距离随之失去意义、得分失去区分度。
 *
 * <p><b>得分方向</b>：Chroma 返回的是<b>平方</b> L2 距离（越小越相近），
 * 本适配器按 {@code 1.0 / (1.0 + distance)} 换算成得分（越大越相关），
 * 与 AgentScope {@code scoreThreshold} 的语义方向保持一致。
 * 得分换算由 {@link ChromaQueryResultReader} 承担，与检索端口共用同一份实现。
 *
 * <p><b>命中项的记录 id</b>：AgentScope 的 {@code Document.getId()} 是从
 * {@code docId + chunkId + content} 派生的，<b>不等于</b> Chroma 的记录 id，
 * 因此真实记录 id 被显式放在返回文档的 payload 的 {@value #PAYLOAD_RECORD_ID} 键下。
 * 对命中项执行删除/更新时必须使用它，否则会因 id 不一致而静默无效。
 */
public class ChromaStore implements VDBStoreBase {

    /** 检索时需要回传的字段：正文、元数据与距离。 */
    private static final List<String> INCLUDE_FIELDS = List.of("documents", "metadatas", "distances");

    /** 入库元数据键：文档标识，与摄取服务写入的键名一致。 */
    private static final String METADATA_DOCUMENT_ID = "document_id";

    /** 入库元数据键：切片标识，与摄取服务写入的键名一致。 */
    private static final String METADATA_CHUNK_ID = "chunk_id";

    /** 返回文档的 payload 键：Chroma 的真实记录 id。 */
    public static final String PAYLOAD_RECORD_ID = "record_id";

    private final ChromaClient chroma;
    private final EmbeddingService embeddings;
    private final ChromaProperties props;

    /**
     * 构造 Chroma 存储适配器。
     *
     * @param chroma     Chroma 客户端
     * @param embeddings 嵌入服务，用于在文档缺少向量时补齐
     * @param props      Chroma 配置，提供知识库集合名
     */
    public ChromaStore(ChromaClient chroma, EmbeddingService embeddings, ChromaProperties props) {
        this.chroma = chroma;
        this.embeddings = embeddings;
        this.props = props;
    }

    @Override
    public Mono<Void> add(List<Document> documents) {
        return Mono.fromCallable(() -> {
            if (documents == null || documents.isEmpty()) {
                return null;
            }
            upsert(documents);
            return null;
        });
    }

    @Override
    public Mono<List<Document>> search(SearchDocumentDto dto) {
        return Mono.fromCallable(() -> {
            double[] queryEmbedding = dto.getQueryEmbedding();
            if (queryEmbedding == null || queryEmbedding.length == 0) {
                throw new IllegalArgumentException("search requires a non-empty query embedding");
            }
            String collectionId = resolveCollectionId();
            ChromaQueryResult result = chroma.query(collectionId,
                EmbeddingNormalizer.normalize(queryEmbedding), dto.getLimit(), null, INCLUDE_FIELDS);
            return toDocuments(result, dto.getScoreThreshold());
        });
    }

    @Override
    public Mono<Boolean> delete(String id) {
        return Mono.fromCallable(() -> {
            if (id == null || id.isBlank()) {
                return Boolean.FALSE;
            }
            chroma.delete(resolveCollectionId(), List.of(id));
            return Boolean.TRUE;
        });
    }

    /**
     * 把 AgentScope 文档批量写入 Chroma 集合。
     *
     * <p>一律走 {@code upsert}：同 id 的记录被覆盖、不存在的被新增，
     * 重复写入不会让集合记录数增长。
     *
     * @param documents 待写入的文档
     */
    private void upsert(List<Document> documents) {
        List<String> ids = new ArrayList<>(documents.size());
        List<double[]> vectors = new ArrayList<>(documents.size());
        List<String> contents = new ArrayList<>(documents.size());
        List<Map<String, Object>> metadatas = new ArrayList<>(documents.size());

        for (Document document : documents) {
            ids.add(document.getId());
            vectors.add(EmbeddingNormalizer.normalize(resolveEmbedding(document)));
            contents.add(document.getMetadata().getContentText());
            Map<String, Object> payload = document.getMetadata().getPayload();
            metadatas.add(payload == null ? Map.of() : payload);
        }

        chroma.upsert(resolveCollectionId(), ids, vectors, contents, metadatas);
    }

    /**
     * 取出文档的向量，缺失时用嵌入服务补齐。
     *
     * <p>AgentScope 的 {@code SimpleKnowledge.addDocuments} 会先为文档设置向量，
     * 但直接构造的 {@link Document} 可能没有向量；缺向量直接入库会得到
     * "已入库却永远检索不到"的记录，因此在入库前补齐。
     *
     * @param document 文档
     * @return 文档向量（尚未归一化，由调用方统一归一化）
     */
    private double[] resolveEmbedding(Document document) {
        double[] embedding = document.getEmbedding();
        if (embedding != null && embedding.length > 0) {
            return embedding;
        }
        return embeddings.embed(document.getMetadata().getContentText());
    }

    /**
     * 把 Chroma 的检索结果转换成 AgentScope 文档列表。
     *
     * @param result         Chroma 检索结果
     * @param scoreThreshold 相似度下限，为 {@code null} 时不施加下限
     * @return 命中文档列表，按相似度降序排列
     */
    private List<Document> toDocuments(ChromaQueryResult result, Double scoreThreshold) {
        List<Document> documents = new ArrayList<>();
        int hitCount = ChromaQueryResultReader.hitCount(result);
        for (int i = 0; i < hitCount; i++) {
            double score = ChromaQueryResultReader.toScore(ChromaQueryResultReader.distanceAt(result, i));
            if (scoreThreshold != null && score < scoreThreshold) {
                continue;
            }
            documents.add(toDocument(result, i, score));
        }
        documents.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        return documents;
    }

    /**
     * 把一条命中记录转换成 AgentScope 文档。
     *
     * <p><b>标识语义</b>：AgentScope 的 {@link Document} 只接受 {@link DocumentMetadata}
     * 作为构造入参，而 {@code Document.getId()} 是从 {@code docId + chunkId + content}
     * 确定性派生的（{@code UUID.nameUUIDFromBytes(json{doc_id, chunk_id, content})}），
     * <b>并不等于</b> Chroma 的记录 id。因此：
     * <ul>
     *   <li>{@code chunkId} 承载<b>原始 chunkId</b>（入库时写入的 {@code chunk_id} 元数据），
     *       使 {@code docId} / {@code chunkId} 的语义与 AgentScope 一致；</li>
     *   <li>Chroma 的<b>真实记录 id</b> 显式保留在 payload 的 {@code record_id} 键下，
     *       调用方据此才能对命中项执行删除/更新 ——
     *       用 {@code Document.getId()} 去删会因 id 不一致而<b>静默无效</b>。</li>
     * </ul>
     *
     * @param result Chroma 检索结果
     * @param index  命中下标
     * @param score  相似度得分
     * @return AgentScope 文档
     */
    private Document toDocument(ChromaQueryResult result, int index, double score) {
        String recordId = ChromaQueryResultReader.idAt(result, index);
        String content = ChromaQueryResultReader.documentAt(result, index);
        Map<String, Object> metadata = ChromaQueryResultReader.metadataAt(result, index);

        Map<String, Object> payload = new LinkedHashMap<>(metadata);
        payload.put(PAYLOAD_RECORD_ID, recordId);

        DocumentMetadata documentMetadata = DocumentMetadata.builder()
            .content(TextBlock.builder().text(content).build())
            .docId(resolveDocId(metadata, recordId))
            .chunkId(resolveChunkId(metadata, recordId))
            .payload(payload)
            .build();

        Document document = new Document(documentMetadata);
        document.setScore(score);
        return document;
    }

    /**
     * 解析出所属文档标识。
     *
     * <p>入库元数据里带有 {@code document_id} 时优先采用它，
     * 这样 AgentScope 侧的 {@code docId} 与本工程摄取时的文档标识保持一致；
     * 缺失时退回记录 id，保证元信息不为空。
     *
     * @param metadata 命中记录的元数据
     * @param recordId 命中记录的 Chroma 记录 id
     * @return 所属文档标识
     */
    private String resolveDocId(Map<String, Object> metadata, String recordId) {
        Object documentId = metadata.get(METADATA_DOCUMENT_ID);
        if (documentId instanceof String value && !value.isBlank()) {
            return value;
        }
        return recordId == null ? "" : recordId;
    }

    /**
     * 解析出切片标识。
     *
     * <p>入库元数据里带有 {@code chunk_id} 时采用它 ——
     * 那才是调用方在写入时使用的原始切片标识；
     * 缺失时退回 Chroma 记录 id，至少保证非空。
     *
     * @param metadata 命中记录的元数据
     * @param recordId 命中记录的 Chroma 记录 id
     * @return 切片标识
     */
    private String resolveChunkId(Map<String, Object> metadata, String recordId) {
        Object chunkId = metadata.get(METADATA_CHUNK_ID);
        if (chunkId instanceof String value && !value.isBlank()) {
            return value;
        }
        return recordId == null ? "" : recordId;
    }

    /**
     * 解析出知识库集合 id。
     *
     * <p>配置里给的是集合<b>名</b>，而 Chroma 的接口需要集合 <b>id</b>，
     * 两者不能混用；此处先校验集合名（非法名在发起任何请求前即被拒绝），
     * 再通过创建接口拿到 id（该接口是幂等的，同名集合复用同一 id）。
     *
     * @return 集合 id
     * @throws IllegalArgumentException 集合名非法时抛出
     */
    private String resolveCollectionId() {
        String collectionName = ChromaCollectionName.validate(props.getKnowledgeCollection());
        return chroma.createCollection(collectionName);
    }
}
