package com.company.bj.agent.memorykb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话模型（智能体）相关配置。
 *
 * <p>凭据只从环境变量注入，源码与配置文件中不得出现真实 api key。
 *
 * <p><b>模型名不带厂商私有前缀</b>：部分专有网关要求模型名带租户前缀，
 * 而通用的 OpenAI 兼容端点要求裸模型名（带前缀会返回"产品未激活"），
 * 因此默认值固定为 {@value #DEFAULT_MODEL}。
 */
@ConfigurationProperties(prefix = "memorykb.agent")
public class AgentProperties {

    /** 默认对话模型名。 */
    public static final String DEFAULT_MODEL = "deepseek-v4.1-flash-1017";

    /** 默认智能体名称。 */
    public static final String DEFAULT_NAME = "knowledge-agent";

    /** 默认 ReAct 循环最大轮数。 */
    public static final int DEFAULT_MAX_ITERS = 8;

    /** OpenAI 兼容模式的模型服务基址。 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 模型服务凭据，默认留空并由环境变量 DASHSCOPE_API_KEY 注入。 */
    private String apiKey = "";

    /** 对话模型名称。 */
    private String model = DEFAULT_MODEL;

    /** 智能体名称，同时作为会话状态的隔离维度之一。 */
    private String name = DEFAULT_NAME;

    /** 系统提示词，约束智能体的检索与作答方式。 */
    private String sysPrompt = """
        你是企业知识库的问答助手。请遵循以下工作方式：
        1. 回答前先调用 search_knowledge 检索知识库，检索结果才是事实依据；
        2. 需要跨会话复用的事实，调用 record_to_memory 记录到长期记忆；
        3. 需要了解用户此前的偏好或结论时，调用 retrieve_from_memory 检索长期记忆；
        4. 检索结果中没有依据的内容，明确说明"知识库中未找到相关内容"，不要编造；
        5. 回答使用简体中文，并在结尾列出所引用的知识库片段来源。
        """;

    /** ReAct 循环的最大轮数，超出后强制收敛输出。 */
    private int maxIters = DEFAULT_MAX_ITERS;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }
}
