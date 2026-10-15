package com.buukle.agent.runtime.kernel.runner;

/**
 * run 循环上限解析：
 * <ul>
 *     <li>{@link #resolveMain}：主循环，优先级 固化 {@code PINNED} &gt; 实例 {@code INSTANCE} &gt;
 *         任务 {@code TASK} &gt; 系统 {@code CHAT}；</li>
 *     <li>{@link #resolveSub}：子 Agent 循环，优先级 实例 {@code INSTANCE} &gt; 系统 {@code CHAT}
 *         （不参与任务提额，且<b>不需要</b>固化）。</li>
 * </ul>
 * 实例覆盖 {@code >0} 视为已配置（0 / null 视为未配置）；已固化值同理（{@code loop_limit} 为 null 表示未记录）。
 *
 * <p><b>为什么主循环需要 PINNED 而子循环不需要</b>：主 run 会从多个入口重入 —— 只有首次在任务
 * 请求线程上（持有 {@code TaskLoopLimitHolder}，能拿到 TASK 提额），wait 恢复 / 澄清恢复 /
 * 对账抢救都在调度线程上，不固化就会回落到更小的 CHAT 值，表现为「同一个 run 的预算中途缩水」。
 * 子 Agent 则是<b>单次同步执行</b>：{@code SessionSubRunner} 内部是普通 for 循环，每条出口都
 * 恰好调用一次 {@code finishSubAgentRun}，没有挂起/恢复，因此上限在一次调用内不会漂移 ——
 * 给它加固化只会多一个永远为 null 的参数与一列死字段。
 */
public final class LoopLimitResolver {

    private LoopLimitResolver() {
    }

    public record ResolvedLoopLimit(int limit, LoopLimitSource source) {
    }

    /** 主循环：已固化值优先，否则按 实例 &gt; 任务 &gt; 系统 解析。 */
    public static ResolvedLoopLimit resolveMain(Integer pinnedLimit, Integer instanceLoopLimit,
                                                 Integer taskLoopLimit, int defaultLimit) {
        if (pinnedLimit != null && pinnedLimit > 0) {
            return new ResolvedLoopLimit(pinnedLimit, LoopLimitSource.PINNED);
        }
        if (instanceLoopLimit != null && instanceLoopLimit > 0) {
            return new ResolvedLoopLimit(instanceLoopLimit, LoopLimitSource.INSTANCE);
        }
        if (taskLoopLimit != null && taskLoopLimit > 0) {
            return new ResolvedLoopLimit(taskLoopLimit, LoopLimitSource.TASK);
        }
        return new ResolvedLoopLimit(defaultLimit, LoopLimitSource.CHAT);
    }

    /** 子循环：按 实例 &gt; 系统 解析，不参与任务提额，也不做固化（无重入路径）。 */
    public static ResolvedLoopLimit resolveSub(Integer instanceLoopLimit, int defaultLimit) {
        if (instanceLoopLimit != null && instanceLoopLimit > 0) {
            return new ResolvedLoopLimit(instanceLoopLimit, LoopLimitSource.INSTANCE);
        }
        return new ResolvedLoopLimit(defaultLimit, LoopLimitSource.CHAT);
    }
}
