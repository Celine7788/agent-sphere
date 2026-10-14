package com.buukle.agent.capability.builtin.tool.todowrite.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class TodowriteExecuteContext extends ExecuteContext {
    @NotNull
    @Valid
    private List<TodoItemDTO> todos;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TodoItemDTO {
        @NotNull
        private String content;
        @NotNull
        private String status;
        @NotNull
        private String priority;

        @Schema(description = "来源技能 id（skill_library 返回的 skillId）。仅当该条目来自某技能的步骤时填写；"
                + "执行前据此与 stepId 一起回查正文，否则留空。")
        private Long skillId;

        @Schema(description = "来源技能步骤的标题标识，如 step1 / step1.1（取自技能大纲标题的首个词）。"
                + "执行该条目时用 skill_library(action=get, skillId=<skillId>, sectionHeading=<stepId>) 读取该节正文。")
        private String stepId;
    }
}
