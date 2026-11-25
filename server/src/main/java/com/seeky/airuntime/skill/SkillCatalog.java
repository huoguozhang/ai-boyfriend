package com.seeky.airuntime.skill;

import io.agentscope.core.skill.AgentSkill;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 场景技能目录（沿用 seeky-ai-runtime 的 SkillCatalog 实现）。
 *
 * <p>与 ai-runtime 的唯一差别是数据来源：那边启动时从 Nacos Skill Registry 全量拉取，这里由
 * {@link LocalSkillLoader} 从 {@code classpath:skills/**\/SKILL.md} 载入内存并整表替换。
 * 对外行为完全一致：
 * <ul>
 *   <li>不校验技能种类与数量，缺一个只是少一条召回候选；</li>
 *   <li>{@code priority} 取自 frontmatter，值越小优先级越高，多条技能要求冲突时由模型按优先级裁决；</li>
 *   <li>{@link #descriptions(String)} 按前缀隔离节点视野。</li>
 * </ul>
 */
@Component
public class SkillCatalog {

    /** SKILL.md frontmatter 里的优先级键。 */
    private static final String PRIORITY_KEY = "priority";

    public static final int DEFAULT_PRIORITY = 100;

    private volatile Map<String, String> contents = Map.of();
    private volatile Map<String, String> descriptions = Map.of();
    private volatile Map<String, Integer> priorities = Map.of();

    /** 整表替换：名字、正文、描述、优先级都取自 SKILL.md。 */
    public void apply(Map<String, AgentSkill> skills) {
        this.contents = Collections.unmodifiableMap(materialize(skills, AgentSkill::getSkillContent));
        this.descriptions = Collections.unmodifiableMap(materialize(skills, AgentSkill::getDescription));
        this.priorities = Collections.unmodifiableMap(priorities(skills));
    }

    public static SkillCatalog of(Map<String, AgentSkill> skills) {
        SkillCatalog catalog = new SkillCatalog();
        catalog.apply(skills);
        return catalog;
    }

    public static SkillCatalog empty() {
        return new SkillCatalog();
    }

    /** 技能正文（未加载到该技能时返回空串）。 */
    public String content(String name) {
        return name == null ? "" : contents.getOrDefault(name, "");
    }

    public boolean contains(String name) {
        return name != null && contents.containsKey(name);
    }

    public int priority(String name) {
        return name == null ? DEFAULT_PRIORITY : priorities.getOrDefault(name, DEFAULT_PRIORITY);
    }

    /** 技能清单（名字 → description），改写/召回节点交给模型判断本轮要哪几条。 */
    public Map<String, String> descriptions() {
        return descriptions;
    }

    /** 某节点可见的技能清单：只保留 prefix 前缀下的技能。 */
    public Map<String, String> descriptions(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return descriptions;
        }
        Map<String, String> scoped = new LinkedHashMap<>();
        descriptions.forEach((name, description) -> {
            if (name.startsWith(prefix)) {
                scoped.put(name, description);
            }
        });
        return scoped;
    }

    public int size() {
        return contents.size();
    }

    private static Map<String, String> materialize(Map<String, AgentSkill> skills,
            Function<AgentSkill, String> extractor) {
        Map<String, String> loaded = new LinkedHashMap<>();
        if (skills != null) {
            skills.forEach((name, skill) -> {
                if (name != null && skill != null) {
                    loaded.put(name, extractor.apply(skill));
                }
            });
        }
        return loaded;
    }

    private static Map<String, Integer> priorities(Map<String, AgentSkill> skills) {
        Map<String, Integer> loaded = new LinkedHashMap<>();
        if (skills != null) {
            skills.forEach((name, skill) -> {
                if (name != null && skill != null) {
                    loaded.put(name, parsePriority(skill.getMetadataValue(PRIORITY_KEY)));
                }
            });
        }
        return loaded;
    }

    private static int parsePriority(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                // 非数字按未声明处理
            }
        }
        return DEFAULT_PRIORITY;
    }
}
