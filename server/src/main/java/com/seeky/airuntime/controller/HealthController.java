package com.seeky.airuntime.controller;

import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.config.Mem0Properties;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.prompt.PromptCatalog;
import com.seeky.airuntime.skill.SkillCatalog;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：一眼看清"哪些能力能用、提示词和技能有没有载入"。
 *
 * <p>路径与 ai-runtime 一致（{@code /ai/health}）同时保留 {@code /api/health} 供前端与运维脚本使用；
 * 返回体只暴露 {@code configured / mock / missing}，不含任何 Key。
 */
@RestController
public class HealthController {

    private final RuntimeSettings models;
    private final Mem0Properties mem0;
    private final PromptCatalog prompts;
    private final SkillCatalog skills;
    private final long startedAt = System.currentTimeMillis();

    public HealthController(RuntimeSettings models, Mem0Properties mem0,
            PromptCatalog prompts, SkillCatalog skills) {
        this.models = models;
        this.mem0 = mem0;
        this.prompts = prompts;
        this.skills = skills;
    }

    @GetMapping({"/api/health", "/ai/health"})
    public Result<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "ai-soulmate-server");
        body.put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000);
        body.put("jvm", System.getProperty("java.version"));
        body.put("pid", ManagementFactory.getRuntimeMXBean().getName());

        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("llm", describe(models.defaults().llm()));
        capability.put("asr", describe(models.defaults().asr()));
        capability.put("tts", describe(models.defaults().tts()));
        capability.put("image", describe(models.defaults().image()));
        capability.put("video", describe(models.defaults().video()));
        body.put("capabilities", capability);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("prompts", prompts.size());
        resources.put("skills", skills.size());
        resources.put("loadedAt", prompts.loadedAt());
        body.put("resources", resources);

        // 记忆只报「配到哪、开没开」，不含 Key；实际可用性看 /api/v1/memory/overview/get 的 degraded
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("configured", mem0.configured());
        memory.put("baseUrl", mem0.baseUrl());
        memory.put("agentId", mem0.agentId());
        memory.put("searchLimit", mem0.searchLimitOrDefault());
        body.put("memory", memory);
        return Result.ok(body);
    }

    private Map<String, Object> describe(ModelProperties.Endpoint endpoint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("configured", endpoint.configured());
        item.put("mock", endpoint.mock());
        item.put("missing", endpoint.missing());
        return item;
    }
}
