package com.company.bj.agent.memorykb.agent;

import com.company.bj.agent.memorykb.memory.LongTermStore;
import com.company.bj.agent.memorykb.memory.MemoryRecord;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/**
 * 长期记忆工具。
 *
 * <p>让智能体能够把跨会话有用的事实写入长期记忆，并在后续对话中召回它们。
 *
 * <p><b>为什么不使用 AgentScope 自带的 {@code LongTermMemoryTools}</b>：
 * 该工具的 {@code recordToMemory} / {@code retrieveFromMemory} 方法签名里
 * <b>没有 {@code RuntimeContext}</b> 参数，因此拿不到请求级的作用域。
 * 而本工程的 {@link LongTermStore} 契约<b>不接受作用域入参</b> ——
 * 作用域一律由 {@link ScopeResolver} 从当前执行上下文解析。
 * 两边一叠加就出现了死结：工具执行线程上没有作用域，
 * 存储层会抛 {@code IllegalStateException("no active memory scope")}。
 *
 * <p><b>解法</b>：把作用域放进 {@code RuntimeContext.extras}，
 * 工具方法通过 {@code RuntimeContext} 参数取回它，
 * 再用 {@link ScopeResolver#withScopeResult} 在<b>该工具线程上</b>重建作用域，
 * 然后才调用存储。作用域因此仍然是「存储层强制」的，
 * 调用方既不能传入作用域、也无法跳过作用域检查。
 *
 * <p>这正是本工程坚持「作用域不做成方法入参」的兑现方式：
 * 入参不可传，但作用域<b>一定会被强制附加</b>到查询条件上。
 */
public class LongTermMemoryTool {

    /** 默认召回条数。 */
    public static final int DEFAULT_TOP_K = 5;

    /** 召回结果中多条记忆之间的分隔符。 */
    private static final String RECORD_SEPARATOR = "\n---\n";

    /** 未找到匹配记忆时给模型的提示文本。 */
    private static final String NO_MEMORY_MESSAGE = "长期记忆中没有找到相关记录。";

    /** 记录成功时给模型的确认文本。 */
    private static final String RECORD_SUCCESS_MESSAGE = "已记录到长期记忆。";

    private final LongTermStore store;
    private final ScopeResolver resolver;

    /**
     * 构造长期记忆工具。
     *
     * @param store    长期记忆存储
     * @param resolver 作用域解析器
     */
    public LongTermMemoryTool(LongTermStore store, ScopeResolver resolver) {
        this.store = store;
        this.resolver = resolver;
    }

    /**
     * 把一条事实记录到长期记忆。
     *
     * @param content 要记录的事实文本
     * @param context 运行时上下文，由 AgentScope 自动注入
     * @return 记录结果提示
     */
    @Tool(name = "record_to_memory",
        description = "把跨会话有用的事实记录到长期记忆，"
            + "例如用户的偏好、已确认的结论。仅在事实本身有复用价值时调用。")
    public String recordToMemory(
        @ToolParam(name = "content", description = "要记录的事实文本") String content,
        RuntimeContext context) {
        if (content == null || content.isBlank()) {
            return "没有可记录的内容。";
        }
        MemoryScope scope = resolveScope(context);
        resolver.withScopeResult(scope, () -> store.remember(content));
        return RECORD_SUCCESS_MESSAGE;
    }

    /**
     * 从长期记忆召回与关键词相关的事实。
     *
     * @param keywords 检索关键词
     * @param context  运行时上下文，由 AgentScope 自动注入
     * @return 召回的记录文本，无命中时返回空结果提示
     */
    @Tool(name = "retrieve_from_memory",
        description = "从长期记忆中召回与关键词相关的事实，用于了解用户此前的偏好或已确认的结论。")
    public String retrieveFromMemory(
        @ToolParam(name = "keywords", description = "检索关键词，多个关键词用空格分隔") String keywords,
        RuntimeContext context) {
        if (keywords == null || keywords.isBlank()) {
            return NO_MEMORY_MESSAGE;
        }
        MemoryScope scope = resolveScope(context);
        List<MemoryRecord> records = resolver.withScopeResult(scope,
            () -> store.search(keywords, DEFAULT_TOP_K));
        if (records == null || records.isEmpty()) {
            return NO_MEMORY_MESSAGE;
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("召回 ").append(records.size()).append(" 条长期记忆：\n\n");
        for (MemoryRecord record : records) {
            builder.append('-').append(record.content()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 从运行时上下文取出本次请求的记忆作用域。
     *
     * @param context 运行时上下文，可为 {@code null}
     * @return 记忆作用域
     * @throws IllegalStateException 上下文中没有携带作用域时抛出，
     *                               消息指明缺失原因而不是含糊的「无作用域」
     */
    private MemoryScope resolveScope(RuntimeContext context) {
        if (context != null) {
            Object scope = context.getExtra().get(AgentContextKeys.MEMORY_SCOPE);
            if (scope instanceof MemoryScope memoryScope) {
                return memoryScope;
            }
        }
        throw new IllegalStateException(
            "memory scope is missing from the runtime context; "
                + "the agent must be invoked through KnowledgeAgent so that the scope is attached");
    }
}
