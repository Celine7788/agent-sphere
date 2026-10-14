package com.buukle.agent.capability.builtin.tool.todowrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.capability.builtin.tool.todowrite.dtvo.dto.TodowriteExecuteContext;
import com.buukle.agent.capability.builtin.tool.todowrite.dtvo.vo.TodowriteResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class CapabilityBuiltinToolTodowrite implements CapabilityBuiltinToolSpi {

    static final String STATUS_PENDING = "pending";
    static final String PRIORITY_MEDIUM = "medium";
    static final String TOOL_DESCRIPTION = """
            Create and maintain the SINGLE task list for the current task.
            Call it at the start to lay out the work, and again whenever a task's status changes
            (pending → in_progress → completed / cancelled). Mark a task in_progress BEFORE working
            on it (max one at a time), and completed ONLY after the work is done AND verified.

            If a skill drives this task, its steps ARE the plan: read the skill (skill_library) and
            write ONE item per step, keeping their order — do NOT build a second, divergent list.
            For items that come from a skill, also set `skillId` (the skill id) and `stepId` (the
            step id such as step1 / step1.1); before executing such an item, read its body with
            skill_library(action=get, skillId=…, sectionHeading=stepId). If a skill only details a
            few steps of your existing plan, KEEP that list and just annotate those items.

            This tool REPLACES the entire list — always pass the COMPLETE list; omitted items are deleted.
            NOTE: a completed list is PROGRESS ONLY — it does not by itself finish the task; completion
            follows the task instructions / the skill's closing conditions and expected output.""";

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.TODOWRITE;
    }

    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolTodowrite.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("待办写入");
        info.setDisplayNameEn("Todo Write");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(TodowriteExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(TodowriteResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return TodowriteExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return TodowriteResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        TodowriteExecuteContext twCtx = (TodowriteExecuteContext) ctx;
        List<TodowriteResultVO.TodoItemVO> resultTodos = new ArrayList<>();

        List<TodowriteExecuteContext.TodoItemDTO> input = twCtx.getTodos();
        if (input != null) {
            for (TodowriteExecuteContext.TodoItemDTO item : input) {
                resultTodos.add(new TodowriteResultVO.TodoItemVO(
                        item.getContent() != null ? item.getContent() : "",
                        item.getStatus() != null ? item.getStatus() : STATUS_PENDING,
                        item.getPriority() != null ? item.getPriority() : PRIORITY_MEDIUM,
                        item.getSkillId(),
                        item.getStepId()));
            }
        }
        return new TodowriteResultVO(resultTodos);
    }
}
