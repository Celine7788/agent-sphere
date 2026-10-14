package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.service.converter.RunConverter;
import com.buukle.agent.instance.domain.AgentRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * loopCount 必须在 VO ↔ DO 之间完整往返。
 *
 * <p>这是「挂起不再重置循环预算」的前提：SessionRunner 恢复时从
 * {@code currentRun.getLoopCount()} 起步，而该值是经 converter 从 DB 行读回来的。
 * 若 converter 漏了映射，字段恒为 null，循环预算会静默退回到「每次调用从 0 起算」——
 * 不报错，只是护栏失效，正是最该被测住的那类回归。
 */
class RunLoopCountConverterTest {

    private final RunConverter converter = new RunConverter();

    private static AgentRun row(Integer loopCount) {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setSessionId(2L);
        run.setStatus("RUNNING");
        run.setLoopCount(loopCount);
        return run;
    }

    @Test
    void loopCount_survivesRowToVo() {
        RunVO vo = converter.toVO(row(7));
        assertEquals(7, vo.getLoopCount());
    }

    @Test
    void loopCount_survivesVoToRow() {
        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setLoopCount(7);
        AgentRun entity = converter.toDO(vo);
        assertEquals(7, entity.getLoopCount());
    }

    @Test
    void loopCount_roundTripsWithoutLoss() {
        AgentRun back = converter.toDO(converter.toVO(row(42)));
        assertEquals(42, back.getLoopCount());
    }

    @Test
    void nullLoopCount_staysNullForLegacyRows() {
        // V82 之前的历史行没有该值；null 必须原样保留，不能被静默变成 0 之外的语义
        assertNull(converter.toVO(row(null)).getLoopCount());
    }
}