package com.seeky.airuntime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 陪伴数字人后端启动类。
 *
 * <p>由 seeky-ai-runtime 与 seeky-app-server 合并而来：AI 侧（AgentScope 模型、StateGraph 主对话图、
 * 记忆、语音、媒体）与业务侧（Controller / Service / Repository 三层 + Result 信封）在同一个进程里，
 * 不再有服务间 HTTP/MCP 调用，也没有 Nacos 服务发现。
 *
 * <p>持久化用本机 PostgreSQL（库 {@code ai-soulmate}，只放 users / sessions / messages 三张表），
 * 长期记忆仍然只走 mem0；鉴权是 JWT（HS256），无状态、不引 spring-security。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiRuntimeApplication.class, args);
    }
}
