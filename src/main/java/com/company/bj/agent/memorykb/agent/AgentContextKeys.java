package com.company.bj.agent.memorykb.agent;

/**
 * 智能体运行期上下文键名。
 *
 * <p>AgentScope 的 {@code RuntimeContext} 提供 {@code extras} 这一「每请求附加数据」通道，
 * 是把<b>请求级</b>状态（作用域、引用收集器）送进工具方法的唯一可靠途径。
 *
 * <p><b>为什么不能靠 ThreadLocal</b>：AgentScope 的工具执行发生在
 * {@code Schedulers.boundedElastic()} 的线程上（已实测确认：调用线程为 {@code main}，
 * 工具线程为 {@code boundedElastic-1}），{@code ThreadLocal} 在线程切换后不可见。
 * 若工具方法依赖线程作用域，越权防线会在跨线程的那一步被绕过 ——
 * 而这恰恰是最危险的路径。
 *
 * <p><b>为什么不能靠模型输出反推</b>：从模型回答文本里解析引用是不可靠的，
 * 模型可能改写、漏写或编造来源。引用必须由<b>检索侧</b>在命中时记录。
 */
public final class AgentContextKeys {

    /** 记忆作用域在 {@code RuntimeContext.extras} 中的键名。 */
    public static final String MEMORY_SCOPE = "memorykb.memory.scope";

    /** 引用收集器在 {@code RuntimeContext.extras} 中的键名。 */
    public static final String CITATION_COLLECTOR = "memorykb.citation.collector";

    private AgentContextKeys() {
    }
}
