package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * 判定某个工具调用是否要求「挂起本轮 run」。
 *
 * <p>历史上这里是 {@code toolResult.contains("\"status\":\"awaiting_user\"")} —— 依赖序列化
 * 后的字段顺序与字面量，改一次编码方式（比如给 JSON 加个空格）就会静默失效：挂起不触发，
 * run 拿着一条「等你回答」的结果继续空转，比报错更难查。改为结构化解析。
 *
 * <p>解析失败一律返回 {@code null}（不挂起）：宁可多跑一轮，也不要静默停住 run。
 */
@Slf4j
public final class SuspenseStatusResolver {

    private SuspenseStatusResolver() {
    }

    /**
     * @return 对应的 {@link RunStatus}（{@code AWAITING_USER} / {@code WAITING}）；非挂起返回 {@code null}
     */
    public static RunStatus resolve(String toolName, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }
        boolean candidate = ChatClarification.CLARIFICATION_TOOL_NAME.equals(toolName)
                || WaitConstants.TOOL_NAME.equals(toolName);
        if (!candidate) {
            return null;
        }
        try {
            String status = JsonUtils.getMapper().readTree(toolResult)
                    .path(ChatClarification.CLARIFYING_JSON_STATUS).asText(null);
            if (ChatClarification.CLARIFYING_STATUS_AWAITING_USER.equals(status)) {
                return RunStatus.AWAITING_USER;
            }
            if (WaitConstants.STATUS_WAITING.equals(status)) {
                return RunStatus.WAITING;
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse suspend status from tool result: {}", toolResult);
            return null;
        }
    }
}