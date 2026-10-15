-- ============================================================
-- agent_run.error_message — run 失败原因（V87 起）
--
-- 背景：此前 run 失败<b>不留任何原因</b>。RuntimeEventListener 收到 run_failed 时只写 status，
-- 事件里的 errorMessage 被丢弃；表上又没有 error 列。于是「run 为什么会挂」事后无法回答 ——
-- 线上出现过整批 run 被模型端秒回错误打挂，却只能靠「耗时<1s + 零 token」反推。
--
-- 写入方（都经 RuntimeEventListener#handleRunLifecycle 的 FAILED 分支）：
--   - SessionRunner   单轮 LLM 失败（turn.errorMessage()）
--   - RuntimeOrchestrator 提交期校验/上下文准备失败
--   - OrphanRunSweeper / ChatRuntimeService#failStuckWait 等终态兜底
-- ============================================================
ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS error_message TEXT;
