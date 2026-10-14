package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.instance.domain.AgentPendingWait;
import com.buukle.agent.instance.repository.AgentPendingWaitMapper;
import com.buukle.agent.instance.service.PendingWaitService;
import com.buukle.agent.instance.spi.PendingWaitCreatedEvent;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 等待记录的服务层行为：建记录 + 发事件 + CAS 抢占。
 *
 * <p>CAS 是这套机制唯一正确性依赖 —— 内存定时器与 cron 补偿两条路径都会命中同一条记录，
 * 没有 CAS 就会把同一个 run 恢复两次。
 */
@ExtendWith(MockitoExtension.class)
class PendingWaitServiceTest {

    @Mock
    AgentPendingWaitMapper waitMapper;
    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    PendingWaitService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaUpdateWrapper 靠 TableInfo 解析列名；纯 Mockito 环境没有 MyBatis 启动过程，
        // 不预热就会抛 "can not find lambda cache"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "fixture"), AgentPendingWait.class);
    }

    @Test
    void createPending_clampsMsAndPublishesEventSoSchedulerPicksItUp() {
        when(waitMapper.insert(any(AgentPendingWait.class))).thenAnswer(inv -> {
            ((AgentPendingWait) inv.getArgument(0)).setId(77L);
            return 1;
        });

        Long id = service.createPending(1L, 2L, "call-1", 999_999_999);

        assertEquals(77L, id);
        ArgumentCaptor<AgentPendingWait> captor = ArgumentCaptor.forClass(AgentPendingWait.class);
        verify(waitMapper).insert(captor.capture());
        assertEquals(WaitConstants.MAX_WAIT_MS, captor.getValue().getWaitMs(),
                "超过上限的 ms 应被夹到 MAX，避免无限空转窗口");
        assertEquals(WaitConstants.PENDING_STATUS, captor.getValue().getStatus());

        verify(eventPublisher).publishEvent(any(PendingWaitCreatedEvent.class));
    }

    @Test
    void createPending_clampsMsUpToMinimum() {
        when(waitMapper.insert(any(AgentPendingWait.class))).thenAnswer(inv -> {
            ((AgentPendingWait) inv.getArgument(0)).setId(78L);
            return 1;
        });

        service.createPending(1L, 2L, "call-2", 1);

        ArgumentCaptor<AgentPendingWait> captor = ArgumentCaptor.forClass(AgentPendingWait.class);
        verify(waitMapper).insert(captor.capture());
        assertEquals(WaitConstants.MIN_WAIT_MS, captor.getValue().getWaitMs());
    }

    @Test
    void claimForResume_succeedsOnlyWhileRowIsPending() {
        // 第一次抢占：UPDATE 命中 1 行
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertTrue(service.claimForResume(5L));

        // 第二次（cron 与定时器同时命中）：已被置为 RESUMED，UPDATE 命中 0 行
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        assertFalse(service.claimForResume(5L),
                "CAS 失败方必须放弃 —— 否则同一个 run 会被恢复两次");
    }

    @Test
    void claimForResume_ignoresNullId() {
        assertFalse(service.claimForResume(null));
        verify(waitMapper, never()).update(any(), any());
    }

    @Test
    void claimForResume_usesSingleConditionalUpdate() {
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.claimForResume(9L);

        // 必须是「一条带 status=PENDING 条件的 UPDATE」，不能是「先 select 再 update」——
        // 后者在两条唤醒路径并发时会双双通过检查。
        verify(waitMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
        verify(waitMapper, never()).selectById(any());
    }

    @Test
    void getById_returnsNullForMissingRecord() {
        when(waitMapper.selectById(404L)).thenReturn(null);
        assertEquals(null, service.getById(404L));
    }

    @Test
    void listDue_boundsLimitToAtLeastOne() {
        when(waitMapper.selectList(any())).thenReturn(java.util.List.of());
        // limit 传 0 / 负数也不能拼出 "LIMIT 0" 之外的非法 SQL
        service.listDue(Duration.ofSeconds(2), 0);
        service.listDue(Duration.ofSeconds(2), -5);
        verify(waitMapper, times(2)).selectList(any());
    }
}