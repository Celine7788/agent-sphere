package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.runtime.kernel.runner.LoopLimitResolver.ResolvedLoopLimit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 轮次上限解析的优先级护栏，重点是 {@link LoopLimitSource#PINNED}。
 *
 * <p>回归背景：上限原本<b>每次进入循环都重新解析</b>，而主 run 有 5 个重入入口
 * （首次 startRun / wait 恢复 / 澄清恢复×2 / 对账抢救），其中只有首次在任务请求线程上
 * 持有 {@code TaskLoopLimitHolder}。续跑时会在调度线程上取不到 TASK 层而回落到更小的
 * CHAT 值 —— 线上表现为「同一个 run 的预算中途缩水」，任务被更早的 loop cap 打断。
 */
class LoopLimitResolverTest {

    private static final int CHAT_LIMIT = 128;
    private static final int TASK_LIMIT = 2000;
    private static final int INSTANCE_LIMIT = 50;

    @Test
    void noConfig_fallsBackToChat() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(null, null, null, CHAT_LIMIT);

        assertEquals(CHAT_LIMIT, r.limit());
        assertEquals(LoopLimitSource.CHAT, r.source());
    }

    @Test
    void taskLimitWinsOverChat() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(null, null, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(TASK_LIMIT, r.limit());
        assertEquals(LoopLimitSource.TASK, r.source());
    }

    @Test
    void instanceLimitWinsOverTask() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(null, INSTANCE_LIMIT, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(INSTANCE_LIMIT, r.limit());
        assertEquals(LoopLimitSource.INSTANCE, r.source());
    }

    // ---------- PINNED（V88） ----------

    @Test
    void pinnedLimitWinsOverEverything() {
        // 核心护栏：续跑时即便实例/任务/系统三者都变了，预算也不许漂移
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(
                INSTANCE_LIMIT, INSTANCE_LIMIT, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(INSTANCE_LIMIT, r.limit());
        assertEquals(LoopLimitSource.PINNED, r.source());
    }

    @Test
    void pinnedLimitSurvivesMissingTaskContextOnSchedulerThread() {
        // wait 恢复走的是 pending-wait-wake 调度线程，TaskLoopLimitHolder 为 null。
        // 没有固化时这里会退回 128；固化后必须仍用 2000。
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(TASK_LIMIT, null, null, CHAT_LIMIT);

        assertEquals(TASK_LIMIT, r.limit(),
                "续跑时 TaskLoopLimitHolder 为 null 也必须沿用固化值，否则预算中途缩水");
    }

    @Test
    void pinnedLimitSurvivesInstanceConfigEditedMidRun() {
        // 运行中把实例上限从 2000 调小到 5：同一 run 的预算不应随之变化
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(2000, 5, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(2000, r.limit(), "运行中改实例配置不得让已固化 run 的预算漂移");
    }

    @Test
    void zeroOrNegativePinnedIsTreatedAsUnset() {
        // 存量 run 的 loop_limit 为 NULL；0/负数也应视为未记录而不是把预算清零
        assertEquals(TASK_LIMIT,
                LoopLimitResolver.resolveMain(0, null, TASK_LIMIT, CHAT_LIMIT).limit());
        assertEquals(TASK_LIMIT,
                LoopLimitResolver.resolveMain(-1, null, TASK_LIMIT, CHAT_LIMIT).limit());
        assertEquals(LoopLimitSource.TASK,
                LoopLimitResolver.resolveMain(0, null, TASK_LIMIT, CHAT_LIMIT).source());
    }

    // ---------- 子循环：明确不参与任务提额，也不需要固化 ----------

    @Test
    void subLoop_neverUsesTaskLimit() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveSub(null, 8);

        assertEquals(8, r.limit());
        assertEquals(LoopLimitSource.CHAT, r.source());
    }

    @Test
    void subLoop_usesInstanceOverride() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveSub(INSTANCE_LIMIT, 8);

        assertEquals(INSTANCE_LIMIT, r.limit());
        assertEquals(LoopLimitSource.INSTANCE, r.source());
    }
}
