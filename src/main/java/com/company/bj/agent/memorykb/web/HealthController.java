package com.company.bj.agent.memorykb.web;

import com.company.bj.agent.memorykb.chroma.ChromaHealthIndicator;
import com.company.bj.agent.memorykb.config.AgentProperties;
import com.company.bj.agent.memorykb.web.dto.HealthResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口。
 *
 * <p>报告应用、Chroma 向量库与对话模型三个组件的可用性。
 *
 * <p><b>模型探活不发真实请求</b>：只检查「凭据非空」与「基址已配置」，
 * 两者成立即视为模型配置就绪。理由是健康检查会被编排系统<b>高频</b>调用
 * （探针通常每秒一次），而每次发一次真实推理请求既烧配额又会给网关添压力，
 * 更糟的是一旦网关限流，探针就会把「网关繁忙」误报成「应用不健康」，
 * 引发无谓的重启。
 *
 * <p>代价是「配置正确但模型实际不可用」这一情形不会被探针发现 ——
 * 但那种问题会在第一次真实问答时暴露，而问答失败会明确报出模型侧的原始错误，
 * 排查路径并不比探针更长。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /** 组件名：应用自身。 */
    public static final String COMPONENT_APPLICATION = "application";

    /** 组件名：Chroma 向量库。 */
    public static final String COMPONENT_CHROMA = "chroma";

    /** 组件名：对话模型。 */
    public static final String COMPONENT_MODEL = "model";

    private final ChromaHealthIndicator chromaHealthIndicator;
    private final AgentProperties agentProperties;

    /**
     * 构造健康检查接口。
     *
     * @param chromaHealthIndicator Chroma 健康指示器
     * @param agentProperties       智能体配置，用于判断模型是否已配置就绪
     */
    public HealthController(ChromaHealthIndicator chromaHealthIndicator,
        AgentProperties agentProperties) {
        this.chromaHealthIndicator = chromaHealthIndicator;
        this.agentProperties = agentProperties;
    }

    /**
     * 查询各组件健康状态。
     *
     * @return 健康状态报告，任一组件异常时整体为 {@code DOWN}
     */
    @GetMapping
    public HealthResponse health() {
        Map<String, String> components = new LinkedHashMap<>(3);
        components.put(COMPONENT_APPLICATION, HealthResponse.STATUS_UP);
        components.put(COMPONENT_CHROMA, chromaHealthIndicator.isHealthy()
            ? HealthResponse.STATUS_UP : HealthResponse.STATUS_DOWN);
        components.put(COMPONENT_MODEL, isModelConfigured()
            ? HealthResponse.STATUS_UP : HealthResponse.STATUS_DOWN);

        String overall = components.values().stream()
            .allMatch(HealthResponse.STATUS_UP::equals)
            ? HealthResponse.STATUS_UP : HealthResponse.STATUS_DOWN;
        return new HealthResponse(overall, components);
    }

    /**
     * 判断对话模型是否已配置就绪。
     *
     * @return 凭据非空且基址已配置时返回 {@code true}
     */
    private boolean isModelConfigured() {
        boolean hasCredential = agentProperties.getApiKey() != null
            && !agentProperties.getApiKey().isBlank();
        boolean hasBaseUrl = agentProperties.getBaseUrl() != null
            && !agentProperties.getBaseUrl().isBlank();
        return hasCredential && hasBaseUrl;
    }
}
