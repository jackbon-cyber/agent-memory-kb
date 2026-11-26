package com.company.bj.agent.memorykb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文本嵌入模型相关配置。
 *
 * <p>凭据只从环境变量注入，源码与配置文件中不得出现真实 api key。
 */
@ConfigurationProperties(prefix = "memorykb.embedding")
public class EmbeddingProperties {

    /** OpenAI 兼容模式的模型服务基址。 */
    private String baseUrl = "https://llm-xcr64b2x1zj64tsg.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

    /** 模型服务凭据，默认留空并由环境变量 DASHSCOPE_API_KEY 注入。 */
    private String apiKey = "";

    /** 嵌入模型名称。 */
    private String model = "qwen3.7-text-embedding";

    /** 嵌入向量维度，必须与模型实际输出维度一致，否则 Chroma 集合维度校验会失败。 */
    private int dimensions = 1024;

    /** 单次嵌入请求的最大条数。 */
    private int batchSize = 32;

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

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
