package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_session_todo")
public class SessionTodo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    private String content;
    private String status;
    private String priority;
    private Integer sortOrder;
    /** 来源技能 id；模型采用技能建立该条目时填写。 */
    private Long skillId;
    /** 来源技能步骤标识（如 step1 / step1.1），与 skillId 配套用于回查技能正文。 */
    private String stepId;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
