package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SessionTodoVO implements Serializable {
    private Long id;
    private Long sessionId;
    private Long runId;
    private String content;
    private String status;
    private String priority;
    private Integer sortOrder;
    /** 来源技能 id（非技能条目为 null）。 */
    private Long skillId;
    /** 来源技能步骤标识，如 step1 / step1.1（非技能条目为 null）。 */
    private String stepId;

    public SessionTodoVO() {
    }

    public SessionTodoVO(String content, String status, String priority) {
        this.content = content;
        this.status = status;
        this.priority = priority;
    }
}
