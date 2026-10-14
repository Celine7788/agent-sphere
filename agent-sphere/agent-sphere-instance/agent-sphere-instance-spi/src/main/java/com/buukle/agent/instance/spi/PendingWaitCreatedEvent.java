package com.buukle.agent.instance.spi;

import java.io.Serializable;
import java.time.Instant;

/**
 * 一条等待记录已创建。
 *
 * <p>由 {@code PendingWaitService#createPending} 用 Spring 本地事件发布，
 * 由 orchestration 模块的 {@code PendingWaitWakeScheduler} 监听并注册内存定时器。
 *
 * <p>之所以走事件而不是让 SPI 直接调唤醒逻辑：kernel（ToolExecutor）只能依赖
 * instance-spi，而真正恢复 run 的 {@code ChatRuntimeService#resumeFromWait} 在
 * orchestration —— 依赖方向是 orchestration → kernel，反向调用会造成环。
 * Spring 本地事件正是这个仓库既有的跨模块解法（RuntimeEventVO 同理）。
 */
public record PendingWaitCreatedEvent(Long waitId, Long sessionId, Long runId, Instant wakeAt) implements Serializable {
}