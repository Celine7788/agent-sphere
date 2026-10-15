package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.service.converter.RunConverter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunConverterTest {

    private final RunConverter converter = new RunConverter();

    @Test
    void toDO_persistsAttachmentsAsJson() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setSessionId(1L);
        dto.setType(RunEnum.TYPE_AUTO);
        dto.setUserMessage("看图");
        dto.setAttachments(List.of(
                new RunAttachment("key-a", "image/png"),
                new RunAttachment("key-b", "image/jpeg")));

        AgentRun run = converter.toDO(dto);

        assertEquals("看图", run.getUserMessage());
        assertEquals("[{\"fileKey\":\"key-a\",\"contentType\":\"image/png\"},{\"fileKey\":\"key-b\",\"contentType\":\"image/jpeg\"}]",
                run.getAttachments());
    }

    @Test
    void toDO_skipsEmptyAttachments() {
        CreateRunDTO dto = new CreateRunDTO();
        dto.setSessionId(1L);
        dto.setType(RunEnum.TYPE_AUTO);
        dto.setUserMessage("hi");
        dto.setAttachments(List.of());

        AgentRun run = converter.toDO(dto);

        assertNull(run.getAttachments());
    }

    @Test
    void toVO_restoresAttachments() {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setSessionId(1L);
        run.setType(RunEnum.TYPE_AUTO);
        run.setUserMessage("看图");
        run.setAttachments("[{\"fileKey\":\"key-a\",\"contentType\":\"image/png\"}]");

        RunVO vo = converter.toVO(run);

        assertEquals(1, vo.getAttachments().size());
        assertEquals("key-a", vo.getAttachments().get(0).fileKey());
        assertEquals("image/png", vo.getAttachments().get(0).contentType());
    }

    @Test
    void toVO_handlesNullAttachments() {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setUserMessage("no imagie");

        RunVO vo = converter.toVO(run);

        assertNull(vo.getAttachments());
    }

    @Test
    void errorMessage_roundTripsBothDirections() {
        // RunConverter 是手写的，漏掉一个方向不会报错、只会让失败原因静默丢失（V87 的教训）
        RunVO vo = new RunVO();
        vo.setId(1L);
        vo.setErrorMessage("rate limited by upstream");

        AgentRun do_ = converter.toDO(vo);
        assertEquals("rate limited by upstream", do_.getErrorMessage(), "VO → DO 必须带上失败原因");

        RunVO back = converter.toVO(do_);
        assertEquals("rate limited by upstream", back.getErrorMessage(), "DO → VO 必须带上失败原因");
    }
}