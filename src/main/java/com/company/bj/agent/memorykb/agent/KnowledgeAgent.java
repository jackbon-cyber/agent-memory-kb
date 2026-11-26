package com.company.bj.agent.memorykb.agent;

import com.company.bj.agent.memorykb.memory.MemoryScope;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 知识问答智能体。
 *
 * <p>对外提供一次问答的入口：把问题交给 {@link ReActAgent}，
 * 返回回答文本与本次实际引用的知识库切片。
 *
 * <p><b>作用域与引用的跨线程传递</b>是本类的核心职责。
 * AgentScope 的工具调用发生在 {@code boundedElastic} 线程上（已实测确认），
 * 而作用域由 {@link ScopeResolver} 托管在 {@code ThreadLocal} 上 ——
 * 两者在工具线程上并不相遇。若放任不管：
 * <ul>
 *   <li>长期记忆工具读不到作用域，越权防线在跨线程那一步被绕过；</li>
 *   <li>引用收集器不在工具线程上，引用会永远是空的。</li>
 * </ul>
 *
 * <p><b>解法</b>：把作用域与引用收集器放进 {@link RuntimeContext} 的
 * {@code extras}。该通道随调用参数一路传到工具方法，
 * 已在 AgentScope 2.0.4 上实测确认可跨线程读取。
 *
 * <p><b>作用域在组装期捕获</b>：{@code ask} 方法在<b>调用线程</b>上执行，
 * 此时作用域仍在 {@link ScopeResolver} 的 {@code ThreadLocal} 上。
 * 若改为在订阅时才解析作用域，订阅线程往往已换线程，
 * 调用方会收到一条指向「作用域机制」而实为「线程切换」的误导性错误。
 * 因此在组装期捕获作用域快照，随 {@code RuntimeContext} 传下去。
 */
public class KnowledgeAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAgent.class);

    /** 未指定会话标识时使用的缺省会话标识。 */
    public static final String DEFAULT_SESSION_ID = "default";

    private final ReActAgent agent;
    private final ScopeResolver resolver;

    /**
     * 构造知识问答智能体。
     *
     * @param agent    装配好的 ReAct 智能体
     * @param resolver 作用域解析器
     */
    public KnowledgeAgent(ReActAgent agent, ScopeResolver resolver) {
        this.agent = agent;
        this.resolver = resolver;
    }

    /**
     * 提问，使用缺省会话。
     *
     * @param question 用户问题
     * @return 问答结果
     */
    public Mono<ChatResult> ask(String question) {
        return ask(question, DEFAULT_SESSION_ID);
    }

    /**
     * 提问，指定会话。
     *
     * @param question  用户问题
     * @param sessionId 会话标识；同一会话的多轮对话共享上下文
     * @return 问答结果
     */
    public Mono<ChatResult> ask(String question, String sessionId) {
        if (question == null || question.isBlank()) {
            return Mono.just(new ChatResult("", List.of()));
        }

        // 组装期捕获作用域：此刻仍在调用线程上，ThreadLocal 可见。
        MemoryScope scope = resolver.current();
        String effectiveSessionId = sessionId == null || sessionId.isBlank()
            ? DEFAULT_SESSION_ID : sessionId;

        CitationCollector collector = new CitationCollector();
        RuntimeContext context = RuntimeContext.builder()
            .sessionId(effectiveSessionId)
            .put(AgentContextKeys.MEMORY_SCOPE, scope)
            .put(AgentContextKeys.CITATION_COLLECTOR, collector)
            .build();

        return agent.call(question, context)
            .map(reply -> new ChatResult(answerOf(reply), collector.snapshot()))
            .doOnError(error -> log.warn("知识问答失败，会话 {}：{}", effectiveSessionId, error.getMessage()));
    }

    /**
     * 取出模型回答的文本内容。
     *
     * @param reply 模型回复
     * @return 回答文本，回复为 {@code null} 时返回空字符串
     */
    private String answerOf(Msg reply) {
        if (reply == null) {
            return "";
        }
        String text = reply.getTextContent();
        return text == null ? "" : text;
    }
}
