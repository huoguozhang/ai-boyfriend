package com.seeky.airuntime.service.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期扫描：把到点的 pending 翻成 due，让前端能拉到。
 *
 * <p>扫描只管"翻状态"，不负责"提醒响铃"——真正的送达是前端拉 {@code /reminders/due} 后弹卡片。
 * 所以这个任务就算漏跑一轮也没关系，下一轮照样补上：它是幂等的，只认状态和时刻。
 *
 * <p>频率是 10 秒一次：一次提醒迟到几秒没人察觉，但扫太勤纯属空转。
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminders;

    public ReminderScheduler(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${seeky.reminder.scan-interval-ms:10000}",
            initialDelayString = "${seeky.reminder.scan-delay-ms:15000}")
    public void scan() {
        int due = reminders.sweep();
        if (due > 0) {
            log.info("[reminder] 本轮有 {} 条提醒到期待投递", due);
        }
    }
}
