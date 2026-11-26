package com.company.bj.agent.memorykb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChromaDB 服务与集合相关配置。
 *
 * <p>默认值与 {@code application.yml} 中的同名配置保持一致，二者由
 * {@code ConfigurationDefaultsTest} 钉住，防止两处漂移。
 */
@ConfigurationProperties(prefix = "memorykb.chroma")
public class ChromaProperties {

    /** Chroma v2 REST 服务基址，不含任何 /api/v2 路径片段。 */
    private String baseUrl = "http://localhost:8765";

    /** Chroma 租户名，固定为 default_tenant。 */
    private String tenant = "default_tenant";

    /** Chroma 数据库名，固定为 default_database。 */
    private String database = "default_database";

    /** Chroma 持久化数据目录，供启动脚本与健康检查定位。 */
    private String dataPath = "data/chroma";

    /** 知识库集合名，需满足 Chroma 命名规则（3-512 字符，[a-zA-Z0-9._-]，首尾字母数字）。 */
    private String knowledgeCollection = "kb_main";

    /** 长期记忆集合名，需满足 Chroma 命名规则。 */
    private String memoryCollection = "mem_longterm";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDataPath() {
        return dataPath;
    }

    public void setDataPath(String dataPath) {
        this.dataPath = dataPath;
    }

    public String getKnowledgeCollection() {
        return knowledgeCollection;
    }

    public void setKnowledgeCollection(String knowledgeCollection) {
        this.knowledgeCollection = knowledgeCollection;
    }

    public String getMemoryCollection() {
        return memoryCollection;
    }

    public void setMemoryCollection(String memoryCollection) {
        this.memoryCollection = memoryCollection;
    }
}
