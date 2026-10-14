-- agent_session_todo：给待办条目加「技能来源」结构化字段。
--
-- 背景：技能不再于读取时播种待办（旧 seedTodosFromSkill 会把技能步骤顶成整份计划，
-- 覆盖模型已建清单）。改为：模型采用技能时，自己建/更新待办，并在这两个字段里标注
-- 来源——skillId 是技能 id，step_id 是技能步骤标题标识（如 step1 / step1.1）。
-- 执行该条目时，模型据此回查 `skill_library action=get, sectionHeading=step_id` 取正文。
-- 两列可空：非技能来源的条目（无技能任务、或模型自建的计划）不填。
ALTER TABLE agent_session_todo
    ADD COLUMN skill_id BIGINT,
    ADD COLUMN step_id  VARCHAR(64);