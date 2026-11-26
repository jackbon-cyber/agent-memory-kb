package com.company.bj.agent.memorykb.knowledge;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.chroma.ChromaCollectionName;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.knowledge.chunk.Chunker;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingException;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.company.bj.agent.memorykb.knowledge.model.Chunk;
import com.company.bj.agent.memorykb.knowledge.model.IngestionResult;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParser;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParserRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档摄取管线。
 *
 * <p>把「解析 → 切片 → 嵌入 → 入库」串成一条链路：上传的文档经解析器得到结构化文本，
 * 经切片器得到带字符区间的切片，为每片生成向量后连同完整元数据写入 Chroma 集合。
 *
 * <p><b>元数据的两段式来源</b>：切片自带的 {@code metadata} 只承载解析期信息
 * （{@code section_title} / {@code table_name} / {@code row_number}），
 * 入库前的完整元数据由本类重新组装（{@code document_id} / {@code file_name} /
 * {@code source_type} / {@code sequence} / {@code start_offset} / {@code end_offset} /
 * {@code ingested_at} 以及调用方附加的自定义字段），
 * 两段来源不累加同一字段，避免同一语义出现两个值。
 *
 * <p><b>幂等性</b>：切片标识由 {@code documentId + "#" + sequence} 确定性派生，
 * 入库一律走 {@link ChromaClient#upsert} —— 已存在的标识被覆盖、不存在的被新增，
 * 因此重复摄取同一文档不会让集合记录数增长。
 *
 * <p><b>失败语义</b>：嵌入失败时整条链路失败，不会先写入无向量的记录，
 * 保证"已入库"永远等价于"已有向量可检索"。
 *
 * <p><b>维度闸门</b>：入库前校验向量维度与配置声明的期望维度一致。
 * Chroma 集合是维度绑定的，维度不符的数据写进去要到检索时才暴露，
 * 因此管线自身持有这道闸门，不依赖具体嵌入实现是否校验过。
 */
public class IngestionService {

    /** 入库元数据键：文档标识。 */
    public static final String METADATA_DOCUMENT_ID = "document_id";

    /** 入库元数据键：文件名。 */
    public static final String METADATA_FILE_NAME = "file_name";

    /** 入库元数据键：来源类型。 */
    public static final String METADATA_SOURCE_TYPE = "source_type";

    /** 入库元数据键：切片序号。 */
    public static final String METADATA_SEQUENCE = "sequence";

    /** 入库元数据键：切片在原文中的起始偏移。 */
    public static final String METADATA_START_OFFSET = "start_offset";

    /** 入库元数据键：切片在原文中的结束偏移。 */
    public static final String METADATA_END_OFFSET = "end_offset";

    /** 入库元数据键：入库时间，ISO-8601 格式。 */
    public static final String METADATA_INGESTED_AT = "ingested_at";

    private final DocumentParserRegistry parsers;
    private final Chunker chunker;
    private final EmbeddingService embeddings;
    private final ChromaClient chroma;
    private final ChromaProperties props;
    private final EmbeddingProperties embeddingProps;

    /**
     * 构造摄取服务。
     *
     * @param parsers        解析器注册表，用于按文件名选择解析器
     * @param chunker        切片器，负责把解析后的文档切成切片
     * @param embeddings     嵌入服务，负责为切片生成向量
     * @param chroma         Chroma 客户端，负责写入向量库
     * @param props          Chroma 配置，提供知识库集合名
     * @param embeddingProps 嵌入模型配置，提供期望的向量维度
     */
    public IngestionService(DocumentParserRegistry parsers, Chunker chunker, EmbeddingService embeddings,
        ChromaClient chroma, ChromaProperties props, EmbeddingProperties embeddingProps) {
        this.parsers = parsers;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.chroma = chroma;
        this.props = props;
        this.embeddingProps = embeddingProps;
    }

    /**
     * 摄取一份文档。
     *
     * @param documentId    文档标识，约定为原始文件名（含扩展名），不做任何规范化
     * @param fileName      原始文件名，用于选择解析器
     * @param bytes         文件原始字节
     * @param extraMetadata 调用方附加的自定义元数据，与基础字段同名时以调用方取值优先；
     *                      为 {@code null} 时视为无附加字段
     * @return 摄取结果
     * @throws IllegalArgumentException 没有解析器支持该文件名时抛出
     * @throws IllegalStateException       文档解析后没有产出任何切片时抛出
     * @throws EmbeddingException          嵌入失败或向量维度不符时抛出
     */
    public IngestionResult ingest(String documentId, String fileName, byte[] bytes,
        Map<String, Object> extraMetadata) {
        ParsedDocument parsed = parse(documentId, fileName, bytes);
        List<Chunk> chunks = chunker.chunk(parsed);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("document '" + documentId + "' produced no chunks, refusing to ingest");
        }

        List<double[]> vectors = embeddings.embed(contents(chunks));
        validateVectors(chunks.size(), vectors);

        String collectionId = resolveCollectionId();
        List<String> ids = new ArrayList<>(chunks.size());
        List<String> contents = new ArrayList<>(chunks.size());
        List<Map<String, Object>> metadatas = new ArrayList<>(chunks.size());
        String ingestedAt = Instant.now().toString();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            ids.add(chunk.chunkId());
            contents.add(chunk.content());
            metadatas.add(buildMetadata(chunk, ingestedAt, extraMetadata));
        }

        chroma.upsert(collectionId, ids, vectors, contents, metadatas);
        return new IngestionResult(documentId, chunks.size(), chunks.size(), ids);
    }

    /**
     * 校验嵌入结果的数量与维度。
     *
     * <p>数量或维度对不上时必须拒绝入库 —— 否则向量库里会留下"部分切片没有向量"
     * 或"同一集合里向量维度不一致"的畸形记录，而这类数据在检索时才暴露，
     * 定位成本远高于在摄取阶段拦下。
     *
     * <p><b>维度闸门</b>：除各向量维度互相一致外，还要与配置声明的期望维度
     * （{@code memorykb.embedding.dimensions}，默认 1024）一致。
     * Chroma 集合是<b>维度绑定</b>的，若集合按 1024 建好而写入 768 维向量，
     * 问题要到检索时才以服务端维度错误的形式暴露。因此管线自身持有一道闸门，
     * 不依赖具体的嵌入实现是否校验过 —— 换了实现或改错了配置都能被挡住。
     *
     * @param chunkCount 切片数量
     * @param vectors    嵌入结果
     * @throws EmbeddingException 数量不符、存在空向量、维度不一致或与期望维度不符时抛出
     */
    private void validateVectors(int chunkCount, List<double[]> vectors) {
        if (vectors == null || vectors.size() != chunkCount) {
            throw new EmbeddingException("embedding returned " + (vectors == null ? 0 : vectors.size())
                + " vectors but " + chunkCount + " chunks were produced");
        }
        int expected = embeddingProps.getDimensions();
        int dimension = -1;
        for (int i = 0; i < vectors.size(); i++) {
            double[] vector = vectors.get(i);
            if (vector == null || vector.length == 0) {
                throw new EmbeddingException("embedding returned an empty vector at position " + i);
            }
            if (dimension == -1) {
                dimension = vector.length;
            } else if (vector.length != dimension) {
                throw new EmbeddingException("embedding returned inconsistent vector dimensions: "
                    + dimension + " and " + vector.length);
            }
            if (vector.length != expected) {
                throw new EmbeddingException("embedding dimension mismatch: expected " + expected
                    + " but got " + vector.length);
            }
        }
    }

    /**
     * 解析文档字节为结构化文档。
     *
     * @param documentId 文档标识
     * @param fileName   原始文件名
     * @param bytes      文件原始字节
     * @return 解析后的文档
     * @throws IllegalArgumentException 没有解析器支持该文件名时抛出
     * @throws IllegalStateException       解析过程发生 IO 异常时抛出
     */
    private ParsedDocument parse(String documentId, String fileName, byte[] bytes) {
        DocumentParser parser = parsers.parserFor(fileName);
        try {
            return parser.parse(documentId, fileName, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse document '" + documentId + "'", e);
        }
    }

    /**
     * 解析出切片正文列表。
     *
     * @param chunks 切片列表
     * @return 切片正文列表
     */
    private List<String> contents(List<Chunk> chunks) {
        List<String> contents = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            contents.add(chunk.content());
        }
        return contents;
    }

    /**
     * 解析出知识库集合 id。
     *
     * <p>配置里给的是集合<b>名</b>，而 Chroma 的写入接口需要集合 <b>id</b>，
     * 两者不能混用；此处先校验集合名（非法名在发起任何请求前即被拒绝），
     * 再通过创建接口拿到 id。
     *
     * @return 集合 id
     * @throws IllegalArgumentException 集合名非法时抛出
     */
    private String resolveCollectionId() {
        String collectionName = ChromaCollectionName.validate(props.getKnowledgeCollection());
        return chroma.createCollection(collectionName);
    }

    /**
     * 组装单条切片入库时的完整元数据。
     *
     * <p>字段来源有三处，且<b>互不重复</b>：
     * <ol>
     *   <li>基础字段：文档标识、文件名、来源类型、序号、字符区间、入库时间；</li>
     *   <li>解析期字段：切片自带的 {@code metadata}（标题、工作表名、行号）；</li>
     *   <li>调用方自定义字段：与基础字段同名时以调用方取值优先。</li>
     * </ol>
     *
     * @param chunk         切片
     * @param ingestedAt    本次摄取的入库时间，ISO-8601 字符串
     * @param extraMetadata 调用方附加的自定义元数据
     * @return 入库元数据
     */
    private Map<String, Object> buildMetadata(Chunk chunk, String ingestedAt, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_DOCUMENT_ID, chunk.documentId());
        metadata.put(METADATA_FILE_NAME, chunk.fileName());
        metadata.put(METADATA_SOURCE_TYPE, chunk.sourceType());
        metadata.put(METADATA_SEQUENCE, chunk.sequence());
        metadata.put(METADATA_START_OFFSET, chunk.startOffset());
        metadata.put(METADATA_END_OFFSET, chunk.endOffset());
        metadata.put(METADATA_INGESTED_AT, ingestedAt);
        metadata.putAll(chunk.metadata());
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        return metadata;
    }
}
