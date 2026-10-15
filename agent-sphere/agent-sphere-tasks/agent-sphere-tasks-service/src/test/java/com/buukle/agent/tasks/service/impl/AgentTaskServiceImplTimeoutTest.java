package com.buukle.agent.tasks.service.impl;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.tasks.repository.AgentTaskArtifactMapper;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.tasks.service.TaskContractValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 任务超时的取值口径：业务方指定优先，未指定才回落到 AS 侧配置默认。
 *
 * <p>这条边界很关键 —— 该值同时决定任务存活上限与业务方签发 MCP 凭证的 TTL。
 * 若把「任务级指定」错误地忽略成默认值，长任务会在凭证 TTL 之后仍在运行，
 * 中途 MCP 调用全部被拒（且表现为与真正根因相隔很远的「缺少凭证」）。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskServiceImplTimeoutTest {

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
    private SsoIdentitySpi ssoIdentitySpi;
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

    private void setDefaultMaxPollSeconds(long value) throws Exception {
        Field field = AgentTaskServiceImpl.class.getDeclaredField("defaultMaxPollSeconds");
        field.setAccessible(true);
        field.setLong(service, value);
    }

    private void setMinTimeoutSeconds(long value) throws Exception {
        Field field = AgentTaskServiceImpl.class.getDeclaredField("minTimeoutSeconds");
        field.setAccessible(true);
        field.setLong(service, value);
    }

    @Test
    void fallsBackToConfiguredDefaultWhenTaskHasNoTimeout() throws Exception {
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(0L);

        assertEquals(3600L, service.effectiveTimeoutSeconds(null));
    }

    @Test
    void taskLevelTimeoutOverridesConfiguredDefault() throws Exception {
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(0L);

        assertEquals(120L, service.effectiveTimeoutSeconds(120));
    }

    // ---------- 超时下限（min-timeout-seconds） ----------

    @Test
    void businessValueBelowFloorIsRaisedToFloor() throws Exception {
        // 线上 155/158 个任务由业务方显式传 3600，且已固化进 task_timeout_seconds 列。
        // 改 max-poll-seconds 对它们无效，唯一能抬高实际超时的是这个下限。
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(10800L);

        assertEquals(10800L, service.effectiveTimeoutSeconds(3600));
    }

    @Test
    void businessValueAboveFloorIsKept() throws Exception {
        // 下限只兜底，不反过来压低业务方主动给的更大值
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(10800L);

        assertEquals(14400L, service.effectiveTimeoutSeconds(14400));
    }

    @Test
    void nullTaskValueAlsoRespectsFloor() throws Exception {
        // 下限高于默认值时，回落到默认值的任务同样要被抬起
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(10800L);

        assertEquals(10800L, service.effectiveTimeoutSeconds(null));
    }

    @Test
    void floorOfZeroKeepsLegacyBehaviourExactly() throws Exception {
        // 未配置该键的环境必须完全不变：业务方给多少就是多少
        setDefaultMaxPollSeconds(3600L);
        setMinTimeoutSeconds(0L);

        assertEquals(120L, service.effectiveTimeoutSeconds(120));
        assertEquals(3600L, service.effectiveTimeoutSeconds(null));
    }
}
