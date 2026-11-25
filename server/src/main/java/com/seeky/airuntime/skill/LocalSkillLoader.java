package com.seeky.airuntime.skill;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.RuntimeSettings;
import io.agentscope.core.skill.AgentSkill;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 技能加载器：启动时扫描 {@code classpath:skills/**\/SKILL.md}，解析 frontmatter 后整表塞进
 * {@link SkillCatalog}（内存），运行期不再读磁盘。
 *
 * <p>目录即技能名：{@code skills/chat/output/send_photo/SKILL.md} → {@code chat.output.send_photo}。
 * 名字不在 {@link SkillKeys#SCOPES} 前缀下直接启动失败——游离的裸名技能没有任何节点会加载它，
 * 留着只会让人误以为"配了却没生效"。
 */
@Component
public class LocalSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(LocalSkillLoader.class);

    private static final String LOCATION = "classpath*:skills/**/SKILL.md";

    /** frontmatter 里的能力依赖键：声明了就要求对应服务可用，否则整条技能不加载。 */
    private static final String CAPABILITY_KEY = "capability";

    private final SkillCatalog catalog;
    private final RuntimeSettings models;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public LocalSkillLoader(SkillCatalog catalog, RuntimeSettings models) {
        this.catalog = catalog;
        this.models = models;
    }

    @PostConstruct
    public void load() {
        Map<String, AgentSkill> skills = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(LOCATION)) {
                String name = nameOf(resource);
                if (name == null) {
                    continue;
                }
                if (SkillKeys.SCOPES.stream().noneMatch(name::startsWith)) {
                    throw new BusinessException(ErrorCode.RESOURCE_INVALID,
                            "技能 " + name + " 不在任何节点前缀下（应为 " + String.join(" / ", SkillKeys.SCOPES) + " 开头）");
                }
                AgentSkill skill = parse(name, resource);
                String capability = String.valueOf(skill.getMetadataValue(CAPABILITY_KEY));
                if (!available(capability)) {
                    // 能力没配就不进目录：否则她会被召回"要发照片"的技能，然后永远发不出来
                    skipped.add(name + "(" + capability + ")");
                    continue;
                }
                skills.put(name, skill);
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "技能目录读取失败：" + ex.getMessage(), ex);
        }
        catalog.apply(skills);
        log.info("技能已载入内存：{} 条，目录 classpath:skills{}", skills.size(),
                skipped.isEmpty() ? "" : "；因能力未配置跳过 " + String.join("、", skipped));
    }

    /**
     * 技能依赖的能力是否可用。
     *
     * <p>没声明 {@code capability} 的技能（纯说话规则）永远可用；声明了就必须那一类服务配好，
     * 这跟工具注册是同一道闸：不进召回清单，模型就不会要求自己发图。
     */
    private boolean available(String capability) {
        if (capability == null || capability.isBlank() || "null".equals(capability)) {
            return true;
        }
        return switch (capability) {
            case "image" -> models.defaults().image().configured();
            case "video" -> models.defaults().video().configured();
            case "tts" -> models.defaults().tts().configured();
            case "asr" -> models.defaults().asr().configured();
            default -> true;
        };
    }

    public int size() {
        return catalog.size();
    }

    /** skills/chat/output/send_photo/SKILL.md → chat.output.send_photo。 */
    private String nameOf(Resource resource) throws IOException {
        String path = resource.getURL().getPath();
        int index = path.indexOf("/skills/");
        if (index < 0) {
            return null;
        }
        String relative = path.substring(index + "/skills/".length());
        relative = relative.replaceAll("/SKILL\\.md$", "");
        if (relative.isBlank() || !relative.contains("/")) {
            return null; // 根目录下的 SKILL.md 不是节点技能，忽略
        }
        return relative.replace('/', '.');
    }

    private AgentSkill parse(String name, Resource resource) throws IOException {
        String raw;
        try (InputStream in = resource.getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String normalized = raw.replace("\r\n", "\n");
        String body = normalized;
        String description = "";
        int priority = SkillCatalog.DEFAULT_PRIORITY;
        String capability = "";

        if (normalized.startsWith("---\n")) {
            int end = normalized.indexOf("\n---", 4);
            if (end > 0) {
                String front = normalized.substring(4, end);
                body = normalized.substring(end + 4).replaceFirst("^\\n", "");
                for (String line : front.split("\n")) {
                    String[] pair = line.split(":", 2);
                    if (pair.length < 2) {
                        continue;
                    }
                    String field = pair[0].trim();
                    String value = pair[1].trim();
                    if ("description".equals(field)) {
                        description = value;
                    } else if (CAPABILITY_KEY.equals(field)) {
                        capability = value;
                    } else if ("priority".equals(field)) {
                        try {
                            priority = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                            priority = SkillCatalog.DEFAULT_PRIORITY;
                        }
                    }
                }
            }
        }
        if (description.isBlank()) {
            throw new BusinessException(ErrorCode.RESOURCE_INVALID,
                    "技能 " + name + " 缺少 description（frontmatter 第一条描述决定它会不会被召回）");
        }
        return AgentSkill.builder()
                .name(name)
                .description(description)
                .skillContent(body.strip())
                .putMetadata("priority", priority)
                .putMetadata(CAPABILITY_KEY, capability)
                .source("classpath:skills/" + name.replace('.', '/') + "/SKILL.md")
                .build();
    }
}
