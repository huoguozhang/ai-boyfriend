package com.seeky.airuntime.prompt;

import com.seeky.airuntime.common.BusinessException;
import com.seeky.airuntime.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 提示词目录：启动时把 {@code classpath:prompts/**} 下的 .md 一次性读进内存。
 *
 * <p>需求明确要求提示词放本地文件、启动加载到内存，因此这里<b>不做热轮询</b>（不像 ai-runtime 的
 * Prompt Registry 要定时拉远端）：内存里就是全部内容，读一个 prompt 不落任何 IO。
 * 改文案后调 {@code POST /api/v1/prompts/reload} 重建快照——开发时够用，生产环境改完重启更干净。
 *
 * <p>文件格式：
 * <pre>
 * ---
 * title: 角色主提示词
 * group: persona
 * ---
 * 正文，可含 {{name}} 之类的占位符
 * </pre>
 * frontmatter 只用于展示与分组，不参与渲染。占位符缺变量<b>直接抛 5202</b>：静默留空会让 prompt
 * 拼成半句话，模型行为诡异却查不出原因。
 */
@Component
public class PromptCatalog {

    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);

    private static final String LOCATION = "classpath*:prompts/**/*.md";

    /** 占位符：{{name}}，允许两侧空格。 */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private volatile Map<String, Prompt> prompts = Map.of();
    private volatile String loadedAt = null;

    /** 一条提示词：键、正文、文件位置（报错时能直接告诉人改哪个文件）。 */
    public record Prompt(String key, String title, String group, String body, String source) {
    }

    @PostConstruct
    public void load() {
        reload();
        List<String> missing = PromptKeys.REQUIRED.stream().filter(key -> !prompts.containsKey(key)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "缺少必需提示词：" + String.join("、", missing) + "（目录 classpath:prompts）");
        }
        log.info("提示词已载入内存：{} 条，目录 classpath:prompts", prompts.size());
    }

    /** 重新扫描并整表替换，返回条数。 */
    public synchronized int reload() {
        Map<String, Prompt> loaded = new LinkedHashMap<>();
        try {
            Resource[] resources = resolver.getResources(LOCATION);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || filename.startsWith("_")) {
                    continue; // 下划线开头是草稿，不加载
                }
                String key = keyOf(resource);
                if (!key.matches("^[a-z0-9][a-z0-9_]*(\\.[a-z0-9][a-z0-9_]*)+$")) {
                    throw new BusinessException(ErrorCode.RESOURCE_INVALID,
                            "提示词键不合规：" + resource.getFilename() + "（应为小写、点号分层，如 persona.chat.system.md）");
                }
                loaded.put(key, parse(key, resource));
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "提示词目录读取失败：" + ex.getMessage(), ex);
        }
        this.prompts = Map.copyOf(loaded);
        this.loadedAt = java.time.Instant.now().toString();
        return loaded.size();
    }

    /** 取正文（未渲染）。键不存在返回空串，供"可选提示词"场景使用。 */
    public String text(String key) {
        Prompt prompt = prompts.get(key);
        return prompt == null ? "" : prompt.body();
    }

    /** 取正文，不存在直接抛错——用于强依赖的提示词。 */
    public String require(String key) {
        Prompt prompt = prompts.get(key);
        if (prompt == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "提示词不存在：" + key);
        }
        return prompt.body();
    }

    /**
     * 渲染：把 {{var}} 替换成传入的值；渲染后仍残留占位符说明漏传变量，直接抛错。
     */
    public String render(String key, Map<String, ?> vars) {
        String template = require(key);
        Matcher matcher = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = vars == null ? null : vars.get(name);
            if (value == null) {
                missing.add(name);
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(out);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_INVALID,
                    "提示词 " + key + " 缺少变量：" + String.join("、", missing));
        }
        return out.toString();
    }

    /** 目录视图（前端 /prompts/list 用）。 */
    public List<Map<String, Object>> list() {
        return prompts.values().stream()
                .map(prompt -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", prompt.key());
                    item.put("title", prompt.title());
                    item.put("group", prompt.group());
                    item.put("chars", prompt.body().length());
                    item.put("file", prompt.source());
                    return item;
                })
                .sorted((a, b) -> String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key"))))
                .toList();
    }

    public int size() {
        return prompts.size();
    }

    public String loadedAt() {
        return loadedAt;
    }

    /** 相对 prompts/ 的路径去扩展名、斜杠转点：persona/chat.system.md → persona.chat.system。 */
    private String keyOf(Resource resource) throws IOException {
        String path = resource.getURL().getPath();
        int index = path.indexOf("/prompts/");
        String relative = index >= 0 ? path.substring(index + "/prompts/".length()) : resource.getFilename();
        return relative.replaceAll("\\.md$", "").replace('/', '.');
    }

    private Prompt parse(String key, Resource resource) throws IOException {
        String raw;
        try (InputStream in = resource.getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String title = key;
        String group = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        String body = raw;

        String normalized = raw.replace("\r\n", "\n");
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
                    if ("title".equals(field)) {
                        title = value;
                    } else if ("group".equals(field)) {
                        group = value;
                    }
                }
            }
        }
        return new Prompt(key, title, group, body, resource.getFilename());
    }
}
