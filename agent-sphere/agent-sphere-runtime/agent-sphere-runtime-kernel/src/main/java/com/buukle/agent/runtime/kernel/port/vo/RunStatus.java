package com.buukle.agent.runtime.kernel.port.vo;

public enum RunStatus implements EventType {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, AWAITING_USER,
    /**
     * 等待时间到（wait 工具挂起）。与 {@code AWAITING_USER} 分开是因为前者等人、
     * 后者等时钟：共用一个状态会让前端把「等 90 秒」显示成「等你回答」，
     * 也会让输入框提前解锁。
     */
    WAITING;

    @Override
    public String value() {
        return name();
    }
}
