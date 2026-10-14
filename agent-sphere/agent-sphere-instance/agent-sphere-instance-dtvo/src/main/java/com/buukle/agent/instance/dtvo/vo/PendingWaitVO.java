package com.buukle.agent.instance.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 唤醒方（内存定时器 / cron 补偿）消费的一条等待记录。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingWaitVO implements Serializable {
    private Long id;
    private Long sessionId;
    private Long runId;
    private String callId;
    private Integer waitMs;
    private LocalDateTime wakeAt;
    private String status;
}