package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.tool.chrome.CapabilityBuiltinToolChrome;
import com.buukle.agent.capability.builtin.tool.chrome.dtvo.dto.ChromeExecuteContext;
import com.buukle.agent.capability.builtin.tool.chrome.dtvo.vo.ChromeResultVO;
import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromeCommandDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CapabilityBuiltinToolChromeTest {

    private CapabilityBuiltinToolChrome tool;
    private ApplicationEventPublisher publisher;
    private Deque<ChromeCallbackDTO> callbacks;

    @BeforeEach
    void setUp() {
        tool = new CapabilityBuiltinToolChrome();
        publisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(tool, "eventPublisher", publisher);
        callbacks = new ArrayDeque<>();
        doAnswer(inv -> {
            ChromeCommandDTO cmd = inv.getArgument(0);
            ChromeCallbackDTO result = callbacks.poll();
            // 工具在 publishEvent 之后才把 future 放进 ChromePendingStore：
            // 异步完成（延迟 20ms），确保 put 先于 complete。
            new Thread(() -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ChromePendingStore.complete(cmd.getCommandId(), result);
            }, "chrome-callback-stub").start();
            return null;
        }).when(publisher).publishEvent(any(ChromeCommandDTO.class));
    }

    private ChromeExecuteContext exec(String action, String selector) {
        ChromeExecuteContext cec = new ChromeExecuteContext();
        cec.setSessionId(1L);
        cec.setAction(action);
        cec.setSelector(selector);
        return cec;
    }

    private static ChromeCallbackDTO fail(String category) {
        ChromeCallbackDTO cb = ChromeCallbackDTO.fail("cmd", "boom");
        cb.setErrorCategory(category);
        return cb;
    }

    private ChromeExecuteContext execJs(String code) {
        ChromeExecuteContext cec = exec("executeJS", null);
        cec.setCode(code);
        return cec;
    }

    /**
     * executeJS 只读闸：所有导航写操作必须在**工具层**被拒，且**不发命令**。
     *
     * <p>这是线上风控的直接对策 —— 脚本式跳转（尤其 location.href 与 history.back）
     * 绕过真实点击链路，是猎聘判异常触发 safe.liepin.com 验证码的主因。
     */
    @Test
    void executeJs_navigationWrites_allBlockedWithoutPublish() {
        String[] codes = {
                "location.href='https://h.liepin.com/resume/showresumedetail/?res_id_encode=X'",
                "location['href']='https://h.liepin.com/x'",
                "location.assign('https://h.liepin.com/x')",
                "location.replace('https://h.liepin.com/x')",
                "location.reload()",
                "document.location='https://h.liepin.com/x'",
                "window.location.href='https://h.liepin.com/x'",
                "top.location='https://h.liepin.com/x'",
                "history.back()",
                "history.pushState({}, '', '/x')",
                "window.open('https://h.liepin.com/x')",
                "document.forms[0].submit()",
                "location.hash = '#a'",
        };
        for (String code : codes) {
            ChromeResultVO vo = (ChromeResultVO) tool.execute(execJs(code));
            assertFalse(vo.isSuccess(), "应被拒: " + code);
            assertEquals("js_navigation_blocked", vo.getErrorCategory(), "错误类别: " + code);
        }
        // 一律不发生命令下发（浏览器命令唯一出口是 buildCommand）
        verify(publisher, never()).publishEvent(any(ChromeCommandDTO.class));
    }

    /** 只读代码必须放行 —— 不能把正常的读取（含读 location 属性、或 == 比较）误判为导航写。 */
    @Test
    void executeJs_readOnly_allowed() {
        String[] okCodes = {
                "return document.title",
                "return document.querySelectorAll('.detail-resume-card-wrap').length",
                "const p = location.pathname; return p",
                "return location.href === 'x'",
                "return document.querySelector('a[href*=detail]')?.getAttribute('href') || ''",
        };
        for (String code : okCodes) {
            callbacks.add(ChromeCallbackDTO.ok("cmd", "ok"));
            ChromeResultVO vo = (ChromeResultVO) tool.execute(execJs(code));
            assertTrue(vo.isSuccess(), "应放行: " + code);
        }
        verify(publisher, org.mockito.Mockito.times(okCodes.length))
                .publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void repeatedSameFailure_guardAfterTwo() {
        for (int i = 0; i < 2; i++) {
            callbacks.add(fail("not_found"));
            ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".degree-item"));
            assertFalse(vo.isSuccess());
            assertEquals("not_found", vo.getErrorCategory());
        }
        // 第 3 次：未再发布命令，直接 repeated_failure（护栏前移：连续 2 次即停）
        callbacks.add(fail("not_found"));
        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".degree-item"));
        assertFalse(vo.isSuccess());
        assertEquals("repeated_failure", vo.getErrorCategory());
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void successResetsConsecutiveFailureCounter() {
        callbacks.add(fail("not_found"));
        assertFalse(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        callbacks.add(ChromeCallbackDTO.ok("cmd", "ok"));
        assertTrue(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        callbacks.add(fail("not_found"));
        assertFalse(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        // 成功重置计数后，下一次单个失败仍只记 not_found（护栏前移到 2 次，1 次不触发）
        callbacks.add(ChromeCallbackDTO.ok("cmd", "ok"));
        assertTrue(((ChromeResultVO) tool.execute(exec("click", ".a"))).isSuccess());
        callbacks.add(fail("not_found"));
        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".a"));
        assertEquals("not_found", vo.getErrorCategory());
    }

    @Test
    void injectFailed_autoRetriesOnce() {
        callbacks.add(fail("inject_failed"));
        callbacks.add(ChromeCallbackDTO.ok("cmd", "retried-ok"));

        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("getContent", ".geek-info-card"));

        assertTrue(vo.isSuccess());
        assertEquals("retried-ok", vo.getData());
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void injectFailed_stillFailsAfterRetry() {
        callbacks.add(fail("inject_failed"));
        callbacks.add(fail("inject_failed"));

        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("getContent", ".x"));

        assertFalse(vo.isSuccess());
        assertEquals("inject_failed", vo.getErrorCategory());
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void frameIdGreaterThanZero_rejectedWithoutPublish() {
        ChromeExecuteContext cec = exec("click", ".degree-item");
        cec.setFrameId(2);

        ChromeResultVO vo = (ChromeResultVO) tool.execute(cec);

        assertFalse(vo.isSuccess());
        assertTrue(vo.getErrorMessage().contains("frameId"));
        verify(publisher, never()).publishEvent(any(ChromeCommandDTO.class));
    }

    @Test
    void callbackArrivingDuringPublishIsNotLost() {
        ChromeCallbackDTO expected = ChromeCallbackDTO.ok("cmd", "immediate");
        doAnswer(inv -> {
            ChromeCommandDTO cmd = inv.getArgument(0);
            ChromePendingStore.complete(cmd.getCommandId(), expected);
            return null;
        }).when(publisher).publishEvent(any(ChromeCommandDTO.class));

        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".ready"));

        assertTrue(vo.isSuccess());
        assertEquals("immediate", vo.getData());
    }

    @Test
    void timeoutHasStructuredCategory() {
        doNothing().when(publisher).publishEvent(any(ChromeCommandDTO.class));
        ChromeExecuteContext cec = exec("click", ".slow");
        cec.setTimeout(2);

        ChromeResultVO vo = (ChromeResultVO) tool.execute(cec);

        assertFalse(vo.isSuccess());
        assertEquals("timeout", vo.getErrorCategory());
        assertTrue(vo.getWarning() != null && vo.getWarning().contains("先 getContent"));
    }

    @Test
    void timeoutDoesNotAdvanceRepeatedFailureCounter() {
        AtomicBoolean silent = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (silent.get()) return null; // 不完成 future → 触发超时
            ChromeCommandDTO cmd = inv.getArgument(0);
            ChromeCallbackDTO result = callbacks.poll();
            new Thread(() -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ChromePendingStore.complete(cmd.getCommandId(), result);
            }, "chrome-callback-stub").start();
            return null;
        }).when(publisher).publishEvent(any(ChromeCommandDTO.class));

        // 两次 timeout：慢页面下操作可能已生效，不应计入连续失败
        silent.set(true);
        for (int i = 0; i < 2; i++) {
            ChromeExecuteContext t = exec("click", ".slow-city");
            t.setTimeout(2);
            assertFalse(((ChromeResultVO) tool.execute(t)).isSuccess());
        }
        // 随后真实失败累计到 2 次即触发 repeated_failure（timeout 不计入，护栏前移验证）
        silent.set(false);
        for (int i = 0; i < 3; i++) {
            callbacks.add(fail("not_found"));
            assertFalse(((ChromeResultVO) tool.execute(exec("click", ".slow-city"))).isSuccess());
        }
        ChromeResultVO vo = (ChromeResultVO) tool.execute(exec("click", ".slow-city"));
        assertEquals("repeated_failure", vo.getErrorCategory());
    }
}
