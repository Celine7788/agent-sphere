package com.buukle.agent.common.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能结构校验的规则护栏（UI 表单与 agent 工具共用此校验）。 */
class SkillTemplateValidatorTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String OK = "# 技能\n\n## step1 步骤\n### step1.1 子步骤\n正文";

    private static String def(String promptTemplate) {
        ObjectNode root = M.createObjectNode();
        root.put("version", 1);
        root.putObject("parameters").put("type", "object").putObject("properties");
        root.put("promptTemplate", promptTemplate);
        return root.toString();
    }

    @Test
    void conformant_passes() {
        assertNull(SkillTemplateValidator.validate(def(OK)));
    }

    @Test
    void blankOrNull_passes() {
        assertNull(SkillTemplateValidator.validate(""));
        assertNull(SkillTemplateValidator.validate(null));
    }

    @Test
    void legacyPromptWithoutStructure_isAlsoRejected() {
        // 强校验：legacy {"prompt":...} 无标题结构 → 同样被拒
        String err = SkillTemplateValidator.validate("{\"prompt\":\"请执行\"}");
        assertNotNull(err);
        assertTrue(err.contains("结构不符合技能规范"), err);
    }

    @Test
    void missingH1_rejectedWithTemplate() {
        String err = SkillTemplateValidator.validate(def("## step1 步骤\n正文"));
        assertNotNull(err);
        assertTrue(err.contains("H1"), err);
        assertTrue(err.contains("# <技能名称>"), "应回带标准模板: " + err);
    }

    @Test
    void missingStepHeading_rejected() {
        String err = SkillTemplateValidator.validate(def("# 技能\n\n## 概述\n正文"));
        assertNotNull(err);
        assertTrue(err.contains("步骤"), err);
    }

    @Test
    void substepUnderWrongStep_rejected() {
        String err = SkillTemplateValidator.validate(def("# 技能\n\n## step1 A\n\n## step2 B\n### step1.1 X\n"));
        assertNotNull(err);
        assertTrue(err.contains("step1.1"), err);
    }

    @Test
    void levelJump_rejected() {
        String err = SkillTemplateValidator.validate(def("# 技能\n\n### step1.1 深跳\n\n## step1 A\n"));
        assertNotNull(err);
        assertTrue(err.contains("层级跳跃"), err);
    }

    @Test
    void nonStepH3_rejected() {
        String err = SkillTemplateValidator.validate(def("# 技能\n\n## 概述\n### 背景\n\n## step1 A\n"));
        assertNotNull(err);
    }

    @Test
    void malformedJson_rejected() {
        assertTrue(SkillTemplateValidator.validate("not-json").contains("Invalid definition"));
    }
}
