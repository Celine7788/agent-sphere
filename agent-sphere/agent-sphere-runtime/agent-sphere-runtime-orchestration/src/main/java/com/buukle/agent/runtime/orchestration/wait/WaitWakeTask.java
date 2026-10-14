package com.buukle.agent.runtime.orchestration.wait;

import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * wait 唤醒的补偿路径：内存定时器丢失（副本重启/发布）时兜底。
 *
 * <p>与 {@link PendingWaitWakeScheduler} 共用 {@code claimForResume} 的 CAS，
 * 所以两者同时命中同一条记录也只会恢复一次 —— 这里不需要「谁先谁后」的约定。
 *
 * <p>k8s 里 backend 是多副本，必须持 Redisson 锁再扫，避免每副本都去抢同批记录
 * （虽然 CAS 兜住了重复恢复，事件广播仍会重复）。
 */
@Slf4j
@Component
public class WaitWakeTask {

    private static final String WAKE_LOCK_KEY = "scheduler:pending-wait";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    /** 允许的扫描滞后：内存定时器是主路径，cron 只需兜底，容忍几秒抖动。 */
    private static final Duration OVERDUE_GRACE = Duration.ofSeconds(2);
    private static final int BATCH_LIMIT = 100;

    private final PendingWaitSpi pendingWaitSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final RedissonClient redissonClient;

    public WaitWakeTask(PendingWaitSpi pendingWaitSpi,
                        ChatRuntimeService chatRuntimeService,
                        RedissonClient redissonClient) {
        this.pendingWaitSpi = pendingWaitSpi;
        this.chatRuntimeService = chatRuntimeService;
        this.redissonClient = redissonClient;
    }

    @Scheduled(cron = "${buukle.agent.wait.wake-cron:*/10 * * * * ?}")
    public void sweepDueWaits() {
        boolean locked = false;
        try {
            locked = redissonClient.getLock(WAKE_LOCK_KEY)
                    .tryLock(0, LOCK_LEASE.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                return;
            }
            List<PendingWaitVO> due = pendingWaitSpi.listDue(OVERDUE_GRACE, BATCH_LIMIT);
            if (due.isEmpty()) {
                return;
            }
            log.info("Wait wake sweep: {} due record(s)", due.size());
            for (PendingWaitVO wait : due) {
                if (!pendingWaitSpi.claimForResume(wait.getId())) {
                    continue;
                }
                try {
                    PendingWaitVO claimed = pendingWaitSpi.getById(wait.getId());
                    if (claimed != null) {
                        chatRuntimeService.resumeFromWait(claimed);
                    }
                } catch (Exception e) {
                    // 单条失败不拖垮整批：其余等待仍应被唤醒
                    log.error("Failed to resume pending wait {}", wait.getId(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Wait wake sweep failed", e);
        } finally {
            if (locked) {
                try {
                    if (redissonClient.getLock(WAKE_LOCK_KEY).isHeldByCurrentThread()) {
                        redissonClient.getLock(WAKE_LOCK_KEY).unlock();
                    }
                } catch (Exception e) {
                    log.warn("Failed to release wait wake lock", e);
                }
            }
        }
    }
}