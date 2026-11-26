package com.company.bj.agent.memorykb.knowledge.quality;

import com.company.bj.agent.memorykb.knowledge.FilterLogic;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.TimedKnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索质量评测器。
 *
 * <p>对一组「查询 → 应命中切片标识」的评测集跑检索，产出
 * {@link RetrievalQualityReport}（Recall@10 与延迟分布）。
 *
 * <p><b>评测口径</b>：
 * <ul>
 *   <li>每条查询取 top-10 命中、无元数据过滤、不设相似度下限
 *       （过滤会掩盖召回问题，而下限在本工程是"打分换算后的可选阈值"，
 *       用它筛掉的结果无法反映"该召回的有没有被召回"）；</li>
 *   <li>单条查询召回率 = 命中数 ÷ 该查询的应命中数；
 *       整体 Recall@10 = 总命中数 ÷ 总应命中数（加权口径）；</li>
 *   <li>延迟用 {@code System.nanoTime()} 度量，<b>含嵌入</b> ——
 *       门禁以端到端耗时为准，避免用查询向量缓存掩盖真实开销。</li>
 * </ul>
 *
 * <p><b>评测集的切片标识必须真实存在</b>：标识由「文档标识 + 切片序号」确定性派生，
 * 与嵌入无关，因此评测集可以离线编写；但编写时必须以<b>真实切片结果</b>为依据，
 * 凭空猜测的标识会让召回率变成噪声比。本工程由
 * {@code QualityEvaluatorTest.评测集引用的每个切片标识都真实存在于语料切片中()} 守住该不变式。
 */
public class QualityEvaluator {

    /** 每次评测的检索条数上限。 */
    public static final int TOP_K = 10;

    /** P95 分位点。 */
    private static final double P95_QUANTILE = 0.95;

    /** 评测集 JSON 字段名：查询文本。 */
    private static final String FIELD_QUERY = "query";

    /** 评测集 JSON 字段名：应命中切片标识列表。 */
    private static final String FIELD_EXPECTED = "expected";

    /** 评测集每行的行号（用于错误信息定位），从 1 起。 */
    private static final int FIRST_LINE_NUMBER = 1;

    private final KnowledgeRetriever retriever;

    /**
     * 构造评测器。
     *
     * @param retriever 检索端口，用于执行评测查询
     */
    public QualityEvaluator(KnowledgeRetriever retriever) {
        if (retriever == null) {
            throw new IllegalArgumentException("retriever must not be null");
        }
        this.retriever = retriever;
    }

    /**
     * 对评测集执行一次完整评测。
     *
     * @param queries 评测查询列表
     * @return 评测报告
     * @throws IllegalArgumentException 评测查询为 {@code null} 时抛出
     */
    public RetrievalQualityReport evaluate(List<EvaluationQuery> queries) {
        if (queries == null) {
            throw new IllegalArgumentException("queries must not be null");
        }

        List<RetrievalQualityReport.QueryDetail> details = new ArrayList<>(queries.size());
        List<Double> latencies = new ArrayList<>(queries.size());
        List<Double> searchLatencies = new ArrayList<>(queries.size());
        int totalHit = 0;
        int totalExpected = 0;

        for (EvaluationQuery query : queries) {
            // 检索与计时在同一次调用里完成：若先检索再单独计时，
            // 计时区间会漏掉真实的检索开销，甚至变成"只测了第二次调用"。
            long start = System.nanoTime();
            RetrievedChunk[] hits = retrieve(query.query());
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

            int hitCount = countHits(hits, query.expectedChunkIds());

            totalHit += hitCount;
            totalExpected += query.expectedChunkIds().size();
            latencies.add(latencyMs);
            // 分段耗时：检索器支持分段计量时才有值，否则记为 0。
            double searchMs = lastSearchMs;
            searchLatencies.add(searchMs);
            details.add(new RetrievalQualityReport.QueryDetail(
                query.query(),
                hitCount,
                query.expectedChunkIds().size(),
                recallOf(hitCount, query.expectedChunkIds().size()),
                latencyMs,
                searchMs));
        }

        double recallAt10 = recallOf(totalHit, totalExpected);
        return new RetrievalQualityReport(
            recallAt10,
            queries.size(),
            totalHit,
            totalExpected,
            median(latencies),
            percentile95(latencies),
            percentile95(searchLatencies),
            details);
    }

    /**
     * 最近一次检索的「向量库查询」分段耗时。
     *
     * <p><b>用字段而不是返回值传递</b>是权宜之计：{@code retrieve} 的返回类型
     * 由 {@link KnowledgeRetriever} 端口固定，不能为了分段数据改端口签名。
     * 端口是跨实现共享的契约，改它会波及所有调用方；
     * 而分段耗时只是评测报告的补充信息。
     */
    private double lastSearchMs;

    /**
     * 执行一次检索。
     *
     * @param query 查询文本
     * @return 命中的切片列表
     */
    private RetrievedChunk[] retrieve(String query) {
        List<RetrievedChunk> hits;
        if (retriever instanceof TimedKnowledgeRetriever timed) {
            // 支持分段计量的检索器：拿到「向量库查询」这一段的耗时。
            TimedKnowledgeRetriever.TimedRetrieval timedRetrieval =
                timed.retrieveTimed(query, List.of(), FilterLogic.AND, TOP_K, null);
            hits = timedRetrieval.chunks();
            lastSearchMs = timedRetrieval.timings().searchMs();
        } else {
            // 不支持分段计量：只能给出端到端耗时，分段值记 0。
            hits = retriever.retrieve(query, List.of(), FilterLogic.AND, TOP_K, null);
            lastSearchMs = 0.0;
        }
        return hits == null ? new RetrievedChunk[0] : hits.toArray(new RetrievedChunk[0]);
    }

    /**
     * 统计命中数：返回的切片标识与应命中集合的交集大小。
     *
     * @param hits            命中的切片
     * @param expectedChunkIds 应命中切片标识集合
     * @return 命中数
     */
    private int countHits(RetrievedChunk[] hits, Set<String> expectedChunkIds) {
        int hitCount = 0;
        for (RetrievedChunk hit : hits) {
            if (hit != null && hit.chunkId() != null && expectedChunkIds.contains(hit.chunkId())) {
                hitCount++;
            }
        }
        return hitCount;
    }

    /**
     * 计算召回率，分母为 0 时返回 0。
     *
     * @param hitCount      命中数
     * @param expectedCount 应命中数
     * @return 召回率，取值落在 [0, 1]
     */
    private double recallOf(int hitCount, int expectedCount) {
        if (expectedCount <= 0) {
            return 0.0;
        }
        return (double) hitCount / expectedCount;
    }

    /**
     * 计算中位数：奇数个样本取正中值，偶数个样本取中间两数平均。
     *
     * @param samples 延迟样本
     * @return 中位数（毫秒），无样本时返回 0
     */
    static double median(List<Double> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    /**
     * 计算 P95：样本升序排序后取索引 {@code ceil(0.95 * n) - 1}。
     *
     * <p>包级可见（而非 private），是为了让分位数公式能在<b>已知样本</b>上被精确断言 ——
     * 走真实计时得到的样本带毫秒级抖动，无法验证"索引取法是否正确"；
     * 而索引取法一旦取错（比如误写成 {@code floor}），P95 会静默偏小，
     * 门禁随之失去对尾部延迟的约束力。
     *
     * @param samples 延迟样本
     * @return P95（毫秒），无样本时返回 0
     */
    static double percentile95(List<Double> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(P95_QUANTILE * sorted.size()) - 1;
        // 样本数为 0 的分支已在上方排除；此处仅防御下标越界。
        int safeIndex = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(safeIndex);
    }

    /**
     * 从 JSONL 文件加载评测集。
     *
     * <p>每行一个 JSON 对象：{@code {"query":"...","expected":["id1","id2"]}}。
     * 空行与以 {@code #} 开头的行被忽略，便于在文件里写注释。
     *
     * @param jsonl JSONL 文件路径
     * @return 评测查询列表，保持文件中的顺序
     * @throws IllegalStateException 文件不存在或读取失败时抛出
     * @throws IllegalArgumentException 某行内容非法时抛出，消息中含行号
     */
    public static List<EvaluationQuery> loadQueries(Path jsonl) {
        if (jsonl == null) {
            throw new IllegalArgumentException("jsonl path must not be null");
        }
        if (!Files.exists(jsonl)) {
            throw new IllegalStateException("evaluation query file does not exist: " + jsonl);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read evaluation query file: " + jsonl, e);
        }

        ObjectMapper mapper = new ObjectMapper();
        List<EvaluationQuery> queries = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            queries.add(parseQuery(mapper, line, i + FIRST_LINE_NUMBER));
        }
        return queries;
    }

    /**
     * 解析评测集的一行。
     *
     * @param mapper     JSON 解析器
     * @param line       行内容
     * @param lineNumber 行号（从 1 起），用于错误信息定位
     * @return 评测查询
     * @throws IllegalArgumentException 行内容非法时抛出，消息中含行号
     */
    private static EvaluationQuery parseQuery(ObjectMapper mapper, String line, int lineNumber) {
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "invalid evaluation query json at line " + lineNumber + ": " + line, e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("evaluation query at line " + lineNumber + " is not a json object");
        }

        JsonNode queryNode = node.get(FIELD_QUERY);
        if (queryNode == null || !queryNode.isTextual() || queryNode.asText().isBlank()) {
            throw new IllegalArgumentException(
                "evaluation query at line " + lineNumber + " has no non-blank '" + FIELD_QUERY + "' field");
        }

        JsonNode expectedNode = node.get(FIELD_EXPECTED);
        if (expectedNode == null || !expectedNode.isArray() || expectedNode.isEmpty()) {
            throw new IllegalArgumentException(
                "evaluation query at line " + lineNumber + " has no non-empty '" + FIELD_EXPECTED + "' array");
        }

        Set<String> expectedChunkIds = new LinkedHashSet<>();
        for (JsonNode item : expectedNode) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(
                    "evaluation query at line " + lineNumber + " has a non-textual '" + FIELD_EXPECTED + "' entry");
            }
            expectedChunkIds.add(item.asText());
        }

        return new EvaluationQuery(queryNode.asText(), expectedChunkIds);
    }

    /**
     * 单条评测查询。
     *
     * @param query           查询文本
     * @param expectedChunkIds 应命中的切片标识集合
     */
    public record EvaluationQuery(String query, Set<String> expectedChunkIds) {

        /**
         * 规范化应命中标识集合，保证下游拿到的集合永不为 {@code null} 且不可变。
         */
        public EvaluationQuery {
            expectedChunkIds = expectedChunkIds == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(expectedChunkIds));
        }
    }
}
