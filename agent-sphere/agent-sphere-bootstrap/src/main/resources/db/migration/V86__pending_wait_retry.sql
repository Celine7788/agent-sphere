-- agent_pending_wait：恢复失败可重试。
--
-- 背景：唤醒路径「先 claimForResume（PENDING → RESUMED）再 resumeFromWait」，一旦恢复抛异常，
-- 记录停在 RESUMED，而 listDue / claimForResume 只认 PENDING → cron 永不重试 →
-- run 永久停在 WAITING（线上偶发、无终态、无重试）。
--
-- 新增 attempts / last_error：恢复失败时用 requeueForRetry 把 RESUMED 回置 PENDING（attempts+1），
-- 让 cron 继续重试；超过上限则置 FAILED 并发 run 终态事件。
ALTER TABLE agent_pending_wait
    ADD COLUMN attempts   INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_error VARCHAR(500);

-- 对账/重试按 run + status 查询。
CREATE INDEX IF NOT EXISTS idx_pending_wait_run_status ON agent_pending_wait(run_id, status);