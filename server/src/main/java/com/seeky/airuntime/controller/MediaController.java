package com.seeky.airuntime.controller;

import com.seeky.airuntime.common.Result;
import com.seeky.airuntime.service.chat.tools.SoulmateTools;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体任务查询：视频是异步出片的，前端拿到 jobId 后按这个接口轮询。
 *
 * <p>接口形态照前端 {@code useChat.js} 的 {@code pollVideo}：成功时要能看到
 * {@code status=succeeded} 与 {@code message.mediaUrl}，失败时给 {@code error} 文案。
 */
@RestController
public class MediaController {

    private final SoulmateTools tools;

    public MediaController(SoulmateTools tools) {
        this.tools = tools;
    }

    @PostMapping("/api/v1/media/job/get")
    public Result<Map<String, Object>> get(@RequestBody(required = false) Map<String, Object> body) {
        String jobId = body == null ? "" : String.valueOf(body.getOrDefault("jobId", ""));
        Map<String, Object> job = tools.job(jobId);
        if (job == null) {
            // 任务丢了（进程重启过）不该让前端一直转圈：明确告诉它可以放弃
            return Result.ok(Map.of("jobId", jobId, "status", "failed",
                    "error", "任务已经不在了，重新让她发一次吧"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", jobId);
        data.put("status", job.getOrDefault("status", "running"));
        data.put("posterOnly", job.getOrDefault("posterOnly", false));
        if (job.get("url") != null) {
            data.put("url", job.get("url"));
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", UUID.randomUUID().toString());
            message.put("text", "（视频）");
            message.put("mediaUrl", job.get("url"));
            message.put("createdAt", java.time.Instant.ofEpochMilli(
                    ((Number) job.getOrDefault("createdAt", System.currentTimeMillis())).longValue()).toString());
            data.put("message", message);
        }
        if (job.get("notice") != null) {
            data.put("notice", job.get("notice"));
        }
        return Result.ok(data);
    }
}
