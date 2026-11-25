package com.seeky.airuntime.controller;

import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.prompt.PromptCatalog;
import com.seeky.airuntime.skill.SkillCatalog;
import com.seeky.airuntime.skill.SkillKeys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词 / 技能资源接口：只读 + 热加载，不做在线编辑。
 *
 * <p>刻意不提供"在网页上改 prompt"：提示词依赖代码传入的变量（memoryBlock、skillBlock），
 * 网页上改出来的内容既没法 diff 评审，又容易和代码脱节。改还是改文件，这里只负责"看"和"重载"。
 */
@RestController
public class PromptController {

    private final PromptCatalog prompts;
    private final SkillCatalog skills;

    public PromptController(PromptCatalog prompts, SkillCatalog skills) {
        this.prompts = prompts;
        this.skills = skills;
    }

    /** 提示词清单。 */
    @PostMapping("/api/v1/prompts/list")
    public Result<Map<String, Object>> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dir", "classpath:prompts");
        body.put("loadedAt", prompts.loadedAt());
        body.put("prompts", prompts.list());
        body.put("skills", skillList());
        return Result.ok(body);
    }

    /** 单条提示词原文（未渲染）。 */
    @PostMapping("/api/v1/prompts/get")
    public Result<Map<String, Object>> get(@RequestBody Map<String, String> request) {
        String key = request.getOrDefault("key", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("content", prompts.require(key));
        return Result.ok(body);
    }

    /** 热加载：重新扫描 classpath 下的提示词与技能。 */
    @PostMapping("/api/v1/prompts/reload")
    public Result<Map<String, Object>> reload() {
        int promptCount = prompts.reload();
        int skillCount = skills.size();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompts", promptCount);
        body.put("skills", skillCount);
        return Result.ok(body);
    }

    /** 技能清单：名字、描述、优先级——排查"她怎么不发照片"的第一站。 */
    @PostMapping("/api/v1/skills/list")
    public Result<List<Map<String, Object>>> skills() {
        return Result.ok(skillList());
    }

    private List<Map<String, Object>> skillList() {
        return skills.descriptions(SkillKeys.CHAT_OUTPUT).entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getKey());
                    item.put("description", entry.getValue());
                    item.put("priority", skills.priority(entry.getKey()));
                    item.put("chars", skills.content(entry.getKey()).length());
                    return item;
                })
                .toList();
    }
}
