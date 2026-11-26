package com.company.bj.agent.memorykb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆层运行时配置。
 *
 * <p>除查询向量缓存 TTL 外，还承载工作记忆与情景记忆的<b>持久化路径</b>：
 * 这两层记忆需要跨进程重启保留内容，因此落盘为 JSON 文件，
 * 路径由 {@code data-path} 与两个文件名共同决定。
 */
@ConfigurationProperties(prefix = "memorykb.memory")
public class MemoryProperties {

    /** 查询向量缓存存活秒数，过期后重新向模型服务请求嵌入。 */
    private long queryCacheTtlSeconds = 60;

    /** 记忆持久化数据目录，工作记忆与情景记忆的 JSON 文件都落在这里。 */
    private String dataPath = "data/memory";

    /** 工作记忆持久化文件名，位于 {@link #dataPath} 之下。 */
    private String workingMemoryFile = "working-memory.json";

    /** 情景记忆持久化文件名，位于 {@link #dataPath} 之下。 */
    private String episodicMemoryFile = "episodic-memory.json";

    public long getQueryCacheTtlSeconds() {
        return queryCacheTtlSeconds;
    }

    public void setQueryCacheTtlSeconds(long queryCacheTtlSeconds) {
        this.queryCacheTtlSeconds = queryCacheTtlSeconds;
    }

    public String getDataPath() {
        return dataPath;
    }

    public void setDataPath(String dataPath) {
        this.dataPath = dataPath;
    }

    public String getWorkingMemoryFile() {
        return workingMemoryFile;
    }

    public void setWorkingMemoryFile(String workingMemoryFile) {
        this.workingMemoryFile = workingMemoryFile;
    }

    public String getEpisodicMemoryFile() {
        return episodicMemoryFile;
    }

    public void setEpisodicMemoryFile(String episodicMemoryFile) {
        this.episodicMemoryFile = episodicMemoryFile;
    }
}
