package com.company.bj.agent.memorykb;

import com.company.bj.agent.memorykb.config.AgentProperties;
import com.company.bj.agent.memorykb.config.ChromaProperties;
import com.company.bj.agent.memorykb.config.EmbeddingProperties;
import com.company.bj.agent.memorykb.config.MemoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * AgentScope 记忆与知识库平台启动入口。
 *
 * <p>四类配置属性在此显式注册，保证它们在任意 Spring Profile 下都能被注入，
 * 而不依赖组件扫描位置带来的隐式注册。
 */
@SpringBootApplication
@EnableConfigurationProperties({ChromaProperties.class, EmbeddingProperties.class, MemoryProperties.class,
    AgentProperties.class})
public class AgentMemoryKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentMemoryKbApplication.class, args);
    }
}
