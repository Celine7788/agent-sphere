package com.buukle.agent.runtime.kernel.constants;

public final class RunnerConstants {
    public static final String TOOL_TYPE_FUNCTION = "function";
    public static final String EMPTY_JSON_ARGS = "{}";
    public static final long DEFAULT_MAX_INPUT_TOKENS = 1_000_000L;
    public static final long DEFAULT_MAX_OUTPUT_TOKENS = 128_000L;
    public static final int TOKEN_ESTIMATE_DIVISOR = 3;
    public static final int TOOL_RESULT_MAX_CHARS = 2000;
    public static final int COMPACTION_TOOL_RESULT_MAX_CHARS = 500;
    public static final int TOOL_RESULT_HISTORY_CHARS_BUDGET = 50000;
    public static final String FALLBACK_CALL_ID_PREFIX = "hist_";
    public static final String TOOL_RESULT_BUDGET_OMITTED = "[Tool result omitted due to context limit]";
    public static final String PROMPT_CURRENT_TIME = "\n\nCurrent server time: ";
    public static final String PROMPT_TOOLS_HEADER = "\n\nAvailable tools:\n";
    /** 待办列表尾部段：header 同时作为槽位定位 marker（内容以 header 开头的消息即待办槽）。 */
    public static final String PROMPT_TODOLIST_HEADER = "\n\n## 进度记录（不代表任务完成）\n";
    public static final String PROMPT_TODOLIST_INSTRUCTION =
            "\n\n**请根据任务状态变更及时更新此列表，不要等待用户提醒。"
            + "注意：此列表只是进度记录，**不构成任务完成判据** —— 任务是否完成由任务指令"
            + "（技能步骤 / 期望输出 / 收尾条件）决定，而不是由本列表全部 completed 决定。"
            + "若本列表由技能步骤自动生成，不要重建，只更新状态。**";
    /**
     * 技能槽尾部段：本任务已读取的技能名/版本 + 大纲（H2/H3 + 行号）。
     *
     * <p>为什么需要它：大技能正文在**跨 run 重放**时会被 {@code ToolResultCompressor}
     * 按 2000 字符上限压成"头 100 + 尾 50"，模型会丢掉规则开始即兴。把**大纲**放进一个
     * 受保护的尾部 system 槽（每轮原位刷新、不参与压缩），模型就永远看得到"地图"，
     * 正文按需 {@code get(sectionHeading=…)} 读取 —— 每次都是小片段，不会被压掉。
     *
     * <p>header 同时作为槽位定位 marker（与待办槽同一套机制）。
     */
    public static final String PROMPT_SKILL_SLOT_HEADER = "\n\n## 本任务技能（按需按节读取）\n";
    public static final String PROMPT_TOOLS_FOOTER = """
            
            TASK LIST: If this task is driven by a skill (see the skill slot / skill_library),
            the skill's steps ARE the plan — read the skill and follow its steps, and do NOT
            build a second task list (it may already be seeded for you; if so, only update
            statuses). Otherwise, call the task list tool to create a structured task list
            covering the user's request before acting. Either way, keep the list in sync with
            actual progress by calling the task list tool whenever a task's status changes
            (pending → in_progress → completed / cancelled).

            A todo's status is PROGRESS ONLY. Marking every todo 'completed' does NOT mean the
            task is finished — completion is decided by the task instructions and, when a skill
            applies, the skill's closing conditions and expected output. Do not stop early just
            because the list looks done.

            You may call multiple INDEPENDENT tools in a single turn — they will
            run in parallel. But tools with sequential dependencies (e.g., fetch
            a URL first, then read its content) MUST be called in separate turns.
            Mark dependent tasks as 'pending' in the todo list until their turn.
            
            IMPORTANT: When calling the task list tool, the JSON MUST be strictly valid.
            Each key-value pair MUST be separated by a comma. Example:
            {"todos": [{"content": "...", "status": "pending", "priority": "high"}]}
            Never omit commas between object properties.

            CRITICAL: When you need to call a tool, you MUST use the function calling API
            (respond with tool_calls in the API format). Do NOT describe or plan tool calls
            as JSON or text in your reply — invoke the tool directly through the available
            function definitions.

            CLARIFICATION RULE: If you ever need the user to confirm an action, choose
            between options, or provide additional information, you MUST call the
            clarification tool (type=choice for options, confirm for yes/no, input for
            free-form). NEVER pose questions in natural language — the user cannot respond
            to plain text questions in this interface; only the clarification tool
            can capture their reply.

            CLARIFICATION AFTERCARE: Whenever the user responds to a clarification,
            IMMEDIATELY review your task list and call the task list tool to reflect
            the new information. Mark resolved items as completed, add follow-up tasks,
            and update priorities. Do NOT continue working on stale tasks. Then
            EXECUTE the next step immediately — call the necessary tools and provide
            the result. Do NOT ask another clarification on the same topic.

            CRITICAL — EXECUTE, DO NOT ASK: When the user gives you a clear instruction
            (e.g., 'search for X', 'check Y', 'look up Z'), EXECUTE it immediately.
            Call the necessary tools and provide the result. Do NOT ask the user
            'what do you want to do' or 'what would you like to search' — they already
            told you. If you need exactly one missing detail, use the clarification
            tool ONCE, then ACT on the response. Never ask multiple consecutive
            clarifications or questions. NEVER respond with a question when the user
            has already stated their intent.""";
    public static final String COMPACTION_USER_PREFIX = "User: ";
    public static final String COMPACTION_ASSISTANT_PREFIX = "Assistant: ";
    public static final String COMPACTION_NEWLINE = "\n";
    public static final String HISTORY_SUMMARY_PREFIX = "[Conversation summary]\n";
    /** 终态轮收口指令：作为尾部 system 消息追加（不修改 index 0，保持前缀缓存稳定）。 */
    public static final String FINAL_TURN_INSTRUCTION =
            "\n\n**IMPORTANT: This is your final turn. You MUST provide a complete summary answer now. Do NOT call any more tools.**";

    /** delegate 子 Agent 工作流指引（仅当注册了 delegate 工具时追加，避免无谓 token）。 */
    public static final String PROMPT_SUBAGENT_WORKFLOW = """


            DELEGATE WORKFLOW: Skills are NOT direct tools.
            1) Use skill_library to discover and inspect skills (list/search; get by outline, section, or line range for progressive loading).
            2) Then use delegate to execute the work. call delegate with mode=main to run inline in this loop (no sub-agent);
               use mode=subagent ONLY when isolation is warranted (noisy intermediate context, long-running work, or independent parallel lanes).
               Provide tasks[] (requires mode=subagent) for a DAG of parallel sub-agents; results are aggregated by key.
               A sub-agent inherits the full toolset and the parent model route; optionally pass agentRef=instance:<id> to prefix its system prompt.
            3) You do NOT need a sub-agent for simple work — when no isolation is needed, just finish the task yourself in this loop.""";

    // ---- SessionRunner magic strings ----
    public static final String JSON_ERROR_TOOL_LOST = "{\"error\":\"Tool execution lost\"}";
    public static final String FALLBACK_COMPLETE_MSG = "{\"status\":\"ERROR\",\"reason\":\"No completion content produced\"}";
    public static final String CANCEL_MSG = "⏹️ Run cancelled by user";

    private RunnerConstants() {
    }
}
