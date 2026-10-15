package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;

import java.time.Duration;
import java.util.List;

public interface PendingWaitSpi {

    /**
     * 写入一条挂起记录并触发内存定时器（由实现内部发布本地事件完成）。
     *
     * <p>调用方（ToolExecutor 拦截 wait 工具）拿到的返回值只用于日志：真正的
     * 「是否挂起」由工具返回的 {@code {"status":"waiting"}} 表达。
     *
     * @param waitMs 实际等待毫秒，实现需自行夹到 [MIN, MAX] 区间
     * @return 新建记录 id
     */
    Long createPending(Long sessionId, Long runId, String callId, int waitMs);

    /**
     * CAS 抢占：仅当记录仍为 PENDING 时把它置为 RESUMED 并返回 {@code true}。
     *
     * <p>内存定时器与 cron 补偿都会调它 —— 两者可能同时命中同一条记录，
     * 只有抢占成功的一方允许唤醒，否则会把同一个 run 恢复两次。
     */
    boolean claimForResume(Long waitId);

    /** 补偿扫描：取出到期但仍是 PENDING 的记录（按 wake_at 升序）。 */
    List<PendingWaitVO> listDue(Duration overdueBy, int limit);

    /** 按 id 取一条等待记录（唤醒时在 CAS 之后回读详情）。 */
    PendingWaitVO getById(Long waitId);

    /**
     * 取消某 run 名下所有仍 PENDING 的挂起记录（置 CANCELLED）。
     *
     * <p>run 被停止时调用：loop 已退出，取消标志没有消费者；若不把挂起记录从 PENDING
     * 挪走，到点后 waker（{@link #listDue}/{@link #claimForResume} 只认 PENDING）仍会
     * 尝试恢复该 run。
     *
     * @return 被取消的记录数
     */
    int cancelPendingByRunId(Long runId);

    /**
     * 恢复失败后回置为 PENDING 以便重试：仅当记录处于 RESUMED 时生效，attempts +1、记 last_error。
     *
     * <p>唤醒是「先 CAS 置 RESUMED、再真正恢复」；一旦恢复抛异常，记录若停在 RESUMED，
     * {@link #listDue}/{@link #claimForResume} 只认 PENDING → cron 永不重试 → run 永久卡 WAITING。
     * 因此失败必须回置 PENDING（本方法）。
     *
     * @return 回置后的 attempts；回置失败（记录非 RESUMED）返回 -1
     */
    int requeueForRetry(Long waitId, String error);

    /** 终态化一条等待记录（CANCELLED / FAILED），记录原因。 */
    void markTerminal(Long waitId, String status, String error);

    /** 取某 run 名下仍 PENDING 的挂起记录（对账用；至多一条）。 */
    List<PendingWaitVO> listPendingByRunId(Long runId);
}