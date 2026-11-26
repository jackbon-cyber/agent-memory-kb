package com.company.bj.agent.memorykb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 校验三类配置属性的默认值与全局约束中的规格一致。
 *
 * <p>默认值同时存在于 {@code application.yml} 与 Java 字段初始化两处，此测试钉住 Java 侧取值，
 * 防止后续 Task 修改配置时出现两处不一致。
 */
class ConfigurationDefaultsTest {

    @Test
    void 默认配置符合规格() {
        ChromaProperties chroma = new ChromaProperties();
        assertThat(chroma.getBaseUrl()).isEqualTo("http://localhost:8765");
        assertThat(chroma.getTenant()).isEqualTo("default_tenant");
        assertThat(chroma.getDatabase()).isEqualTo("default_database");
        assertThat(chroma.getKnowledgeCollection()).isEqualTo("kb_main");
        assertThat(chroma.getMemoryCollection()).isEqualTo("mem_longterm");

        EmbeddingProperties embedding = new EmbeddingProperties();
        assertThat(embedding.getModel()).isEqualTo("qwen3.7-text-embedding");
        assertThat(embedding.getDimensions()).isEqualTo(1024);
        assertThat(embedding.getBatchSize()).isEqualTo(32);

        MemoryProperties memory = new MemoryProperties();
        assertThat(memory.getQueryCacheTtlSeconds()).isEqualTo(60);
        // 持久化路径的默认值与 memorykb.chroma.data-path 的命名风格保持一致（都在 data/ 之下）。
        assertThat(memory.getDataPath()).isEqualTo("data/memory");
        assertThat(memory.getWorkingMemoryFile()).isEqualTo("working-memory.json");
        assertThat(memory.getEpisodicMemoryFile()).isEqualTo("episodic-memory.json");

        AgentProperties agent = new AgentProperties();
        assertThat(agent.getModel()).isEqualTo("deepseek-v4.1-flash-1017");
        assertThat(agent.getName()).isEqualTo("knowledge-agent");
        assertThat(agent.getMaxIters()).isEqualTo(8);
        // 模型名不得带 vanchin/ 前缀：带前缀访问百炼网关会返回「产品未激活」。
        assertThat(agent.getModel()).doesNotStartWith("vanchin/");
        // 凭据默认留空，只能由环境变量注入。
        assertThat(agent.getApiKey()).isEmpty();
    }

    @Test
    void 配置文件与Java侧默认值保持一致() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains("query-cache-ttl-seconds: 60");
        assertThat(yaml).contains("data-path: data/memory");
        assertThat(yaml).contains("working-memory-file: working-memory.json");
        assertThat(yaml).contains("episodic-memory-file: episodic-memory.json");
        assertThat(yaml).contains("model: deepseek-v4.1-flash-1017");
        assertThat(yaml).contains("name: knowledge-agent");
        assertThat(yaml).contains("max-iters: 8");
    }

    @Test
    void 配置文件不含真实密钥() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).doesNotContain("sk-0f12");
    }
}
