package com.company.bj.agent.memorykb.knowledge;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.chroma.ChromaCollectionName;
import com.company.bj.agent.memorykb.chroma.ChromaQueryResult;
import com.company.bj.agent.memorykb.chroma.ChromaQueryResultReader;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Chroma 的知识库检索实现。
 *
 * <p>把"查询文本 + 元数据过滤"翻译成一次 Chroma 向量检索：
 * <ol>
 *   <li>把查询文本嵌入为查询向量；</li>
 *   <li>把过滤条件翻译成 Chroma 的 {@code where} 表达式；</li>
 *   <li>发起检索并把距离换算成相似度得分。</li>
 * </ol>
 *
 * <p><b>距离到得分的换算</b>：Chroma 默认使用 L2 空间，{@code /query} 返回的
 * {@code distances} 是<b>平方</b> L2 距离（已对 live Chroma 实测确认），
 * 本实现按 {@code score = 1.0 / (1.0 + distance)} 换算，使得分<b>越大越相关</b>，
 * 与 AgentScope {@code scoreThreshold} 的语义方向一致。
 *
 * <p>由于写入与查询两侧的向量都是<b>单位向量</b>
 * （归一化由 {@code OpenAiCompatibleEmbeddingService} 这一唯一向量产出点保证），
 * 单位向量之间 {@code squared_L2 = 2 - 2·cos}，距离落在 {@code [0, 4]}，
 * 得分落在 {@code [0.2, 1.0]}，且与余弦相似度单调对应。
 *
 * <p>结果解析与得分换算由 {@link ChromaQueryResultReader} 承担，
 * 与 {@code ChromaStore} 共用同一份实现，得分公式因此只存在一处。
 *
 * <p><b>查询向量不缓存</b>：缓存由上游的 {@code CachedEmbeddingService} 负责，
 * 本类只保证检索语义正确，避免同一份缓存逻辑出现两份实现。
 */
public class ChromaKnowledgeRetriever implements TimedKnowledgeRetriever {

    /** 检索时需要回传的字段：正文、元数据与距离。 */
    private static final List<String> INCLUDE_FIELDS = List.of("documents", "metadatas", "distances");

    private final ChromaClient chroma;
    private final EmbeddingService embeddings;
    private final ChromaProperties props;

    /**
     * 构造检索器。
     *
     * @param chroma     Chroma 客户端
     * @param embeddings 嵌入服务，用于把查询文本转换为查询向量
     * @param props      Chroma 配置，提供知识库集合名
     */
    public ChromaKnowledgeRetriever(ChromaClient chroma, EmbeddingService embeddings, ChromaProperties props) {
        this.chroma = chroma;
        this.embeddings = embeddings;
        this.props = props;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, List<FilterCondition> filters,
        FilterLogic logic, int topK, Double scoreThreshold) {
        return retrieveTimed(query, filters, logic, topK, scoreThreshold).chunks();
    }

    @Override
    public TimedRetrieval retrieveTimed(String query, List<FilterCondition> filters,
        FilterLogic logic, int topK, Double scoreThreshold) {
        // 分段计时的意义在于归因：把「远程嵌入往返」与「本地向量查询」分开，
        // 才能在 P95 超标时判断瓶颈究竟在哪一段。
        // 两段都必须真实计时 —— 合成一个数字只会得到「172ms」这种无法行动的结论。
        long embeddingStart = System.nanoTime();
        double[] queryVector = embeddings.embed(query);
        double embeddingMs = elapsedMillis(embeddingStart);

        String collectionId = resolveCollectionId();
        Map<String, Object> where = buildWhere(filters, logic);

        long searchStart = System.nanoTime();
        ChromaQueryResult result = chroma.query(collectionId, queryVector, topK, where, INCLUDE_FIELDS);
        double searchMs = elapsedMillis(searchStart);

        List<RetrievedChunk> chunks = toRetrievedChunks(result, scoreThreshold);
        return new TimedRetrieval(chunks, new RetrievalTimings(embeddingMs, searchMs));
    }

    /**
     * 计算从起点到现在的耗时（毫秒）。
     *
     * @param startNanos 起始时刻的 {@code System.nanoTime()} 值
     * @return 耗时（毫秒）
     */
    private static double elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    /**
     * 把过滤条件翻译成 Chroma 的 {@code where} 表达式。
     *
     * <p>0 个条件返回 {@code null}（调用方据此完全省略 {@code where} 字段）；
     * 1 个条件直接用该条件的表达式；多个条件按 {@link FilterLogic} 组装成
     * {@code {"$and": [...]}} 或 {@code {"$or": [...]}}。
     *
     * @param filters 过滤条件列表
     * @param logic   多条件的组合逻辑
     * @return where 表达式，无条件时为 {@code null}
     * @throws IllegalArgumentException 条件本身非法（操作符不支持、取值无法解析）时抛出
     */
    private Map<String, Object> buildWhere(List<FilterCondition> filters, FilterLogic logic) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0).toWhere();
        }
        FilterLogic effectiveLogic = logic == null ? FilterLogic.AND : logic;
        List<Map<String, Object>> conditions = new ArrayList<>(filters.size());
        for (FilterCondition condition : filters) {
            conditions.add(condition.toWhere());
        }
        Map<String, Object> where = new LinkedHashMap<>(1);
        where.put(effectiveLogic.whereKey(), conditions);
        return where;
    }

    /**
     * 把 Chroma 的检索结果转换成带得分的命中列表。
     *
     * @param result         Chroma 检索结果
     * @param scoreThreshold 相似度下限，为 {@code null} 时不施加下限
     * @return 命中列表，按相似度降序排列
     */
    private List<RetrievedChunk> toRetrievedChunks(ChromaQueryResult result, Double scoreThreshold) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        int hitCount = ChromaQueryResultReader.hitCount(result);
        for (int i = 0; i < hitCount; i++) {
            double score = ChromaQueryResultReader.toScore(ChromaQueryResultReader.distanceAt(result, i));
            if (scoreThreshold != null && score < scoreThreshold) {
                continue;
            }
            chunks.add(new RetrievedChunk(
                ChromaQueryResultReader.idAt(result, i),
                ChromaQueryResultReader.documentAt(result, i),
                score,
                ChromaQueryResultReader.metadataAt(result, i)));
        }
        chunks.sort((left, right) -> Double.compare(right.score(), left.score()));
        return chunks;
    }

    /**
     * 解析出知识库集合 id。
     *
     * <p>配置里给的是集合<b>名</b>，而 Chroma 的检索接口需要集合 <b>id</b>，
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
