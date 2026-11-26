package com.company.bj.agent.memorykb.knowledge;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.chroma.ChromaCollectionName;
import com.company.bj.agent.memorykb.chroma.ChromaGetResult;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.web.dto.DocumentSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档清单服务。
 *
 * <p><b>为什么不另建文档表</b>：文档的唯一事实来源是向量库里的切片记录。
 * 若额外维护一张文档表，就会出现两处状态需要同步 ——
 * 上传成功但写表失败、或删表成功但向量没删干净，两种不一致都很难察觉。
 * 代价是本类需要读取全量切片的元数据来聚合成文档清单，
 * 在文档数量很大时这一步会变慢；当前数据量小，这个代价可接受。
 *
 * <p><b>聚合规则</b>：按 {@code document_id} 分组，组内切片数即该文档的切片数，
 * 文件名取该组中出现次数最多的 {@code file_name}
 * （取众数而非首条，是为了让「同组元数据不一致」时的展示更接近多数事实）。
 *
 * <p><b>读取方式</b>：调用 {@link ChromaClient#getByWhere} 时<b>不传 {@code ids}</b> ——
 * 服务端把「缺省 {@code ids}」解释为"读取全部"，而 {@code ids: []} 会被解释为"一条也不要"，
 * 返回空列表（已对 live Chroma v2 实测确认）。
 */
public class KnowledgeCatalogService {

    /** 元数据键：文档标识。 */
    private static final String METADATA_DOCUMENT_ID = IngestionService.METADATA_DOCUMENT_ID;

    /** 元数据键：文件名。 */
    private static final String METADATA_FILE_NAME = IngestionService.METADATA_FILE_NAME;

    /** Chroma where 表达式中的等值操作符。 */
    private static final String WHERE_EQUALS = "$eq";

    /** 读取时需要回传的字段：元数据。 */
    private static final List<String> INCLUDE_METADATAS = List.of("metadatas");

    private final ChromaClient chroma;
    private final ChromaProperties props;

    /**
     * 构造文档清单服务。
     *
     * @param chroma Chroma 客户端
     * @param props  Chroma 配置，提供知识库集合名
     */
    public KnowledgeCatalogService(ChromaClient chroma, ChromaProperties props) {
        this.chroma = chroma;
        this.props = props;
    }

    /**
     * 列出知识库中的全部文档。
     *
     * @return 文档摘要列表，按文档标识升序排列
     * @throws com.company.bj.agent.memorykb.chroma.ChromaException Chroma 服务不可用时抛出
     */
    public List<DocumentSummary> listDocuments() {
        ChromaGetResult records = chroma.getByWhere(resolveCollectionId(), null, null, null,
            INCLUDE_METADATAS);
        return aggregate(records);
    }

    /**
     * 删除一个文档的全部切片。
     *
     * <p><b>物理删除</b>：知识库文档是用户显式删除的内容，与长期记忆的
     * 「软删除（归档）」语义不同 —— 后者需要可追溯与可恢复，
     * 前者则是用户明确表达"这份内容不要了"。
     *
     * @param documentId 文档标识
     * @return 实际删除的切片数，文档不存在时为 0
     * @throws com.company.bj.agent.memorykb.chroma.ChromaException Chroma 服务不可用时抛出
     */
    public int deleteDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        String collectionId = resolveCollectionId();
        // 先数出该文档的切片数用于回报，再按过滤条件物理删除。
        ChromaGetResult records = chroma.getByWhere(collectionId,
            equalityWhere(METADATA_DOCUMENT_ID, documentId), null, null, INCLUDE_METADATAS);
        int chunkCount = records.ids().size();
        if (chunkCount == 0) {
            return 0;
        }
        chroma.deleteByWhere(collectionId, equalityWhere(METADATA_DOCUMENT_ID, documentId));
        return chunkCount;
    }

    /**
     * 把切片记录聚合为文档摘要。
     *
     * @param records 切片记录
     * @return 文档摘要列表，按文档标识升序排列
     */
    private List<DocumentSummary> aggregate(ChromaGetResult records) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> metadata : records.metadatas()) {
            Object documentId = metadata.get(METADATA_DOCUMENT_ID);
            if (documentId == null) {
                // 没有文档标识的记录无法归属到某个文档，跳过而不是造一个空标识的条目。
                continue;
            }
            grouped.computeIfAbsent(documentId.toString(), key -> new ArrayList<>()).add(metadata);
        }

        List<DocumentSummary> summaries = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            summaries.add(new DocumentSummary(entry.getKey(),
                dominantFileName(entry.getValue()), entry.getValue().size()));
        }
        summaries.sort((left, right) -> left.documentId().compareTo(right.documentId()));
        return summaries;
    }

    /**
     * 取出切片组中出现次数最多的文件名。
     *
     * @param metadatas 同一文档的切片元数据
     * @return 文件名，元数据里没有该字段时退回文档标识本身
     */
    private String dominantFileName(List<Map<String, Object>> metadatas) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Map<String, Object> metadata : metadatas) {
            Object fileName = metadata.get(METADATA_FILE_NAME);
            if (fileName != null) {
                counter.merge(fileName.toString(), 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best == null ? "" : best;
    }

    /**
     * 构造单字段等值的 where 表达式。
     *
     * @param field 元数据字段名
     * @param value 期望值
     * @return where 表达式
     */
    private Map<String, Object> equalityWhere(String field, String value) {
        Map<String, Object> comparison = new LinkedHashMap<>(1);
        comparison.put(WHERE_EQUALS, value);
        Map<String, Object> where = new LinkedHashMap<>(1);
        where.put(field, comparison);
        return where;
    }

    /**
     * 解析出知识库集合 id。
     *
     * @return 集合 id
     * @throws IllegalArgumentException 集合名非法时抛出
     */
    private String resolveCollectionId() {
        String collectionName = ChromaCollectionName.validate(props.getKnowledgeCollection());
        return chroma.createCollection(collectionName);
    }
}
