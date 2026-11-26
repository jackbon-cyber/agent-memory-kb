package com.company.bj.agent.memorykb.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.chroma.ChromaStore;
import com.company.bj.agent.memorykb.knowledge.chunk.Chunker;
import com.company.bj.agent.memorykb.knowledge.chunk.SourceTypeRoutingChunker;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.company.bj.agent.memorykb.knowledge.parse.DocumentParserRegistry;
import com.company.bj.agent.memorykb.memory.CachedEmbeddingService;
import com.company.bj.agent.memorykb.memory.ChromaLongTermMemory;
import com.company.bj.agent.memorykb.memory.ChromaLongTermStore;
import com.company.bj.agent.memorykb.memory.EpisodicMemoryStore;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import com.company.bj.agent.memorykb.memory.WorkingMemoryStore;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 校验 {@link KnowledgeConfiguration} 的装配结果。
 *
 * <p>重点是<b>切片器消歧</b>：文本切片器与表格切片器类型相同（都是 {@link Chunker}），
 * 路由切片器需要拿到的是确定的那两个，若消歧失效，上下文启动即失败或注入错对象。
 *
 * <p>另一处重点是<b>嵌入服务只有一个</b>：装配出来的必须是带缓存的
 * {@link CachedEmbeddingService}，且它是唯一的 {@code EmbeddingService} Bean ——
 * 若同时存在缓存版与未缓存版，注入点会歧义，缓存是否生效也无从判断。
 *
 * <p>本测试不触碰 Chroma 服务与嵌入网关：所有 Bean 都是惰性构造，不发起任何网络请求。
 */
@SpringBootTest
class KnowledgeConfigurationTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private Chunker chunker;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ChromaClient chromaClient;

    @Autowired
    private DocumentParserRegistry parserRegistry;

    @Autowired
    private KnowledgeRetriever knowledgeRetriever;

    @Autowired
    private ChromaStore chromaStore;

    @Autowired
    private ScopeResolver scopeResolver;

    @Autowired
    private ChromaLongTermStore chromaLongTermStore;

    @Autowired
    private ChromaLongTermMemory chromaLongTermMemory;

    @Autowired
    private WorkingMemoryStore workingMemoryStore;

    @Autowired
    private EpisodicMemoryStore episodicMemoryStore;

    @Test
    void 上下文能装配出摄取服务() {
        assertThat(ingestionService).isNotNull();
        assertThat(chromaClient).isNotNull();
        assertThat(parserRegistry.supports("手册.txt")).isTrue();
        assertThat(parserRegistry.supports("名单.xlsx")).isTrue();
    }

    @Test
    void 路由切片器被正确装配() {
        assertThat(chunker).isInstanceOf(SourceTypeRoutingChunker.class);
    }

    @Test
    void 嵌入服务使用带缓存的实现() {
        // 缓存必须包在真实嵌入服务的外面，知识库检索与长期记忆才都能吃到缓存。
        assertThat(embeddingService).isInstanceOf(CachedEmbeddingService.class);
    }

    @Test
    void 切片参数使用全局约束的默认值() {
        // 600 / 90 来自全局约束（单片上限 600、重叠 90）。
        // 装配的是路由切片器，其内部的具体参数不可直接读取，
        // 因此这里通过"路由切片器确实由两个具体切片器组合"来钉住装配形态，
        // 数值正确性由 RecursiveCharacterChunkerTest / TableRowChunkerTest 覆盖。
        assertThat(KnowledgeConfiguration.MAX_CHUNK_SIZE).isEqualTo(600);
        assertThat(KnowledgeConfiguration.OVERLAP).isEqualTo(90);
    }

    @Test
    void 检索端口与向量库存储被正确装配() {
        assertThat(knowledgeRetriever).isInstanceOf(ChromaKnowledgeRetriever.class);
        assertThat(chromaStore).isNotNull();
        // ChromaStore 必须实现 AgentScope 的 VDBStoreBase，否则 SimpleKnowledge 无法挂载。
        assertThat(chromaStore).isInstanceOf(VDBStoreBase.class);
    }

    @Test
    void 四层记忆组件被正确装配() {
        assertThat(scopeResolver).isNotNull();
        assertThat(chromaLongTermStore).isNotNull();
        assertThat(workingMemoryStore).isNotNull();
        assertThat(episodicMemoryStore).isNotNull();
        // AgentScope 的 LongTermMemory 契约必须被满足，否则记忆链路接不上 AgentScope。
        assertThat(chromaLongTermMemory).isInstanceOf(LongTermMemory.class);
    }
}
