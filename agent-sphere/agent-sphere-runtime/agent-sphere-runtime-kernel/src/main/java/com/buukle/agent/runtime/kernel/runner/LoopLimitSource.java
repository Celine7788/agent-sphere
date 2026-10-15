package com.buukle.agent.runtime.kernel.runner;

/**
 * run 循环上限的来源：
 * <ul>
 *     <li>{@link #PINNED}：续跑时沿用本 run 已固化的上限（{@code agent_run.loop_limit}，V88 起）；</li>
 *     <li>{@link #INSTANCE}：实例级 {@code maxLoopCount} 覆盖（主/子循环均生效，最高优先）；</li>
 *     <li>{@link #TASK}：任务触发的 run 提额（仅主循环）；</li>
 *     <li>{@link #CHAT}：系统配置兜底（主循环 runner.max-loop-count，子循环 delegate.max-sub-loop-count）。</li>
 * </ul>
 *
 * <p>{@link #PINNED} 必须排在最前：run 有多个重入入口（wait 恢复 / 澄清恢复 / 对账抢救都在
 * 调度线程上，拿不到 {@code TaskLoopLimitHolder}），不固化就会在同一个 run 中途缩水。
 */
public enum LoopLimitSource {
    PINNED,
    INSTANCE,
    TASK,
    CHAT
}