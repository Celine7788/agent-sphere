package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.runner.SuspenseStatusResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 挂起判定的回归护栏。
 *
 * <p>这里最要紧的是 {@link #fieldOrderAndWhitespaceDoNotMatter()}：旧实现用字符串
 * {@code contains("\"status\":\"awaiting_user\"")} 判定，只要序列化输出多一个空格或调整字段
 * 顺序，挂起就不触发，run 会带着一条「等你回答」的结果继续空转。这类失败不会抛异常，
 * 只会表现为「点了澄清但 agent 不停」，非常难定位。
 */
class SuspenseStatusResolverTest {

    private static final String WAIT_TOOL = WaitConstants.TOOL_NAME;
    private static final String CLARIFY_TOOL = ChatClarification.CLARIFICATION_TOOL_NAME;

    @Test
    void waitResult_suspendsWithWaiting() {
        assertEquals(RunStatus.WAITING,
                SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"status\":\"waiting\",\"wait_ms\":90000}"));
    }

    @Test
    void clarificationResult_suspendsWithAwaitingUser() {
        assertEquals(RunStatus.AWAITING_USER,
                SuspenseStatusResolver.resolve(CLARIFY_TOOL, "{\"status\":\"awaiting_user\",\"clarification_id\":\"c1\"}"));
    }

    @Test
    void fieldOrderAndWhitespaceDoNotMatter() {
        // 旧实现对这两种写法都会漏判
        assertEquals(RunStatus.WAITING,
                SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"wait_ms\":1000,\"status\":\"waiting\"}"));
        assertEquals(RunStatus.AWAITING_USER,
                SuspenseStatusResolver.resolve(CLARIFY_TOOL, "{\"status\" : \"awaiting_user\"}"));
    }

    @Test
    void otherToolsWithSameStatus_areNotSuspendCandidates() {
        // 只有澄清与 wait 是挂起类工具；别的工具返回 waiting 不该劫持 run
        assertNull(SuspenseStatusResolver.resolve("builtin_5", "{\"status\":\"waiting\"}"));
    }

    @Test
    void candidateToolWithNonSuspendStatus_returnsNull() {
        assertNull(SuspenseStatusResolver.resolve(CLARIFY_TOOL, "{\"status\":\"responded\"}"));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"ok\":true}"));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"error\":\"Failed to process wait\"}"),
                "失败的 wait 必须按普通结果继续，不能把 run 永久停住");
    }

    @Test
    void malformedResult_fallsBackToNotSuspending() {
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, "not-json"));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, ""));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, null));
    }

    // 关于「恢复后二次挂起」：resumeFromWait 改写的 artifact 同样带 status=waiting，
    // 但它写进的是 tool call 记录，恢复后的循环经 HistoryLoader 读历史，wait 不会被重新执行，
    // 因此该 artifact 永远不会流进 results 触发本判定。防线在「不重跑」而非在此处。
}