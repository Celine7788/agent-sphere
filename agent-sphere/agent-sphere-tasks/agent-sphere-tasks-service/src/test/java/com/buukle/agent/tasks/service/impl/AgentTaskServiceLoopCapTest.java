package com.buukle.agent.tasks.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.tasks.domain.AgentTask;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import com.buukle.agent.tasks.repository.AgentTaskArtifactMapper;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.tasks.service.TaskContractValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * loop cap 任务守卫的回归护栏。
 *
 * <p>线上 task 362 / 360：run 其实是 COMPLETED，且都产出了 1300+ 字符的合规结构化结果
 * （run 1575 甚至是 {@code status=empty / stopReason=POOL_EXHAUSTED} 的完整结论），
 * 却因 {@code loopCapped=true} 被一票否决，业务方只收到一句
 * {@code {"error":"run loop limit reached"}}，模型写的东西全被丢弃。
 *
 * <p>根因：{@code loopCapped} 在 SessionRunner 的 COMPLETE 分支也被置位，把「恰好在最后一轮
 * 正常收工」误判成「被截断出半成品」。现在 COMPLETE 分支不再置位，守卫只对真截断生效 ——
 * 但真截断时<b>仍必须</b>把 run 的产出带回去，否则业务方依然看不到现场。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskServiceLoopCapTest {

    private static final Long TASK_ID = 362L;
    private static final Long RUN_ID = 1575L;

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private AgentTaskArtifactMapper artifactMapper;
    @Mock
    private AgentRuntimeProperties runtimeProperties;
    @Mock
    private InstanceSpi instanceSpi;
    @Mock
    private SessionSpi sessionSpi;
    @Mock
    private RunSpi runSpi;
    @Mock
    private ChatRuntimeService chatRuntimeService;
    @Mock
    private TaskCallbackService taskCallbackService;
    @Mock
    private TaskContractValidator contractValidator;
    @Mock
    private AgentLlmInteractionRecordSpi llmInteractionRecordSpi;
    @Mock
    private AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    private TaskMcpCredentialStore taskMcpCredentialStore;

    @InjectMocks
    private AgentTaskServiceImpl service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaUpdateWrapper 靠 TableInfo 解析列名；纯 Mockito 环境没有 MyBatis 启动过程，
        // 不预热就会抛 "can not find lambda cache"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "fixture"), AgentTask.class);
    }

    private static AgentTask runningTask() {
        AgentTask t = new AgentTask();
        t.setId(TASK_ID);
        t.setStatus(TaskEnum.STATUS_RUNNING);
        t.setPollPhase("execute");
        t.setRunId(RUN_ID);
        t.setSessionId(674L);
        t.setExpectedOutputJson("{\"required\":[\"candidates\",\"status\"]}");
        return t;
    }

    private static RunVO completedRun(boolean loopCapped) {
        RunVO r = new RunVO();
        r.setId(RUN_ID);
        r.setSessionId(674L);
        r.setStatus("COMPLETED");
        r.setLoopCount(127);
        r.setLoopCapped(loopCapped);
        r.setAssistantReply("{\"candidates\":[],\"status\":\"empty\",\"stopReason\":\"POOL_EXHAUSTED\"}");
        return r;
    }

    /** invoke handleCompletedPhase(AgentTask, String, Long, RunVO) */
    private boolean invokeHandleCompletedPhase(AgentTask task, String phase, Long runId, RunVO run)
            throws Exception {
        Method m = AgentTaskServiceImpl.class.getDeclaredMethod(
                "handleCompletedPhase", AgentTask.class, String.class, Long.class, RunVO.class);
        m.setAccessible(true);
        return (boolean) m.invoke(service, task, phase, runId, run);
    }

    @Test
    void loopCappedRun_failsTaskButCarriesRunOutputAndRemark() throws Exception {
        AgentTask task = runningTask();
        RunVO run = completedRun(true);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);

        assertTrue(invokeHandleCompletedPhase(task, "execute", RUN_ID, run));

        // 终态快照经回调发给业务方，从这里断言它拿到的内容
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).update(any(), any());
        verify(taskCallbackService).notifyTerminal(cap.capture());
        AgentTask notified = cap.getValue();
        assertEquals(TaskEnum.STATUS_FAILED, notified.getStatus());
        assertNotNull(notified.getResultJson());
        assertTrue(notified.getResultJson().contains("POOL_EXHAUSTED"),
                "loop cap 失败也必须带上 run 的实际产出，否则业务方看不到模型干了什么。实际="
                        + notified.getResultJson());
        assertNotNull(notified.getRemark(), "remark 必须有人话原因，回调里此前一直是空串");
        assertTrue(notified.getRemark().length() > 0);
    }

    @Test
    void loopCappedRun_doesNotStartExtraction() throws Exception {
        AgentTask task = runningTask();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);

        invokeHandleCompletedPhase(task, "execute", RUN_ID, completedRun(true));

        // 真截断仍不进入结构化提炼（这是守卫存在的意义）——不发起 extract 轮
        verify(chatRuntimeService, never()).chat(anyLong(), any());
        verify(taskMapper, never()).updateById(any(AgentTask.class));
    }

    @Test
    void notLoopCappedRun_proceedsToExtraction() throws Exception {
        // 改动后的关键分支：模型在最后一轮主动交付答案（loopCapped=false）时，
        // 必须正常进入结构化提炼，而不是被一票否决
        AgentTask task = runningTask();
        // fireFollowUpRun 会读 runner.taskMaxLoopCount；mock 默认返回 null 会 NPE 提前跳出
        when(runtimeProperties.getRunner()).thenReturn(new AgentRuntimeProperties.RunnerConfig());
        ChatMessageResponseVO resp = new ChatMessageResponseVO();
        resp.setRunId(999L);
        when(chatRuntimeService.chat(anyLong(), any())).thenReturn(resp);

        invokeHandleCompletedPhase(task, "execute", RUN_ID, completedRun(false));

        verify(chatRuntimeService).chat(anyLong(), any());
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).updateById(cap.capture());
        assertEquals("extract", cap.getValue().getPollPhase(),
                "正常完成的 run 应推进到 extract 阶段做结构化整理");
    }
}
