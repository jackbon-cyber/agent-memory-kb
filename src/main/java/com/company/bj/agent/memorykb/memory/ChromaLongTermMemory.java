package com.company.bj.agent.memorykb.memory;

import com.company.bj.agent.memorykb.chroma.ChromaClient;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.memory.LongTermMemory;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * AgentScope {@code LongTermMemory} 契约的 Chroma 适配器。
 *
 * <p>让 AgentScope 原生的记忆链路可以把本工程的 Chroma 长期记忆当作后端使用：
 * {@code record} 把消息写入长期记忆，{@code retrieve} 按消息内容召回相关记忆。
 *
 * <p><b>检索逻辑一律委托 {@link ChromaLongTermStore}</b>，本类不重复实现任何
 * 作用域过滤或检索细节。这不是省事，而是刻意的：
 * 作用域过滤是本工程<b>越权读写的唯一屏障</b>，一旦出现两份实现，
 * 迟早会有一份漏改，表现为「某些入口能读到别人的记忆」——
 * 这类问题在测试里也不容易发现，因为两条路径要分别触发才会暴露分歧。
 * 委托之后，屏障只有一处，修复只需改一处。
 *
 * <p><b>响应式契约</b>：AgentScope 的接口返回 {@link Mono}，
 * 实现用 {@code Mono.fromCallable} 把同步的存储调用包成惰性序列 ——
 * 存储调用在<b>订阅时</b>才发生，而不是在组装 Mono 时。
 *
 * <p><b>作用域必须在组装期捕获</b>：作用域由 {@link ScopeResolver} 托管在
 * {@link ThreadLocal} 上，而订阅线程未必是组装线程（任何 {@code subscribeOn} /
 * {@code publishOn} 或 WebFlux 式的非阻塞订阅都会换线程）。
 * 若在订阅时才解析作用域，订阅线程上往往没有作用域，
 * 调用方会收到 {@code IllegalStateException("no active memory scope")} ——
 * 这条消息指向作用域机制，而真实原因是线程切换，极易误诊。
 *
 * <p>因此本类在<b>组装期</b>就解析作用域并把它捕获下来（无作用域时立即以
 * 正确的归因失败），在 callable 内部<b>重新建立</b>该作用域后再调用存储
 * —— 于是无论订阅发生在哪个线程，读写的都是组装期确定的那个作用域。
 */
public class ChromaLongTermMemory implements LongTermMemory {

    /** 召回结果中多条记忆之间的分隔符。 */
    private static final String RECORD_SEPARATOR = "\n";

    /** 一次检索返回的记忆条数上限。 */
    private static final int DEFAULT_TOP_K = 5;

    private final ChromaLongTermStore store;
    private final ScopeResolver resolver;

    /**
     * 构造长期记忆适配器。
     *
     * @param chroma     Chroma 客户端
     * @param embeddings 嵌入服务，用于把文本转换为向量
     * @param props      Chroma 配置，提供长期记忆集合名
     * @param resolver   作用域解析器
     */
    public ChromaLongTermMemory(ChromaClient chroma, EmbeddingService embeddings,
        ChromaProperties props, ScopeResolver resolver) {
        this.store = new ChromaLongTermStore(chroma, embeddings, props, resolver);
        this.resolver = resolver;
    }

    /**
     * 构造长期记忆适配器，直接复用既有的长期记忆存储。
     *
     * <p>供 Spring 装配使用 —— 保证适配器与平台侧读到的是<b>同一个</b>存储实例。
     *
     * @param store    长期记忆存储
     * @param resolver 作用域解析器，用于在组装期捕获作用域
     */
    public ChromaLongTermMemory(ChromaLongTermStore store, ScopeResolver resolver) {
        this.store = store;
        this.resolver = resolver;
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        // 组装期捕获作用域：订阅线程未必是组装线程，
        // 捕获后订阅时才能在正确的作用域下写入。
        MemoryScope scope = resolver.current();
        return Mono.fromCallable(() -> resolver.withScopeResult(scope, () -> {
            if (messages == null || messages.isEmpty()) {
                return null;
            }
            for (Msg message : messages) {
                String text = textOf(message);
                // 空白内容不入库：向量模型对空文本没有意义，
                // 而库里多一条空记录只会在检索时挤掉真正有价值的记忆。
                if (text.isBlank()) {
                    continue;
                }
                store.remember(text);
            }
            return null;
        }));
    }

    @Override
    public Mono<String> retrieve(Msg message) {
        MemoryScope scope = resolver.current();
        return Mono.fromCallable(() -> resolver.withScopeResult(scope, () -> {
            String query = textOf(message);
            if (query.isBlank()) {
                return "";
            }
            List<MemoryRecord> records = store.search(query, DEFAULT_TOP_K);
            List<String> contents = new ArrayList<>(records.size());
            for (MemoryRecord record : records) {
                contents.add(record.content());
            }
            return String.join(RECORD_SEPARATOR, contents);
        }));
    }

    /**
     * 取出消息的纯文本内容。
     *
     * <p>{@code Msg.getTextContent()} 会把所有 {@code TextBlock} 的文本用换行拼接，
     * 非文本块（如图片块）会被跳过 —— 这正是我们要的：
     * 记忆存的是文本，图片块进不了向量模型。
     *
     * @param message 消息
     * @return 文本内容，消息为 {@code null} 时返回空字符串
     */
    private String textOf(Msg message) {
        if (message == null) {
            return "";
        }
        String text = message.getTextContent();
        return text == null ? "" : text;
    }
}
