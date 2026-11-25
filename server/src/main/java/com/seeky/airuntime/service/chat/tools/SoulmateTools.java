package com.seeky.airuntime.service.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.seeky.airuntime.config.ModelProperties;
import com.seeky.airuntime.config.RuntimeSettings;
import com.seeky.airuntime.dto.Mem0Result;
import com.seeky.airuntime.dto.MemoryItem;
import com.seeky.airuntime.service.memory.MemoryService;
import com.seeky.airuntime.service.reminder.ReminderService;
import com.seeky.airuntime.store.ReminderItem;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 她会用的四个工具：发照片、发视频、记住、想起来。
 *
 * <p>只注册**真能干活**的：能力没配的（图/视频服务没开）连工具定义都不给模型，
 * 这样她不会承诺"等下拍给你"然后永远发不出来 —— 这是 ai-runtime 那边踩过的坑，
 * 承诺兑现不了比干脆不会更伤。
 *
 * <p>参数 schema 写在 Java 里（代码契约），描述面向"什么时候该调我"，与 SKILL.md 里的
 * "本轮行为规则"分开：技能管怎么说话，工具管什么时候动手。
 */
@Component
public class SoulmateTools {

    private static final Logger log = LoggerFactory.getLogger(SoulmateTools.class);

    public static final String SEND_PHOTO = "send_photo";
    public static final String SEND_VIDEO = "send_video";
    public static final String REMEMBER = "remember";
    public static final String RECALL = "recall";
    public static final String SET_REMINDER = "set_reminder";
    public static final String LIST_REMINDERS = "list_reminders";
    public static final String CANCEL_REMINDER = "cancel_reminder";

    /** 工具分组：意图判明后按组挂，避免"闲聊时手滑调了发照片"。 */
    public static final List<String> MEDIA_TOOLS = List.of(SEND_PHOTO, SEND_VIDEO);
    public static final List<String> MEMORY_TOOLS = List.of(REMEMBER, RECALL);
    public static final List<String> REMINDER_TOOLS = List.of(SET_REMINDER, LIST_REMINDERS, CANCEL_REMINDER);

    /** 工具执行等 mem0/上游的上限：超过这个就不值得让整轮对话等着了。 */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

    private final RuntimeSettings models;
    private final MemoryService memory;
    private final ReminderService reminders;

    /** 视频任务：真模型是异步出片的，这里用内存表撑住前端的轮询。 */
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();

    public SoulmateTools(RuntimeSettings models, MemoryService memory, ReminderService reminders) {
        this.models = models;
        this.memory = memory;
        this.reminders = reminders;
    }

    /** OpenAI function 定义；能力缺失的那几个不出现。 */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (models.defaults().image().configured()) {
            tools.add(function(SEND_PHOTO,
                    "拍一张照片发给他。只有他明确想看照片/自拍时才调用；一次一张。",
                    Map.of(
                            "scene", Map.of("type", "string",
                                    "description", "照片内容：地点、光线、穿着、正在做什么。写给画面看，不是写给他看"),
                            "caption", Map.of("type", "string",
                                    "description", "配一句话，像随手发照片时说的那种，不超过 20 字")),
                    List.of("scene")));
        }
        if (models.defaults().video().configured()) {
            tools.add(function(SEND_VIDEO,
                    "录一小段视频发给他。只有他明确要视频时才调用；很慢，调用前先说一句等他一下。",
                    Map.of("scene", Map.of("type", "string",
                            "description", "镜头怎么动、她在做什么、几秒。写给画面看")),
                    List.of("scene")));
        }
        tools.add(function(REMEMBER,
                "记住关于他的一件长期有用的事（名字、城市、工作、喜好、计划、关系）。闲聊本身不要记。",
                Map.of(
                        "text", Map.of("type", "string", "description", "一句话事实，用「他…」开头"),
                        "slot", Map.of("type", "string",
                                "description", "归类槽位：profile.name / profile.city / preference.food 这种，便于覆盖旧值")),
                List.of("text")));
        tools.add(function(RECALL,
                "想一下关于他的某件事。当你不确定细节、或他问「你还记得吗」时先调它。",
                Map.of("query", Map.of("type", "string", "description", "要回忆的主题，一句话")),
                List.of("query")));
        tools.add(function(SET_REMINDER,
                "给他设一条到点会主动提醒的待办。只要他说「提醒我…」「别忘了…」「到点叫我」，"
                        + "就必须调用它落一条；只嘴上答应会导致到点想不起来。时间说不清就先别调，反问他。",
                Map.of(
                        "content", Map.of("type", "string",
                                "description", "提醒什么，写成一句单看也懂的话，例如「吃两片维C」"),
                        "kind", Map.of("type", "string",
                                "description", "类型：medication / water / sleep / wake / "
                                        + "appointment / todo / anniversary / other"),
                        "remindIn", Map.of("type", "string",
                                "description", "相对时间，如 3d / 2h30m / 30m / 1w / 2mo。"
                                        + "他说「三天后」「半小时后」就用它，不要自己算日期"),
                        "remindAt", Map.of("type", "string",
                                "description", "绝对时间，格式 2026-11-28 20:00（24 小时制、必须带年份）。"
                                        + "他说「明天八点」「28号下午三点」时用")),
                List.of("content")));
        tools.add(function(LIST_REMINDERS,
                "看看他现在都有哪些还没兑现的提醒。他问「我都让你记了什么」「我什么时候要干什么」时调用。",
                Map.of(), List.of()));
        tools.add(function(CANCEL_REMINDER,
                "取消一条还没兑现的提醒。他说「不用提醒我…了」「那个提醒算了」时调用。",
                Map.of("hint", Map.of("type", "string",
                        "description", "要取消的那件事的关键词，如「吃药」。他说「刚才那个」「最近那个」就留空")),
                List.of()));
        return tools;
    }

    public boolean empty() {
        return definitions().isEmpty();
    }

    /** 只给这几个工具（意图已经判明是 Action 时用），其余一律不出现，模型就没有机会乱调。 */
    public List<Map<String, Object>> definitions(java.util.Collection<String> only) {
        List<Map<String, Object>> all = definitions();
        if (only == null || only.isEmpty()) {
            return all;
        }
        List<Map<String, Object>> scoped = new ArrayList<>();
        for (Map<String, Object> tool : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            if (function != null && only.contains(String.valueOf(function.get("name")))) {
                scoped.add(tool);
            }
        }
        return scoped;
    }

    /**
     * 执行一次工具调用。
     *
     * @param name      工具名（模型给的，可能不存在）
     * @param args      参数（模型给的，可能缺字段）
     * @param userId    当前登录用户（提醒与记忆都按他隔离，绝不能用默认用户）
     * @param sessionId 当前会话（提醒记下来源，方便以后"这句话是哪轮说的"）
     */
    public Execution execute(String name, JsonNode args, String userId, String sessionId) {
        try {
            return switch (name) {
                case SEND_PHOTO -> photo(text(args, "scene"), text(args, "caption"));
                case SEND_VIDEO -> video(text(args, "scene"));
                case REMEMBER -> remember(text(args, "text"), text(args, "slot"), userId);
                case RECALL -> recall(text(args, "query"), userId);
                case SET_REMINDER -> setReminder(text(args, "content"), text(args, "kind"),
                        text(args, "remindAt"), text(args, "remindIn"), userId, sessionId);
                case LIST_REMINDERS -> listReminders(userId);
                case CANCEL_REMINDER -> cancelReminder(text(args, "hint"), userId);
                default -> Execution.failed("没有这个工具：" + name);
            };
        } catch (RuntimeException ex) {
            log.warn("[tool] {} 执行失败: {}", name, ex.getMessage());
            return Execution.failed(ex.getMessage());
        }
    }

    public Map<String, Object> job(String jobId) {
        return jobs.get(jobId);
    }

    // ------------------------------------------------------------------ 各工具

    /**
     * 发照片。
     *
     * <p>当前接的是 mock 图片协议：不联网，生成一张带场景文字的占位图（内联 SVG），
     * 好让"工具调用 → 媒体帧 → 前端气泡"这条链路能整条走通。真接上图像模型后，
     * 这里换成生成的图片 URL 即可，其余代码不用动。
     */
    private Execution photo(String scene, String caption) {
        if (scene.isBlank()) {
            return Execution.failed("缺少 scene");
        }
        String url = placeholderSvg("照片", scene, 600, 800);
        log.info("[tool] send_photo scene={}", shorten(scene));
        return new Execution("image", url, caption, null, null, false, true);
    }

    /** 发视频：先回一个"在生成"，前端拿 jobId 轮询；mock 下 6 秒后给一张封面。 */
    private Execution video(String scene) {
        if (scene.isBlank()) {
            return Execution.failed("缺少 scene");
        }
        String jobId = UUID.randomUUID().toString();
        Map<String, Object> job = new ConcurrentHashMap<>();
        job.put("jobId", jobId);
        job.put("status", "running");
        job.put("scene", scene);
        job.put("createdAt", System.currentTimeMillis());
        jobs.put(jobId, job);
        log.info("[tool] send_video job={} scene={}", jobId, shorten(scene));

        // mock 视频：没有真模型时不该假装成功，只把封面给他，并明确告知这是占位
        job.put("status", "succeeded");
        job.put("posterOnly", true);
        job.put("url", placeholderSvg("视频封面", scene, 720, 1280));
        job.put("notice", "视频暂为占位封面：接到真实视频模型后这里会出片");
        return new Execution("video", String.valueOf(job.get("url")), "", jobId,
                "视频暂为占位封面：接到真实视频模型后这里会出片", false, true);
    }

    /** 让她主动记一件事：直接写 mem0，槽位相同的旧值会被顶掉。 */
    private Execution remember(String text, String slot, String userId) {
        if (text.isBlank()) {
            return Execution.failed("缺少 text");
        }
        // 必须写当前登录用户：写死 DEFAULT_USER_ID 会让所有账号共用一份记忆
        String owner = memory.resolveUser(userId);
        // 工具是同步契约（模型的 function call 也是一问一答），这里阻塞等 mem0 回话；
        // mem0 在本地 docker 里，正常情况下 100ms 内返回
        Mem0Result<MemoryItem> result = memory.remember(owner, null,
                slot.isBlank() ? "manual." + UUID.randomUUID().toString().substring(0, 8) : slot,
                text, "other", 0.6).block(TOOL_TIMEOUT);
        if (result == null || result.degraded()) {
            return Execution.failed("记忆没写进去：" + (result == null ? "超时" : result.reason()));
        }
        log.info("[tool] remember user={} slot={} text={}", owner, slot, shorten(text));
        return new Execution("memory", null, "", null, null, true, false);
    }

    /** 想起来：语义检索 mem0，把命中的事实回给模型。 */
    private Execution recall(String query, String userId) {
        if (query.isBlank()) {
            return Execution.failed("缺少 query");
        }
        String owner = memory.resolveUser(userId);
        Mem0Result<List<MemoryItem>> result = memory.recall(owner, query, 6)
                .block(TOOL_TIMEOUT);
        if (result == null || result.degraded() || result.value() == null) {
            return Execution.failed("暂时想不起来：" + (result == null ? "超时" : result.reason()));
        }
        String facts = result.value().stream()
                .map(MemoryItem::memory)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
        log.info("[tool] recall query={} 命中 {} 条", shorten(query), result.value().size());
        return new Execution("memory", null, "", null, null, false, false,
                facts.isBlank() ? "（没有想起相关的事）" : facts);
    }

    // ------------------------------------------------------------------ 提醒

    /**
     * 设一条提醒。
     *
     * <p>时间解析失败**不能**降级成"先随便记一个"：算错时间的提醒比没有更糟。
     * 这里把原因原样回给模型（"这个时间已经过去了"），由她照实说出来再问他，
     * 而不是悄悄猜一个时刻 —— 猜出来的那一刻用户就已经失去控制了。
     */
    private Execution setReminder(String content, String kindRaw, String remindAt, String remindIn,
            String userId, String sessionId) {
        ReminderService.Created created;
        try {
            created = reminders.create(userId, sessionId, kindRaw, content, remindAt, remindIn);
        } catch (IllegalArgumentException ex) {
            log.info("[tool] set_reminder 没落库：{} (remindAt={}, remindIn={})",
                    ex.getMessage(), remindAt, remindIn);
            return Execution.failed(ex.getMessage());
        }
        String line = created.humanText() + " · " + created.kind().label();
        log.info("[tool] set_reminder user={} id={} -> {}", userId, created.id(), line);
        return new Execution("reminder", null, created.content(), null,
                "⏰ 记下了：" + line, false, false,
                "已记下「" + created.content() + "」，时间是 " + created.humanText()
                        + "（类型 " + created.kind().label() + "）。回话时照这个时间说，不要自己换算。");
    }

    /** 他现在有哪些还没兑现的提醒。 */
    private Execution listReminders(String userId) {
        List<ReminderItem> items = reminders.upcoming(userId, 12);
        if (items.isEmpty()) {
            return new Execution("none", null, "", null, null, false, false,
                    "他现在没有任何未完成的提醒。");
        }
        StringBuilder lines = new StringBuilder("他现在有 " + items.size() + " 条还没到点的提醒：\n");
        for (ReminderItem item : items) {
            lines.append("- ").append(reminders.line(item)).append('\n');
        }
        return new Execution("none", null, "", null, null, false, false, lines.toString().strip());
    }

    /** 取消一条。找不到就如实说没找到，绝不假装取消成功。 */
    private Execution cancelReminder(String hint, String userId) {
        ReminderItem cancelled = reminders.cancel(userId, hint);
        if (cancelled == null) {
            return Execution.failed(hint.isBlank()
                    ? "他现在没有任何待办的提醒可以取消"
                    : "没找到和「" + hint + "」对得上的提醒");
        }
        String line = reminders.line(cancelled);
        log.info("[tool] cancel_reminder user={} id={}", userId, cancelled.id());
        return new Execution("none", null, "", null, "已取消：" + line, false, false,
                "已经取消掉这条提醒：" + line);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 占位图：内联 SVG data URI。
     *
     * <p>刻意不引外部图床 —— Demo 要在断网/内网环境也能跑起来，而且占位图不该被误当成真照片。
     */
    private String placeholderSvg(String label, String scene, int width, int height) {
        String title = escape(label);
        String line = escape(shorten(scene));
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
                  <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0%%" stop-color="#3a2b3f"/><stop offset="100%%" stop-color="#1b1720"/>
                  </linearGradient></defs>
                  <rect width="100%%" height="100%%" fill="url(#g)"/>
                  <text x="50%%" y="46%%" fill="#f1d9e6" font-size="%d" text-anchor="middle"
                        font-family="PingFang SC, sans-serif">%s</text>
                  <text x="50%%" y="56%%" fill="#b9a7b6" font-size="%d" text-anchor="middle"
                        font-family="PingFang SC, sans-serif">%s</text>
                </svg>
                """.formatted(width, height, width, height, Math.max(18, width / 18), title,
                Math.max(13, width / 30), line);
        return "data:image/svg+xml;charset=utf-8," + URLEncoder.encode(svg, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private Map<String, Object> function(String name, String description,
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }

    private String text(JsonNode args, String field) {
        if (args == null) {
            return "";
        }
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private String escape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String shorten(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 40 ? raw.substring(0, 40) + "…" : raw;
    }

    /**
     * 一次工具执行的结果。
     *
     * @param kind           image / video / memory
     * @param url            媒体地址（memory 类为 null）
     * @param caption        配文
     * @param jobId          异步任务 id（视频用）
     * @param notice         要提示给用户的话（能力不足、降级）
     * @param memoryChanged  要不要触发前端刷新记忆页
     * @param media          有没有东西要显示出来
     * @param toolResult     回给模型的文本（recall 的事实、或失败原因）
     */
    public record Execution(String kind, String url, String caption, String jobId, String notice,
            boolean memoryChanged, boolean media, String toolResult) {

        public Execution(String kind, String url, String caption, String jobId, String notice,
                boolean memoryChanged, boolean media) {
            this(kind, url, caption, jobId, notice, memoryChanged, media, "");
        }

        static Execution failed(String reason) {
            return new Execution("none", null, "", null, null, false, false, reason);
        }
    }
}
