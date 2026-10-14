package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

@Data
public class TodoItemDTO {
    private String content;
    private String status;
    private String priority;
    /** 来源技能 id（见 agent_session_todo.skill_id）。 */
    private Long skillId;
    /** 来源技能步骤标识，如 step1 / step1.1（见 agent_session_todo.step_id）。 */
    private String stepId;
}
