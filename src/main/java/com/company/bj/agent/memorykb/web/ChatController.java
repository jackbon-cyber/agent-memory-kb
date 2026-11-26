package com.company.bj.agent.memorykb.web;

import com.company.bj.agent.memorykb.agent.ChatResult;
import com.company.bj.agent.memorykb.agent.KnowledgeAgent;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import com.company.bj.agent.memorykb.memory.ScopeResolver;
import com.company.bj.agent.memorykb.web.dto.ChatRequest;
import com.company.bj.agent.memorykb.web.dto.ChatResponse;
import com.company.bj.agent.memorykb.web.dto.HitResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体问答接口。
 *
 * <p>把用户问题交给智能体，返回回答与本次实际引用的知识库切片。
 *
 * <p><b>作用域在控制器这一层建立</b>：智能体内部通过
 * {@code RuntimeContext.extras} 传递作用域（见 {@link KnowledgeAgent}），
 * 但它<b>捕获的是调用线程上已有的作用域</b> —— 若控制器不先建立作用域，
 * 智能体会在捕获阶段就以「no active memory scope」失败。
 * 因此这里的 {@code withScopeResult} 不是装饰，而是智能体能够工作的前提。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final KnowledgeAgent knowledgeAgent;
    private final ScopeResolver resolver;

    /**
     * 构造问答接口。
     *
     * @param knowledgeAgent 知识问答智能体
     * @param resolver       作用域解析器
     */
    public ChatController(KnowledgeAgent knowledgeAgent, ScopeResolver resolver) {
        this.knowledgeAgent = knowledgeAgent;
        this.resolver = resolver;
    }

    /**
     * 向智能体提问。
     *
     * @param request  问答请求
     * @param tenantId 租户标识请求头
     * @param ownerKey 主体标识请求头
     * @return 回答与引用
     * @throws IllegalArgumentException 提问内容为空时抛出
     */
    @PostMapping
    public ChatResponse chat(
        @RequestBody ChatRequest request,
        @RequestHeader(value = ScopeHeaders.HEADER_TENANT_ID, required = false) String tenantId,
        @RequestHeader(value = ScopeHeaders.HEADER_OWNER_KEY, required = false) String ownerKey) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        MemoryScope scope = ScopeHeaders.resolve(tenantId, ownerKey);
        // 智能体返回 Mono，但本次调用是同步的 HTTP 请求：
        // 在作用域内阻塞取结果，保证作用域在整个调用期间有效。
        ChatResult result = resolver.withScopeResult(scope,
            () -> knowledgeAgent.ask(request.question(), request.sessionId()).block());
        if (result == null) {
            return new ChatResponse("", List.of());
        }

        List<HitResponse> citations = new ArrayList<>(result.citations().size());
        for (RetrievedChunk citation : result.citations()) {
            citations.add(HitResponse.from(citation));
        }
        return new ChatResponse(result.answer(), citations);
    }
}
