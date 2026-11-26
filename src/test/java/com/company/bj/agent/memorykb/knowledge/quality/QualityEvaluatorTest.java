package com.company.bj.agent.memorykb.knowledge.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.knowledge.FilterCondition;
import com.company.bj.agent.memorykb.knowledge.FilterLogic;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.chunk.Chunker;
import com.company.bj.agent.memorykb.knowledge.chunk.RecursiveCharacterChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.SourceTypeRoutingChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.TableRowChunker;
import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParserRegistry;
import com.company.bj.agent.memorykb.knowledge.parse.ExcelDocumentParser;
import com.company.bj.agent.memorykb.knowledge.parse.TxtDocumentParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link QualityEvaluator} 的召回率/延迟统计与 {@link QualityGate} 的门禁判定。
 *
 * <p>本测试同时守住本任务最关键的一条不变式：
 * <b>评测集里引用的每一个切片标识，都必须真实存在于评测语料的切片结果中</b>。
 * 标识一旦对不上，召回率就不是"检索质量"而是"噪声比"，门禁随之失去意义 ——
 * 因此该不变式由 {@link #评测集引用的每个切片标识都真实存在于语料切片中()} 直接断言。
 */
class QualityEvaluatorTest {

    /** 单片字符上限，与全局约束一致。 */
    private static final int MAX_CHUNK_SIZE = 600;

    /** 相邻切片重叠字符数，与全局约束一致。 */
    private static final int OVERLAP = 90;

    /** 评测集文件路径，相对模块目录解析。 */
    private static final Path EVAL_QUERIES = Path.of("eval/queries.jsonl");

    /** 评测语料目录，相对模块目录解析。 */
    private static final Path CORPUS_DIR = Path.of("eval/corpus");

    /** 文本语料文件名，也是该文档的 documentId 前缀。 */
    private static final String TXT_FILE_NAME = "边检业务手册.txt";

    /** 表格语料文件名，也是该文档的 documentId 前缀。 */
    private static final String XLSX_FILE_NAME = "设备巡检记录.xlsx";

    @Test
    void 召回率按命中数除以应命中数计算() {
        // stub 检索器：第 1 条查询命中 2 条（期望 3 条），第 2 条查询命中 1 条（期望 1 条）。
        StubRetriever retriever = new StubRetriever();
        retriever.hits.put("旅客怎么通关", List.of("doc.txt#0", "doc.txt#1"));
        retriever.hits.put("行李怎么检查", List.of("doc.txt#7"));

        QualityEvaluator evaluator = new QualityEvaluator(retriever);
        RetrievalQualityReport report = evaluator.evaluate(List.of(
            new QualityEvaluator.EvaluationQuery("旅客怎么通关", Set.of("doc.txt#0", "doc.txt#1", "doc.txt#2")),
            new QualityEvaluator.EvaluationQuery("行李怎么检查", Set.of("doc.txt#7"))));

        // 总命中 3 / 总应命中 4 = 0.75
        assertThat(report.recallAt10()).isEqualTo(0.75);
        assertThat(report.queryCount()).isEqualTo(2);
        assertThat(report.hitCount()).isEqualTo(3);
        assertThat(report.expectedCount()).isEqualTo(4);
        assertThat(report.details()).hasSize(2);
        assertThat(report.details().get(0).recall()).isEqualTo(2.0 / 3.0);
        assertThat(report.details().get(1).recall()).isEqualTo(1.0);
    }

    @Test
    void 检索命中为空时召回率为零且不抛异常() {
        StubRetriever retriever = new StubRetriever();
        retriever.hits.put("查不到的查询", List.of());

        QualityEvaluator evaluator = new QualityEvaluator(retriever);
        RetrievalQualityReport report = evaluator.evaluate(List.of(
            new QualityEvaluator.EvaluationQuery("查不到的查询", Set.of("doc.txt#0"))));

        assertThat(report.recallAt10()).isZero();
        assertThat(report.hitCount()).isZero();
        assertThat(report.expectedCount()).isEqualTo(1);
    }

    @Test
    void 分位数计算正确() {
        // 在**已知样本**上精确断言分位数公式：索引取法一旦取错
        // （例如误写成 floor），P95 会静默偏小，门禁随之失去对尾部延迟的约束力。
        // 走真实计时得到的样本带毫秒级抖动，无法验证索引取法是否正确，
        // 因此分位数公式单独在确定样本上验证。
        List<Double> samples = List.of(50.0, 10.0, 30.0, 20.0, 40.0);

        // 中位数：奇数个样本取正中值 → 30
        assertThat(QualityEvaluator.median(samples)).isEqualTo(30.0);
        // p95：升序排序后取索引 ceil(0.95 * 5) - 1 = 4 → 最大值 50
        assertThat(QualityEvaluator.percentile95(samples)).isEqualTo(50.0);
    }

    @Test
    void 偶数个样本的中位数为中间两数平均() {
        List<Double> samples = List.of(40.0, 10.0, 30.0, 20.0);

        // 中位数：偶数个样本取中间两数平均 → (20 + 30) / 2 = 25
        assertThat(QualityEvaluator.median(samples)).isEqualTo(25.0);
        // p95：ceil(0.95 * 4) - 1 = 3 → 最大值 40
        assertThat(QualityEvaluator.percentile95(samples)).isEqualTo(40.0);
    }

    @Test
    void 分位数在样本数为一与为空时的取值() {
        assertThat(QualityEvaluator.median(List.of(7.0))).isEqualTo(7.0);
        assertThat(QualityEvaluator.percentile95(List.of(7.0))).isEqualTo(7.0);
        assertThat(QualityEvaluator.median(List.of())).isZero();
        assertThat(QualityEvaluator.percentile95(List.of())).isZero();
    }

    @Test
    void 评测报告的延迟统计来自真实计时() {
        // 端到端计时必须落在真实耗时区间内：既不能是 0（说明没量到检索），
        // 也不能远大于桩制造的耗时（说明把别的开销算进来了）。
        StubRetriever retriever = new StubRetriever();
        retriever.latencies.add(40.0);
        retriever.hits.put("查询0", List.of("doc.txt#0"));

        RetrievalQualityReport report = new QualityEvaluator(retriever).evaluate(List.of(
            new QualityEvaluator.EvaluationQuery("查询0", Set.of("doc.txt#0"))));

        assertThat(report.medianLatencyMs()).isGreaterThanOrEqualTo(40.0);
        assertThat(report.medianLatencyMs()).isLessThan(1000.0);
        assertThat(report.p95LatencyMs()).isEqualTo(report.medianLatencyMs());
    }

    @Test
    void 门禁常量固定() {
        assertThat(QualityGate.MIN_RECALL).isEqualTo(0.60);
        assertThat(QualityGate.MAX_P95_MS).isEqualTo(100.0);
    }

    @Test
    void 延迟门禁判的是向量库查询而非端到端耗时() {
        // 端到端 172 ms（远程嵌入往返 162 ms + 向量库 10 ms）：
        // 向量库这一段达标，端到端不达标。
        // 门禁必须按向量库这一段判定 ——
        // 否则这条线就成了一条指向外部服务、本工程无法改动、
        // 也无法通过的假约束。
        RetrievalQualityReport report = new RetrievalQualityReport(
            1.00, 1, 1, 1,
            172.0,   // medianLatencyMs（端到端）
            172.0,   // p95LatencyMs（端到端）
            10.0,    // searchP95LatencyMs（仅向量库查询）
            List.of());

        assertThat(QualityGate.latencyPassed(report))
            .as("向量库查询 10ms 达标，延迟门禁应通过")
            .isTrue();
        // 端到端数字照常保留在报告里 —— 判定与呈现分开。
        assertThat(report.p95LatencyMs()).isEqualTo(172.0);
    }

    @Test
    void 向量库查询超限时延迟门禁不通过() {
        RetrievalQualityReport report = new RetrievalQualityReport(
            1.00, 1, 1, 1,
            172.0, 172.0,
            150.0,  // 向量库查询本身就超了 100ms
            List.of());

        assertThat(QualityGate.latencyPassed(report))
            .as("向量库查询 150ms 超过门禁线，延迟门禁应不通过")
            .isFalse();
    }

    @Test
    void 门禁按召回率与延迟判定通过与否() {
        RetrievalQualityReport passed = report(0.80, 20.0);
        assertThat(QualityGate.recallPassed(passed)).isTrue();
        assertThat(QualityGate.latencyPassed(passed)).isTrue();

        RetrievalQualityReport lowRecall = report(0.50, 20.0);
        assertThat(QualityGate.recallPassed(lowRecall)).isFalse();
        assertThat(QualityGate.latencyPassed(lowRecall)).isTrue();

        RetrievalQualityReport slow = report(0.80, 150.0);
        assertThat(QualityGate.recallPassed(slow)).isTrue();
        assertThat(QualityGate.latencyPassed(slow)).isFalse();
    }

    @Test
    void 门禁在阈值边界上判定为通过() {
        // 恰好落在门禁线上必须**通过**：门禁的语义是"不低于 0.60"与"不高于 100ms"，
        // 即闭区间。若有人把 >= 改成 > 或把 <= 改成 <，边界样本就会翻转为未通过。
        RetrievalQualityReport boundary = report(0.60, 100.0);

        assertThat(QualityGate.recallPassed(boundary))
            .as("Recall@10 恰好等于门禁线 0.60 时必须通过")
            .isTrue();
        assertThat(QualityGate.latencyPassed(boundary))
            .as("P95 恰好等于门禁线 100.0ms 时必须通过")
            .isTrue();
    }

    @Test
    void 桩段与真实段使用不同的评测集合() {
        // Chroma 集合的向量维度在首次写入时绑定且不可变，而 createCollection 不传维度。
        // 两段若共用一个集合，先跑的那段会把维度钉死（桩段 256 维 / 真实段 1024 维），
        // 另一段必然撞上维度冲突，且冲突与否取决于 JUnit 的方法执行顺序。
        // 这条断言把"两段物理隔离"钉住，防止将来有人把集合名"顺手统一"回去。
        assertThat(QualityEvaluationIT.STUB_COLLECTION_NAME)
            .as("桩段与真实段必须使用不同的集合名，否则集合维度会被先跑的那段钉死")
            .isNotEqualTo(QualityEvaluationIT.REAL_COLLECTION_NAME);
    }

    @Test
    void 评测集可加载且非空() {
        List<QualityEvaluator.EvaluationQuery> queries = QualityEvaluator.loadQueries(EVAL_QUERIES);

        assertThat(queries).hasSizeGreaterThanOrEqualTo(20);
        assertThat(queries).allSatisfy(query -> assertThat(query.expectedChunkIds()).isNotEmpty());
        assertThat(queries).allSatisfy(query -> assertThat(query.query()).isNotBlank());
    }

    @Test
    void 评测集引用的每个切片标识都真实存在于语料切片中() throws Exception {
        // 这是本任务最关键的不变式：评测集里的标识必须来自**真实切片**，
        // 而不是凭规则猜出来的。对不上时召回率没有意义，门禁也就无从谈起。
        Set<String> actualChunkIds = chunkIdsOfCorpus();
        List<QualityEvaluator.EvaluationQuery> queries = QualityEvaluator.loadQueries(EVAL_QUERIES);

        Set<String> missing = new LinkedHashSet<>();
        for (QualityEvaluator.EvaluationQuery query : queries) {
            for (String expectedId : query.expectedChunkIds()) {
                if (!actualChunkIds.contains(expectedId)) {
                    missing.add(expectedId);
                }
            }
        }

        assertThat(missing)
            .as("评测集引用了语料切片结果中不存在的标识")
            .isEmpty();
        assertThat(actualChunkIds).as("语料切片结果不应为空").isNotEmpty();
    }

    @Test
    void 评测集覆盖两个语料文档() {
        List<QualityEvaluator.EvaluationQuery> queries = QualityEvaluator.loadQueries(EVAL_QUERIES);

        assertThat(queries).anySatisfy(query -> assertThat(query.expectedChunkIds())
            .anyMatch(id -> id.startsWith(TXT_FILE_NAME + "#")));
        assertThat(queries).anySatisfy(query -> assertThat(query.expectedChunkIds())
            .anyMatch(id -> id.startsWith(XLSX_FILE_NAME + "#")));
    }

    @Test
    void 评测集不存在时抛出明确异常() {
        assertThatThrownBy(() -> QualityEvaluator.loadQueries(Path.of("eval/不存在的评测集.jsonl")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不存在的评测集.jsonl");
    }

    @Test
    void 评测集行内容非法时抛出明确异常() throws Exception {
        Path invalid = Files.createTempFile("queries-invalid", ".jsonl");
        try {
            Files.writeString(invalid, "{\"query\":\"缺少 expected 字段\"}\n", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> QualityEvaluator.loadQueries(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected");
        } finally {
            Files.deleteIfExists(invalid);
        }
    }

    /**
     * 构造一份用于门禁判定的评测报告。
     *
     * <p><b>分段延迟与端到端延迟取同一个值</b>：本构造器服务于
     * 「门禁常��」这类不涉及分段语义的断言，
     * 让两个口径一致才能断言门禁的判定结果。
     * 需要区分两段的用例请直接构造报告。
     *
     * @param recall    召回率
     * @param p95       P95 延迟
     * @return 评测报告
     */
    private RetrievalQualityReport report(double recall, double p95) {
        return new RetrievalQualityReport(recall, 1, (int) Math.round(recall), 1, p95, p95, p95, List.of());
    }

    /**
     * 用<b>真实的切片器</b>切分评测语料，收集全部切片标识。
     *
     * <p>切片标识只由「文档标识 + 切片序号」决定，与嵌入无关，
     * 因此该结果在有/无 api key 的环境下都成立。
     *
     * @return 语料切片标识集合
     * @throws IOException 读取语料文件失败时抛出
     */
    private Set<String> chunkIdsOfCorpus() throws IOException {
        DocumentParserRegistry parsers =
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser()));
        Chunker chunker = new SourceTypeRoutingChunker(
            new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP),
            new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP));

        Set<String> chunkIds = new LinkedHashSet<>();
        for (String fileName : List.of(TXT_FILE_NAME, XLSX_FILE_NAME)) {
            Path file = CORPUS_DIR.resolve(fileName);
            assertThat(Files.exists(file)).as("评测语料文件应存在: " + file).isTrue();
            byte[] bytes = Files.readAllBytes(file);
            // documentId 约定为原始文件名（含扩展名），与摄取管线的调用方约定一致。
            List<Chunk> chunks = chunker.chunk(parsers.parserFor(fileName).parse(fileName, fileName, bytes));
            assertThat(chunks).as("语料应产出切片: " + fileName).isNotEmpty();
            for (Chunk chunk : chunks) {
                chunkIds.add(chunk.chunkId());
            }
        }
        return chunkIds;
    }

    /**
     * 桩检索器：按查询文本返回预设命中，并在检索过程中睡出预设延迟。
     */
    private static final class StubRetriever implements KnowledgeRetriever {

        /** 查询文本 → 命中的切片标识列表。 */
        private final Map<String, List<String>> hits = new java.util.LinkedHashMap<>();

        /** 各次调用的延迟（毫秒）；为空时由桩自行产生固定延迟。 */
        private final List<Double> latencies = new ArrayList<>();

        /** 已发生的调用次数。 */
        private int callCount;

        @Override
        public List<RetrievedChunk> retrieve(String query, List<FilterCondition> filters,
            FilterLogic logic, int topK, Double scoreThreshold) {
            // 延迟必须发生在检索"内部"：评测器度量的正是 retrieve 调用本身，
            // 若把 sleep 放在调用之外，评测器量到的将是接近 0 的耗时。
            sleep(callCount < latencies.size() ? latencies.get(callCount) : 1.0);
            callCount++;

            List<String> ids = hits.getOrDefault(query, List.of());
            List<RetrievedChunk> result = new ArrayList<>(ids.size());
            for (String id : ids) {
                result.add(new RetrievedChunk(id, "正文", 1.0, Map.of()));
            }
            return result;
        }

        /**
         * 等待指定毫秒数，用于制造可预期的延迟样本。
         *
         * @param millis 等待毫秒数
         */
        private void sleep(double millis) {
            long deadline = System.nanoTime() + (long) (millis * 1_000_000L);
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }
}
