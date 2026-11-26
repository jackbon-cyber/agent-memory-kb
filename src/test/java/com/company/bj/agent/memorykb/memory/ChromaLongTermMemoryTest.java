package com.company.bj.agent.memorykb.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingNormalizer;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

/**
 * AgentScope {@code LongTermMemory} 适配器的行为验证。
 *
 * <p>验证的是<b>AgentScope 契约本身</b>：{@code record} 接受消息列表并落库，
 * {@code retrieve} 接受单条消息并返回可读文本。
 * 适配器内部一律委托 {@link ChromaLongTermStore}，因此作用域隔离语义与
 * 长期记忆存储完全一致。
 */
@EnabledIfEnvironmentVariable(named = "CHROMA_IT", matches = "true")
class ChromaLongTermMemoryTest {

    /** 冒烟测试专用集合名，满足 3-512 长度与 [a-zA-Z0-9._-] 字符集规则。 */
    private static final String COLLECTION_NAME = "it_mem_longterm";

    /** 桩嵌入模型的向量维度，与 Chroma 集合维度绑定一致。 */
    private static final int STUB_EMBEDDING_DIMENSIONS = 256;

    private ScopeResolver resolver;
    private ChromaLongTermMemory memory;
    private MemoryScope scope;

    @BeforeEach
    void 准备集合与作用域() {
        ChromaProperties props = new ChromaProperties();
        props.setTenant("default_tenant");
        props.setDatabase("default_database");
        props.setMemoryCollection(COLLECTION_NAME);

        ChromaClient client = new ChromaClient(props, HttpClient.newHttpClient(), new ObjectMapper());
        resolver = new ScopeResolver();
        memory = new ChromaLongTermMemory(client, stubEmbeddings(), props, resolver);
        scope = MemoryScope.of("tenant-" + UUID.randomUUID(), "owner-" + UUID.randomUUID());
    }

    @Test
    void 记录消息后可检索回内容() {
        Msg message = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("巡检要点：先断电后挂牌")
            .build();

        resolver.withScopeResult(scope, () -> memory.record(List.of(message)).block());

        String retrieved = resolver.withScopeResult(scope, () -> memory.retrieve(message).block());

        assertThat(retrieved).isNotNull();
        assertThat(retrieved).contains("巡检要点：先断电后挂牌");
    }

    @Test
    void 检索只返回当前作用域的内容() {
        Msg message = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("跨作用域不应的内容")
            .build();

        resolver.withScopeResult(scope, () -> memory.record(List.of(message)).block());

        String retrieved = resolver.withScopeResult(
            MemoryScope.of(scope.tenantId(), "other-owner"), () -> memory.retrieve(message).block());

        assertThat(retrieved).isNullOrEmpty();
    }

    @Test
    void 有作用域时记录返回空的完成信号() {
        Msg message = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("无作用域的内容")
            .build();

        // 记录成功时 Mono<Void> 的结果是「空」——这是 Reactor 对 Void 的标准表现，
        // 不是「失败」。
        assertThat(resolver.withScopeResult(scope, () -> memory.record(List.of(message)).block())).isNull();
    }

    @Test
    void 无作用域时组装记录立即失败() {
        Msg message = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("无作用域的内容")
            .build();

        // 作用域在组装期解析：缺失时立刻以正确的归因失败，
        // 而不是推迟到订阅期再抛出一条会被误读成「线程切换」的消息。
        assertThatThrownBy(() -> memory.record(List.of(message)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("no active memory scope");
    }

    @Test
    void 无作用域时组装检索立即失败() {
        Msg message = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("无作用域的内容")
            .build();

        assertThatThrownBy(() -> memory.retrieve(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("no active memory scope");
    }

    @Test
    void 在作用域外订阅仍能读到作用域内的内容() throws Exception {
        Msg message = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("跨线程订阅仍应可见")
            .build();

        resolver.withScopeResult(scope, () -> memory.record(List.of(message)).block());

        // 组装在作用域内，订阅在作用域外（另一个线程）——
        // 这正是任何 subscribeOn / publishOn 或 WebFlux 式非阻塞订阅的场景。
        // 作用域在组装期已被捕获，因此订阅线程上无需再持有作用域。
        Mono<String> assembled = resolver.withScopeResult(scope, () -> memory.retrieve(message));

        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread subscriber = new Thread(() -> {
            try {
                result.set(assembled.block());
            } catch (RuntimeException e) {
                failure.set(e);
            }
        }, "subscriber-outside-scope");
        subscriber.start();
        subscriber.join(10_000L);

        assertThat(failure.get()).isNull();
        assertThat(result.get()).isNotNull();
        assertThat(result.get()).contains("跨线程订阅仍应可见");
    }

    @Test
    void 在另一个作用域内订阅仍按组装时的作用域读数据() throws Exception {
        Msg message = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("组装作用域不被订阅作用域覆盖")
            .build();

        resolver.withScopeResult(scope, () -> memory.record(List.of(message)).block());

        // 组装在 scope 内，订阅发生在<b>另一个</b>作用域内：
        // 订阅时的作用域不得覆盖组装时捕获的作用域，否则读到的是别人的数据。
        MemoryScope other = MemoryScope.of(scope.tenantId(), "other-owner");
        Mono<String> assembled = resolver.withScopeResult(scope, () -> memory.retrieve(message));

        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread subscriber = new Thread(() -> {
            try {
                result.set(resolver.withScopeResult(other, () -> assembled.block()));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        }, "subscriber-other-scope");
        subscriber.start();
        subscriber.join(10_000L);

        assertThat(failure.get()).isNull();
        assertThat(result.get()).isNotNull();
        assertThat(result.get()).contains("组装作用域不被订阅作用域覆盖");
    }

    /**
     * 返回确定性的本地嵌入模型。
     *
     * @return 桩嵌入服务
     */
    private static EmbeddingService stubEmbeddings() {
        return new EmbeddingService() {
            @Override
            public List<double[]> embed(List<String> texts) {
                List<double[]> vectors = new ArrayList<>(texts.size());
                for (String text : texts) {
                    double[] vector = new double[STUB_EMBEDDING_DIMENSIONS];
                    for (int i = 0; i < text.length(); i++) {
                        int bucket = Math.floorMod(text.charAt(i) * 31 + i, STUB_EMBEDDING_DIMENSIONS);
                        vector[bucket] += 1.0;
                    }
                    vectors.add(EmbeddingNormalizer.normalize(vector));
                }
                return vectors;
            }
        };
    }
}
