package com.company.bj.agent.memorykb.knowledge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.knowledge.ChromaKnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.IngestionService;
import com.company.bj.agent.memorykb.knowledge.chunk.RecursiveCharacterChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.SourceTypeRoutingChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.TableRowChunker;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingNormalizer;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.company.bj.agent.memorykb.knowledge.embed.OpenAiCompatibleEmbeddingService;
import com.company.bj.agent.memorykb.knowledge.model.IngestionResult;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParserRegistry;
import com.company.bj.agent.memorykb.knowledge.parse.ExcelDocumentParser;
import com.company.bj.agent.memorykb.knowledge.parse.TxtDocumentParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 端到端质量评测集成测试。
 *
 * <p>把评测语料摄到<b>真实本地 Chroma 服务</b>，跑完整的
 * 「摄取 → 检索 → 统计 → 门禁判定」链路，输出召回率、P95 与门禁判定。
 *
 * <p><b>两段式验证</b>（与 {@code SimpleKnowledgeWiringIT} 同一约定）：
 * <ol>
 *   <li><b>确定性桩嵌入链路</b>（只需 {@code CHROMA_IT=true}）：
 *       用本地确定性嵌入模型驱动整条链路，真实访问 Chroma 服务。
 *       这一段验证的是<b>评测装置本身</b>是否正确 ——
 *       评测集能否被完整摄取、检索能否命中预期切片、
 *       统计与门禁是否按预期工作。</li>
 *   <li><b>真实嵌入链路</b>（额外需要 {@code DASHSCOPE_API_KEY}）：
 *       用真实嵌入模型重跑同一套评测，产出<b>可用于验收的真实召回率</b>。</li>
 * </ol>
 *
 * <p>两段都<b>不会静默通过</b>：真实嵌入段的环境变量缺失时整段被显式跳过，
 * 跳过情况在报告中说明，不冒充"通过"。
 *
 * <p><b>关于桩嵌入的召回率</b>：本地桩嵌入是「字符哈希袋」，
 * 它衡量的是<b>词面重叠</b>而非语义相似度。评测集的查询文本与目标切片
 * 都取自语料原文，因此桩嵌入能取得可观召回 —— 但这**不能**替代真实嵌入的语义召回。
 * 真实召回率必须以第二段（真实嵌入）的结果为准。
 *
 * <p><b>两段使用物理隔离的集合</b>（{@link #STUB_COLLECTION_NAME} 与
 * {@link #REAL_COLLECTION_NAME}）：Chroma 集合的向量维度在<b>首次写入时绑定且不可变</b>，
 * 而 {@code ChromaClient.createCollection} 只发 {@code name} + {@code get_or_create}、不传维度，
 * 因此桩段（256 维）先跑会把集合钉死在 256 维，真实段（1024 维）再写就会撞上
 * {@code Collection expecting embedding with dimension of 256, got 1024}。
 * 分成两个集合后，两段<b>互不影响</b>，与 JUnit 的执行顺序无关 ——
 * 不依赖"真实段先跑"这种假设。
 */
@EnabledIfEnvironmentVariable(named = "CHROMA_IT", matches = "true")
class QualityEvaluationIT {

    /**
     * 桩嵌入段专用的集合名，满足 3-512 长度与 [a-zA-Z0-9._-] 字符集规则。
     *
     * <p><b>该集合被桩嵌入（256 维）绑定后维度不可变</b>，因此真实嵌入段
     * <b>不得</b>复用它 —— 见 {@link #REAL_COLLECTION_NAME}。
     */
    static final String STUB_COLLECTION_NAME = "it_quality_eval";

    /**
     * 真实嵌入段专用的集合名，与桩段物理隔离。
     *
     * <p>真实嵌入的向量维度是 1024，与桩段的 256 不同。若两段共用一个集合，
     * 先跑的那段会把集合维度钉死，另一段必然因维度冲突失败，
     * 且失败与否取决于 JUnit 的方法执行顺序（未配置固定顺序）。
     */
    static final String REAL_COLLECTION_NAME = "it_quality_eval_real";

    /** 单片字符上限，与全局约束一致。 */
    private static final int MAX_CHUNK_SIZE = 600;

    /** 相邻切片重叠字符数，与全局约束一致。 */
    private static final int OVERLAP = 90;

    /** 桩嵌入模型的向量维度，必须与 Chroma 集合维度一致。 */
    private static final int STUB_EMBEDDING_DIMENSIONS = 256;

    /** 真实嵌入网关的凭据环境变量名。 */
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    /**
     * 真实嵌入段报告的标记行。
     *
     * <p>验收脚本按此标记把「真实段」与「桩段」的输出区分开：有凭据时只采信标记之后的第一份报告，
     * 从而不会出现"脚本取到先打印的桩段报告、把词面重叠召回率当成验收结论"的问题。
     */
    static final String REAL_REPORT_MARKER = "== REAL EMBEDDING REPORT ==";

    /**
     * 桩嵌入段报告的标记行。
     *
     * <p>与 {@link #REAL_REPORT_MARKER} 成对出现，让脚本能明确区分两段的来源。
     */
    static final String STUB_REPORT_MARKER = "== STUB EMBEDDING REPORT ==";

    /** 评测语料目录，相对模块目录解析。 */
    private static final Path CORPUS_DIR = Path.of("eval/corpus");

    /** 评测集文件路径，相对模块目录解析。 */
    private static final Path EVAL_QUERIES = Path.of("eval/queries.jsonl");

    /** 评测语料文件名列表，顺序决定摄取顺序。 */
    private static final List<String> CORPUS_FILES = List.of("边检业务手册.txt", "设备巡检记录.xlsx");

    private ChromaClient client;
    private String collectionId;

    @BeforeEach
    void 准备集合() {
        // 两段必须用不同的集合：Chroma 集合维度在首次写入时绑定且不可变，
        // 共用集合时先跑的那段会把维度钉死，另一段必然因维度冲突失败。
        // 这条断言让"集合被合并回去"这类改动在测试阶段就暴露，而不是等到有凭据时才炸。
        assertThat(STUB_COLLECTION_NAME)
            .as("桩段与真实段必须使用不同的集合名（否则集合维度会被先跑的那段钉死）")
            .isNotEqualTo(REAL_COLLECTION_NAME);

        client = new ChromaClient(chromaProperties(STUB_COLLECTION_NAME), HttpClient.newHttpClient(), new ObjectMapper());
        collectionId = client.createCollection(STUB_COLLECTION_NAME);
    }

    @Test
    void 确定性桩嵌入下评测链路可完整跑通() throws IOException {
        // 1. 摄取评测语料到真实 Chroma。
        IngestionService ingestionService = ingestionService(stubEmbeddings());
        List<String> ingestedIds = new ArrayList<>();
        for (String fileName : CORPUS_FILES) {
            Path file = CORPUS_DIR.resolve(fileName);
            assertThat(Files.exists(file)).as("评测语料应存在: " + file).isTrue();
            byte[] bytes = readCorpus(file);
            // documentId 传原始文件名（含扩展名），与评测集里的切片标识前缀一致。
            IngestionResult result = ingestionService.ingest(fileName, fileName, bytes, Map.of());
            assertThat(result.chunkCount()).as("语料应产出切片: " + fileName).isPositive();
            ingestedIds.addAll(result.chunkIds());
        }

        // 2. 语料确实落到了服务端。
        assertThat(client.count(collectionId)).isEqualTo(ingestedIds.size());

        // 3. 评测集引用的标识必须全部在已摄取的切片中 ——
        //    否则召回率是拿不存在的标识去比对的噪声。
        List<QualityEvaluator.EvaluationQuery> queries = QualityEvaluator.loadQueries(EVAL_QUERIES);
        assertThat(queries).hasSizeGreaterThanOrEqualTo(20);
        for (QualityEvaluator.EvaluationQuery query : queries) {
            assertThat(ingestedIds).as("评测集引用了未摄取的切片: " + query.expectedChunkIds())
                .containsAll(query.expectedChunkIds());
        }

        // 4. 跑评测并打印门禁判定。
        RetrievalQualityReport report = evaluate(queries);
        // 桩段打印自己的标记，脚本据此确认"本次采信的是桩段"，
        // 并在有凭据时优先去找真实段而不是这份。
        printReport(report, STUB_REPORT_MARKER);
        // 桩结果必须在**任何**输出里显式标注为词面重叠 —— 验收脚本会原样回显这段标记。
        System.out.println("[桩嵌入说明] 本段召回率由确定性字符哈希嵌入算出，"
            + "衡量的是词面重叠，不是语义召回，不能作为真实召回的验收结论。");

        // 5. 桩嵌入走的是词面重叠，语料与查询文本取自同一批原文，
        //    召回必须显著高于门禁线 —— 否则说明评测装置本身有问题。
        assertThat(report.recallAt10())
            .as("桩嵌入下召回率应显著高于门禁线")
            .isGreaterThan(QualityGate.MIN_RECALL);
        assertThat(report.p95LatencyMs())
            .as("本地 Chroma 的 P95 延迟应远低于门禁线")
            .isLessThan(QualityGate.MAX_P95_MS);

        // 6. 清理自己写入的记录，集合内记录数归零。
        client.delete(collectionId, ingestedIds);
        assertThat(client.count(collectionId)).isZero();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = API_KEY_ENV, matches = ".+")
    void 真实嵌入下产出验收用召回率() throws IOException {
        // 真实嵌入网关的凭据存在时才跑这一段：产出的召回率才是**验收依据**。
        // 写入侧与查询侧必须用**同一个**嵌入服务 ——
        // 两边模型不同会让查询向量与库内向量不在同一空间，召回率没有意义。
        EmbeddingProperties embeddingProps = new EmbeddingProperties();
        embeddingProps.setApiKey(System.getenv(API_KEY_ENV));

        // 真实段用**自己的**集合：桩段会把集合维度钉死在 256，
        // 真实段是 1024 维，共用必然撞维度冲突，且冲突与否取决于执行顺序。
        ChromaProperties props = chromaProperties(REAL_COLLECTION_NAME);
        EmbeddingService realEmbeddings = new OpenAiCompatibleEmbeddingService(
            embeddingProps, HttpClient.newHttpClient(), new ObjectMapper());

        ChromaClient realClient = new ChromaClient(props, HttpClient.newHttpClient(), new ObjectMapper());
        String realCollectionId = realClient.createCollection(REAL_COLLECTION_NAME);

        IngestionService realIngestionService = new IngestionService(
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser())),
            new SourceTypeRoutingChunker(
                new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP),
                new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP)),
            realEmbeddings,
            realClient,
            props,
            embeddingProps);

        List<String> ingestedIds = new ArrayList<>();
        for (String fileName : CORPUS_FILES) {
            IngestionResult result = realIngestionService.ingest(
                fileName, fileName, readCorpus(CORPUS_DIR.resolve(fileName)), Map.of());
            ingestedIds.addAll(result.chunkIds());
        }

        RetrievalQualityReport report = new QualityEvaluator(
            new ChromaKnowledgeRetriever(realClient, realEmbeddings, props))
            .evaluate(QualityEvaluator.loadQueries(EVAL_QUERIES));
        // 打印带标记的报告：验收脚本据此把「真实段」与「桩段」的输出区分开，
        // 避免有凭据时把先打印的桩段报告误当成验收结论。
        printReport(report, REAL_REPORT_MARKER);
        System.out.println("[真实嵌入说明] 本段召回率由真实嵌入模型算出，"
            + "是本次运行的验收依据。");

        assertThat(QualityGate.recallPassed(report))
            .as("真实嵌入下 Recall@10 应不低于 " + QualityGate.MIN_RECALL)
            .isTrue();
        assertThat(QualityGate.latencyPassed(report))
            .as("真实嵌入下 P95 应不高于 " + QualityGate.MAX_P95_MS + "ms")
            .isTrue();

        realClient.delete(realCollectionId, ingestedIds);
        assertThat(realClient.count(realCollectionId)).isZero();
    }

    /**
     * 组装摄取服务。
     *
     * @param embeddings 嵌入服务
     * @return 摄取服务
     */
    private IngestionService ingestionService(EmbeddingService embeddings) {
        ChromaProperties props = chromaProperties(STUB_COLLECTION_NAME);

        EmbeddingProperties embeddingProps = new EmbeddingProperties();
        // 桩嵌入的维度是 256，因此这里的期望维度必须与之一致。
        embeddingProps.setDimensions(STUB_EMBEDDING_DIMENSIONS);

        return new IngestionService(
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser())),
            new SourceTypeRoutingChunker(
                new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP),
                new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP)),
            embeddings,
            client,
            props,
            embeddingProps);
    }

    /**
     * 构造指向指定集合的 Chroma 配置。
     *
     * @param collectionName 集合名
     * @return Chroma 配置
     */
    private ChromaProperties chromaProperties(String collectionName) {
        ChromaProperties props = new ChromaProperties();
        props.setTenant("default_tenant");
        props.setDatabase("default_database");
        props.setKnowledgeCollection(collectionName);
        return props;
    }

    /**
     * 用桩嵌入执行评测。
     *
     * @param queries 评测查询
     * @return 评测报告
     */
    private RetrievalQualityReport evaluate(List<QualityEvaluator.EvaluationQuery> queries) {
        return new QualityEvaluator(
            new ChromaKnowledgeRetriever(client, stubEmbeddings(), chromaProperties(STUB_COLLECTION_NAME)))
            .evaluate(queries);
    }

    /**
     * 返回确定性的本地嵌入模型：把文本的字符哈希累加到一个固定维度的向量。
     *
     * <p>这是<b>词面</b>相似度而非语义相似度，用途是验证评测装置本身。
     *
     * @return 桩嵌入服务
     */
    private static EmbeddingService stubEmbeddings() {
        return new EmbeddingService() {
            @Override
            public List<double[]> embed(List<String> texts) {
                List<double[]> vectors = new ArrayList<>(texts.size());
                for (String text : texts) {
                    vectors.add(vectorOf(text));
                }
                return vectors;
            }

            /**
             * 把文本的字符哈希累加为向量。
             *
             * @param text 文本
             * @return 单位长度向量
             */
            private double[] vectorOf(String text) {
                double[] vector = new double[STUB_EMBEDDING_DIMENSIONS];
                for (int i = 0; i < text.length(); i++) {
                    int bucket = Math.floorMod(text.charAt(i) * 31 + i, STUB_EMBEDDING_DIMENSIONS);
                    vector[bucket] += 1.0;
                }
                return EmbeddingNormalizer.normalize(vector);
            }
        };
    }

    /**
     * 读取语料文件。
     *
     * @param file 文件路径
     * @return 文件字节
     * @throws IOException 读取失败时抛出
     */
    private byte[] readCorpus(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    /**
     * 打印评测报告与门禁判定。
     *
     * @param report 评测报告
     * @param marker 段落标记行，用于让验收脚本区分本段是桩嵌入还是真实嵌入
     */
    private void printReport(RetrievalQualityReport report, String marker) {
        System.out.println(marker);
        System.out.println("========== 检索质量评测报告 ==========");
        System.out.printf("查询数        : %d%n", report.queryCount());
        System.out.printf("命中/应命中   : %d / %d%n", report.hitCount(), report.expectedCount());
        System.out.printf("Recall@10     : %.4f (门禁线 %.2f -> %s)%n",
            report.recallAt10(), QualityGate.MIN_RECALL,
            QualityGate.recallPassed(report) ? "通过" : "未通过");
        System.out.printf("中位延迟(ms)  : %.2f  (端到端，含嵌入)%n", report.medianLatencyMs());
        System.out.printf("P95 延迟(ms)  : %.2f  (端到端，含嵌入)%n", report.p95LatencyMs());
        // 分段延迟并列打印：延迟超标时必须能看出瓶颈在哪一段 ——
        // 「端到端 172ms」既不能说明检索慢，也不能说明检索快。
        System.out.printf("P95 向量库(ms): %.2f  (仅检索，门禁线 %.0f -> %s)%n",
            report.searchP95LatencyMs(), QualityGate.MAX_P95_MS,
            QualityGate.latencyPassed(report) ? "通过" : "未通过");
        System.out.println("------------------------------------------");
        for (RetrievalQualityReport.QueryDetail detail : report.details()) {
            System.out.printf("  recall=%.2f  latency=%.2fms  search=%.2fms  query=%s%n",
                detail.recall(), detail.latencyMs(), detail.searchMs(), detail.query());
        }
        System.out.println("==========================================");
    }
}
