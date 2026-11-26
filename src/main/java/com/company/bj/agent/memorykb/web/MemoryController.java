package com.company.bj.agent.memorykb.web;

import com.company.bj.agent.memorykb.agent.KnowledgeAgent;
import com.company.bj.agent.memorykb.memory.EpisodicMemoryStore;
import com.company.bj.agent.memorykb.memory.LongTermStore;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import com.company.bj.agent.memorykb.memory.WorkingMemoryStore;
import com.company.bj.agent.memorykb.web.dto.EpisodicMemoryResponse;
import com.company.bj.agent.memorykb.web.dto.EpisodeResponse;
import com.company.bj.agent.memorykb.web.dto.LongTermMemoryResponse;
import com.company.bj.agent.memorykb.web.dto.MemoryRecordResponse;
import com.company.bj.agent.memorykb.web.dto.ShortTermMemoryResponse;
import com.company.bj.agent.memorykb.web.dto.TaskItemResponse;
import com.company.bj.agent.memorykb.web.dto.WorkingMemoryResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 四层记忆查看接口。
 *
 * <p>暴露短期、长期、工作、情景四层记忆的只读视图，供控制台浏览。
 *
 * <p><b>作用域从请求头来，但绝不作为参数传给存储层</b>：
 * 控制器把请求头解析成 {@link MemoryScope}，再用
 * {@link ScopeResolver#withScopeResult} 包裹对存储的调用。
 * 存储契约<b>不接受</b>作用域入参 —— 越权防线只有一处，
 * 由 {@code ScopeResolver} 把守。若把作用域做成方法参数传给存储，
 * 调用方就可能「忘了传」或「传错」，而这正是作用域唯一的屏障。
 *
 * <p><b>短期记忆读的是智能体的会话状态</b>，而不是平台自带的
 * {@code ShortTermMemoryStore}：前者才是智能体多轮对话真正依赖的上下文
 * （AgentScope 的会话状态按 {@code sessionId} 隔离），
 * 后者是平台侧的独立存储。控制台要展示的是「智能体记住了什么」，
 * 因此必须读前者 —— 读后者会永远得到空列表，
 * 让人误以为智能体没有短期记忆。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    /** 长期记忆列举条数上限。 */
    private static final int LONG_TERM_LIMIT = 100;

    /** 缺省会话标识，与问答接口保持一致。 */
    private static final String DEFAULT_SESSION_ID = KnowledgeAgent.DEFAULT_SESSION_ID;

    private final ReActAgent agent;
    private final LongTermStore longTermStore;
    private final WorkingMemoryStore workingMemoryStore;
    private final EpisodicMemoryStore episodicMemoryStore;
    private final ScopeResolver resolver;

    /**
     * 构造记忆查看接口。
     *
     * @param agent              智能体，用于读取会话的短期记忆
     * @param longTermStore      长期记忆存储
     * @param workingMemoryStore 工作记忆存储
     * @param episodicMemoryStore 情景记忆存储
     * @param resolver           作用域解析器
     */
    public MemoryController(ReActAgent agent, LongTermStore longTermStore,
        WorkingMemoryStore workingMemoryStore, EpisodicMemoryStore episodicMemoryStore,
        ScopeResolver resolver) {
        this.agent = agent;
        this.longTermStore = longTermStore;
        this.workingMemoryStore = workingMemoryStore;
        this.episodicMemoryStore = episodicMemoryStore;
        this.resolver = resolver;
    }

    /**
     * 查看短期记忆（会话内的消息序列）。
     *
     * @param sessionId 会话标识，缺省为 {@code default}
     * @return 短期记忆视图
     */
    @GetMapping("/short-term")
    public ShortTermMemoryResponse shortTerm(
        @RequestParam(value = "sessionId", required = false) String sessionId) {
        return new ShortTermMemoryResponse(messagesOf(resolveSessionId(sessionId)));
    }

    /**
     * 查看长期记忆。
     *
     * @param tenantId 租户标识请求头
     * @param ownerKey 主体标识请求头
     * @return 长期记忆视图
     */
    @GetMapping("/long-term")
    public LongTermMemoryResponse longTerm(
        @RequestHeader(value = ScopeHeaders.HEADER_TENANT_ID, required = false) String tenantId,
        @RequestHeader(value = ScopeHeaders.HEADER_OWNER_KEY, required = false) String ownerKey) {
        MemoryScope scope = ScopeHeaders.resolve(tenantId, ownerKey);
        List<MemoryRecordResponse> records = resolver.withScopeResult(scope,
            () -> MemoryRecordResponse.from(longTermStore.list(LONG_TERM_LIMIT)));
        return new LongTermMemoryResponse(records);
    }

    /**
     * 查看工作记忆。
     *
     * @param tenantId 租户标识请求头
     * @param ownerKey 主体标识请求头
     * @return 工作记忆视图
     */
    @GetMapping("/working")
    public WorkingMemoryResponse working(
        @RequestHeader(value = ScopeHeaders.HEADER_TENANT_ID, required = false) String tenantId,
        @RequestHeader(value = ScopeHeaders.HEADER_OWNER_KEY, required = false) String ownerKey) {
        MemoryScope scope = ScopeHeaders.resolve(tenantId, ownerKey);
        return new WorkingMemoryResponse(resolver.withScopeResult(scope,
            () -> TaskItemResponse.from(workingMemoryStore.tasks())));
    }

    /**
     * 查看情景记忆。
     *
     * @param tenantId 租户标识请求头
     * @param ownerKey 主体标识请求头
     * @return 情景记忆视图
     */
    @GetMapping("/episodic")
    public EpisodicMemoryResponse episodic(
        @RequestHeader(value = ScopeHeaders.HEADER_TENANT_ID, required = false) String tenantId,
        @RequestHeader(value = ScopeHeaders.HEADER_OWNER_KEY, required = false) String ownerKey) {
        MemoryScope scope = ScopeHeaders.resolve(tenantId, ownerKey);
        return new EpisodicMemoryResponse(resolver.withScopeResult(scope,
            () -> EpisodeResponse.from(episodicMemoryStore.episodes())));
    }

    /**
     * 解析出会话标识，缺省回落到默认值。
     *
     * @param sessionId 请求传入的会话标识
     * @return 会话标识
     */
    private String resolveSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? DEFAULT_SESSION_ID : sessionId;
    }

    /**
     * 读取指定会话的消息序列。
     *
     * <p><b>与问答接口读的是同一份状态</b>：问答接口调用智能体时，
     * AgentScope 按 {@code (userId, sessionId)} 把会话状态存进状态存储；
     * 本方法用同样的键去读，因此控制台看到的正是智能体自己看到的上下文。
     *
     * <p><b>用户标识传 {@code null}</b>：问答接口构造运行时上下文时
     * 没有设置用户标识，AgentScope 会把空用户标识归一为 {@code __anon__}；
     * 此处同样传 {@code null}，才能命中同一个状态槽位。
     *
     * @param sessionId 会话标识
     * @return 消息文本列表
     */
    private List<String> messagesOf(String sessionId) {
        List<Msg> context = agent.getAgentState(null, sessionId).getContext();
        List<String> messages = new ArrayList<>(context.size());
        for (Msg message : context) {
            String text = message.getTextContent();
            if (text == null || text.isBlank()) {
                // 纯工具调用消息没有文本内容，展示出来只会是空行，跳过。
                continue;
            }
            messages.add(rolePrefix(message.getRole()) + text);
        }
        return messages;
    }

    /**
     * 把消息角色渲染成可读前缀。
     *
     * @param role 消息角色
     * @return 角色前缀
     */
    private String rolePrefix(MsgRole role) {
        if (role == null) {
            return "";
        }
        return "[" + role.name().toLowerCase(java.util.Locale.ROOT) + "] ";
    }
}
