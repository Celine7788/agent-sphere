-- ============================================================
-- agent_run.loop_limit — 固化本 run 生效的轮次上限（V88）
--
-- 背景：此前只持久化了 loop_count（V82），**上限本身却在每次进入循环时重新解析**。
-- run 有 5 个重入入口（首次 startRun / wait 恢复 / 澄清恢复×2 / 对账抢救），
-- 其中只有首次在任务请求线程上（持有 TaskLoopLimitHolder），其余入口都在调度线程上、
-- 拿不到 TASK 层，回落到 CHAT 值 —— 同一个 run 的预算会在中途缩水。
--
-- 同理，运行中改实例 max_loop_count 或改 max-loop-count 配置，也会让同一 run 的预算漂移。
-- V82 的设计意图是「预算是 per-run 而非 per-call」，只固化计数器、不固化分母是不完整的。
--
-- 语义：可空 = 尚未记录。首次进入时按 LoopLimitResolver 解析并写入，之后续跑一律沿用。
-- ============================================================
ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS loop_limit INT;
