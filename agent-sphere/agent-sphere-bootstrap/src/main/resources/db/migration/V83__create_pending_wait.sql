-- agent_pending_wait：wait 内置工具的挂起记录。
--
-- wait 不阻塞线程：工具执行体只写一条本记录并立即返回 {"status":"waiting"}，
-- SessionRunner 循环判定后 break 退出，虚拟线程与 maxParallel 槽位立即释放。
-- 到点由 PendingWaitWakeScheduler（内存定时器）触发唤醒，
-- WaitWakeTask（cron）作为进程重启/定时器丢失的补偿。
-- 两条路径都靠 status 的 CAS 抢占保证「只唤醒一次」。
--
-- call_id 记录模型侧的 tool call id：唤醒时要改写那条 tool call 记录的
-- artifact（模型看到的 tool 消息内容），定位方式为 getByRunAndCallId。
CREATE TABLE IF NOT EXISTS agent_pending_wait (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT      NOT NULL,
    run_id        BIGINT      NOT NULL,
    call_id       VARCHAR(128)          DEFAULT '',
    wait_ms       INTEGER     NOT NULL DEFAULT 0,
    wake_at       TIMESTAMP   NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reason        VARCHAR(500)          DEFAULT '',
    resumed_at    TIMESTAMP,
    created_by    VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by    VARCHAR(64)  NOT NULL DEFAULT '',
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP,
    delete_flag   SMALLINT     NOT NULL DEFAULT 0,
    tenant_id     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pending_wait_wake ON agent_pending_wait(status, wake_at);
CREATE INDEX IF NOT EXISTS idx_pending_wait_run  ON agent_pending_wait(run_id);
CREATE INDEX IF NOT EXISTS idx_pending_wait_sess ON agent_pending_wait(session_id);