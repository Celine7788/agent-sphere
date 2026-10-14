-- agent_run.loop_count：把 agent 循环计数从「每次 runner 调用的局部变量」提升为「run 级持久状态」。
--
-- 背景：SessionRunner#runInternal 里 loopCount 是局部变量（int loopCount = 0），
-- 每次挂起恢复（澄清 AWAITING_USER / 等待 WAITING）都会归零，
-- 于是 maxLoopCount 形同虚设 —— 模型可以靠反复挂起无限续命。
-- 落库后恢复时从 currentRun 读回，循环预算才真正是「每个 run」而非「每次调用」。
ALTER TABLE agent_run
    ADD COLUMN loop_count INTEGER NOT NULL DEFAULT 0;