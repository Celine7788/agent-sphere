package com.buukle.agent.capability.skill.dtvo.constants;

public final class SkillConstants {

    /**
     * skill definition 的最大字符数。列类型为 JSONB，无长度上限，此处仅为拒绝异常巨大的报文。
     */
    public static final int MAX_DEFINITION_LENGTH = 100000;

    private SkillConstants() {
    }
}
