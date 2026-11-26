package com.company.bj.agent.memorykb.knowledge;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.chroma.ChromaHealthIndicator;
import com.company.bj.agent.memorykb.chroma.ChromaStore;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.config.MemoryProperties;
import com.company.bj.agent.memorykb.knowledge.chunk.Chunker;
import com.company.bj.agent.memorykb.knowledge.chunk.RecursiveCharacterChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.SourceTypeRoutingChunker;
import com.company.bj.agent.memorykb.knowledge.chunk.TableRowChunker;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.company.bj.agent.memorykb.knowledge.embed.OpenAiCompatibleEmbeddingService;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParser;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParserRegistry;
import com.company.bj.agent.memorykb.knowledge.parse.ExcelDocumentParser;
import com.company.bj.agent.memorykb.knowledge.parse.TxtDocumentParser;
import com.company.bj.agent.memorykb.memory.CachedEmbeddingService;
import com.company.bj.agent.memorykb.memory.ChromaLongTermMemory;
import com.company.bj.agent.memorykb.memory.ChromaLongTermStore;
import com.company.bj.agent.memorykb.memory.EpisodicMemoryStore;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import com.company.bj.agent.memorykb.memory.WorkingMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库组件的 Spring 装配。
 *
 * <p>领域类（切片器、解析器、嵌入服务、摄取服务）保持为普通 Java 对象，
 * 不在自身类上标注 {@code @Component} —— 这样它们可以被直接 new 出来做单元测试，
 * 不必拉起 Spring 容器。装配集中在本类，避免同一批 Bean 散落在多个配置类里。
 */
@Configuration
public class KnowledgeConfiguration {

    /** 单片字符上限。 */
    public static final int MAX_CHUNK_SIZE = 600;

    /** 相邻切片的重叠字符数（约为单片上限的 15%）。 */
    public static final int OVERLAP = 90;

    /** HTTP 客户端的连接超时时间。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 提供共享的 HTTP 客户端。
     *
     * @return HTTP 客户端
     */
    @Bean
    public HttpClient memoryKbHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    }

    /**
     * 提供 Chroma 客户端。
     *
     * @param props      Chroma 配置
     * @param httpClient HTTP 客户端
     * @param mapper     JSON 序列化器
     * @return Chroma 客户端
     */
    @Bean
    public ChromaClient chromaClient(ChromaProperties props, HttpClient httpClient, ObjectMapper mapper) {
        return new ChromaClient(props, httpClient, mapper);
    }

    /**
     * 提供嵌入服务。
     *
     * <p><b>返回的是带缓存的实例</b>：{@link CachedEmbeddingService} 包在
     * {@link OpenAiCompatibleEmbeddingService} <b>外面</b>，
     * 因此知识库检索器与长期记忆存储拿到的都是缓存版本。
     *
     * <p>本方法是<b>整个应用里唯一</b>的 {@code EmbeddingService} Bean ——
     * 若同时注册缓存版与未缓存版两个 Bean，注入点将出现歧义，
     * 而「哪些调用点走了缓存」也就无从判断了。
     *
     * @param props         嵌入模型配置
     * @param httpClient    HTTP 客户端
     * @param mapper        JSON 序列化器
     * @param memoryProps   记忆配置，提供查询向量缓存 TTL
     * @return 嵌入服务
     */
    @Bean
    public EmbeddingService embeddingService(EmbeddingProperties props, HttpClient httpClient,
        ObjectMapper mapper, MemoryProperties memoryProps) {
        EmbeddingService delegate = new OpenAiCompatibleEmbeddingService(props, httpClient, mapper);
        return new CachedEmbeddingService(delegate, memoryProps.getQueryCacheTtlSeconds());
    }

    /**
     * 提供解析器注册表。
     *
     * @param parsers 受支持的解析器
     * @return 解析器注册表
     */
    @Bean
    public DocumentParserRegistry documentParserRegistry(List<DocumentParser> parsers) {
        return new DocumentParserRegistry(parsers);
    }

    /**
     * 提供纯文本解析器。
     *
     * @return 纯文本解析器
     */
    @Bean
    public DocumentParser txtDocumentParser() {
        return new TxtDocumentParser();
    }

    /**
     * 提供 Excel 解析器。
     *
     * @return Excel 解析器
     */
    @Bean
    public DocumentParser excelDocumentParser() {
        return new ExcelDocumentParser();
    }

    /**
     * 提供按来源类型路由的切片器。
     *
     * <p>两个入参类型相同，靠 {@link Qualifier} 消歧 —— 否则 Spring 只能按参数名匹配，
     * 而参数名匹配依赖编译期保留参数名的开关，配置调整时容易出现难以定位的装配失败。
     *
     * @param textChunker  文本切片器
     * @param tableChunker 表格切片器
     * @return 路由切片器
     */
    @Bean
    public Chunker chunker(@Qualifier("textChunker") Chunker textChunker,
        @Qualifier("tableChunker") Chunker tableChunker) {
        return new SourceTypeRoutingChunker(textChunker, tableChunker);
    }

    /**
     * 提供文本切片器。
     *
     * @return 递归字符切片器
     */
    @Bean
    public Chunker textChunker() {
        return new RecursiveCharacterChunker(MAX_CHUNK_SIZE, OVERLAP);
    }

    /**
     * 提供表格切片器。
     *
     * @return 表格行切片器
     */
    @Bean
    public Chunker tableChunker() {
        return new TableRowChunker(MAX_CHUNK_SIZE, OVERLAP);
    }

    /**
     * 提供摄取服务。
     *
     * @param parsers        解析器注册表
     * @param chunker        切片器
     * @param embeddings     嵌入服务
     * @param chroma         Chroma 客户端
     * @param props          Chroma 配置
     * @param embeddingProps 嵌入模型配置，提供期望的向量维度
     * @return 摄取服务
     */
    @Bean
    public IngestionService ingestionService(DocumentParserRegistry parsers, Chunker chunker,
        EmbeddingService embeddings, ChromaClient chroma, ChromaProperties props,
        EmbeddingProperties embeddingProps) {
        return new IngestionService(parsers, chunker, embeddings, chroma, props, embeddingProps);
    }

    /**
     * 提供带元数据过滤的检索端口实现。
     *
     * @param chroma     Chroma 客户端
     * @param embeddings 嵌入服务，用于把查询文本转换为查询向量
     * @param props      Chroma 配置，提供知识库集合名
     * @return 检索端口实现
     */
    @Bean
    public KnowledgeRetriever knowledgeRetriever(ChromaClient chroma, EmbeddingService embeddings,
        ChromaProperties props) {
        return new ChromaKnowledgeRetriever(chroma, embeddings, props);
    }

    /**
     * 提供文档清单服务。
     *
     * @param chroma Chroma 客户端
     * @param props  Chroma 配置，提供知识库集合名
     * @return 文档清单服务
     */
    @Bean
    public KnowledgeCatalogService knowledgeCatalogService(ChromaClient chroma, ChromaProperties props) {
        return new KnowledgeCatalogService(chroma, props);
    }

    /**
     * 提供 AgentScope SPI 的向量库存储适配器。
     *
     * <p>供 {@code SimpleKnowledge} 挂载本工程的 Chroma 集合。
     *
     * @param chroma     Chroma 客户端
     * @param embeddings 嵌入服务，用于在文档缺少向量时补齐
     * @param props      Chroma 配置，提供知识库集合名
     * @return 向量库存储适配器
     */
    @Bean
    public ChromaStore chromaStore(ChromaClient chroma, EmbeddingService embeddings, ChromaProperties props) {
        return new ChromaStore(chroma, embeddings, props);
    }

    /**
     * 提供 Chroma 健康指示器。
     *
     * @param chroma Chroma 客户端
     * @return 健康指示器
     */
    @Bean
    public ChromaHealthIndicator chromaHealthIndicator(ChromaClient chroma) {
        return new ChromaHealthIndicator(chroma);
    }

    /**
     * 提供作用域解析器。
     *
     * @return 作用域解析器
     */
    @Bean
    public ScopeResolver scopeResolver() {
        return new ScopeResolver();
    }

    /**
     * 提供长期记忆存储。
     *
     * @param chroma     Chroma 客户端
     * @param embeddings 嵌入服务（缓存版）
     * @param props      Chroma 配置，提供长期记忆集合名
     * @param resolver   作用域解析器
     * @return 长期记忆存储
     */
    @Bean
    public ChromaLongTermStore chromaLongTermStore(ChromaClient chroma, EmbeddingService embeddings,
        ChromaProperties props, ScopeResolver resolver) {
        return new ChromaLongTermStore(chroma, embeddings, props, resolver);
    }

    /**
     * 提供 AgentScope {@code LongTermMemory} 契约的适配器。
     *
     * <p>复用同一个 {@link ChromaLongTermStore} Bean —— 平台侧读写的长期记忆
     * 与 AgentScope 侧读写的是同一份数据，不存在「两套长期记忆」的可能。
     *
     * @param store    长期记忆存储
     * @param resolver 作用域解析器，用于在组装期捕获作用域
     * @return AgentScope 长期记忆适配器
     */
    @Bean
    public ChromaLongTermMemory chromaLongTermMemory(ChromaLongTermStore store, ScopeResolver resolver) {
        return new ChromaLongTermMemory(store, resolver);
    }

    /**
     * 提供工作记忆存储。
     *
     * @param resolver   作用域解析器
     * @param properties 记忆配置，提供持久化路径
     * @return 工作记忆存储
     */
    @Bean
    public WorkingMemoryStore workingMemoryStore(ScopeResolver resolver, MemoryProperties properties) {
        return new WorkingMemoryStore(resolver, properties);
    }

    /**
     * 提供情景记忆存储。
     *
     * @param resolver   作用域解析器
     * @param properties 记忆配置，提供持久化路径
     * @return 情景记忆存储
     */
    @Bean
    public EpisodicMemoryStore episodicMemoryStore(ScopeResolver resolver, MemoryProperties properties) {
        return new EpisodicMemoryStore(resolver, properties);
    }
}
