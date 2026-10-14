package com.buukle.agent.common.skill;

import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能 definition / promptTemplate 的规范校验。
 *
 * <p>技能正文必须遵循固定标题结构：{@code # <技能名>}（唯一 H1）+ {@code ## stepN <步骤名>}
 * （至少一个）+ {@code ### stepN.M <子步骤名>}（挂在其上方最近的、同编号的 {@code ## stepN} 之下），
 * 且不得层级跳跃。UI 表单（service）与 agent 技能工具共用本校验 —— 二者都经过它，
 * 单一实现避免口径漂移。
 *
 * <p>{@link #validate(String)} 返回 {@code null} 表示通过，否则返回「违规清单 + 标准模板」。
 */
public final class SkillTemplateValidator {

    /** 规范模板：与前端 {@code agent-sphere-ui/src/pages/capabilities/skill/index.tsx} 的 SKILL_TEMPLATE 保持一致。 */
    public static final String SKILL_TEMPLATE = """
            # <技能名称>

            ## 概述
            <何时使用 / 输入·输出 / 成功标准>

            ## step0 <准备>
            ### step0.1 <子步骤>
            ### step0.2 <子步骤>

            ## step1 <主步骤>
            ### step1.1 <子步骤>
            ### step1.2 <子步骤>

            ## step2 <主步骤>

            ## 收尾对账
            <完成前必须核对的收尾条件；输出格式 / 验收标准>""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKDOWN_FENCE = "```json";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern STEP_H2 = Pattern.compile("^step\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_H3 = Pattern.compile("^step\\s*\\d+\\.\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_H3_PARENT = Pattern.compile("^(step\\s*\\d+)\\.", Pattern.CASE_INSENSITIVE);

    private SkillTemplateValidator() {
    }

    /**
     * 校验 definition：JSON 形状（parameters + promptTemplate）→ promptTemplate 标题结构。
     *
     * @return 通过返回 {@code null}；否则返回可直接回给用户的错误文本（含违规点与模板）
     */
    public static String validate(String definition) {
        if (definition == null || definition.isBlank()) {
            return null; // 空定义沿用既有放行语义（由各自入口另行约束）
        }
        SkillDefinition def;
        try {
            def = SkillDefinitionParser.parse(definition);
        } catch (InvalidSubRunDefinition e) {
            return "Invalid definition: " + e.getMessage();
        }
        if (def == null) {
            return null;
        }
        return validateStructure(def.promptTemplate());
    }

    /** 结构校验；返回 null 表示合规。 */
    static String validateStructure(String promptTemplate) {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            return structureError(List.of("promptTemplate 为空"));
        }
        List<Heading> headings = parseHeadings(promptTemplate);
        List<String> problems = new ArrayList<>();

        long h1 = headings.stream().filter(h -> h.level() == 1).count();
        if (h1 != 1) {
            problems.add("必须有且仅有一个 H1 标题（# 技能名），当前 " + h1 + " 个");
        }
        if (headings.isEmpty() || headings.get(0).level() != 1) {
            problems.add("第一个标题必须是 H1（# 技能名）");
        }
        boolean hasStep = headings.stream()
                .anyMatch(h -> h.level() == 2 && STEP_H2.matcher(h.text()).find());
        if (!hasStep) {
            problems.add("至少需要一个步骤标题（## stepN 步骤名）");
        }

        int prevLevel = 0;
        String currentStep = null; // 上方最近的 ## stepN 的规范化前缀（非步骤 H2 → null）
        for (Heading h : headings) {
            int lvl = h.level();
            String text = h.text();
            if (prevLevel != 0 && lvl > prevLevel + 1) {
                problems.add("L" + h.line() + " 标题层级跳跃：H" + prevLevel + " → H" + lvl + "（「" + text + "」）");
            }
            if (lvl == 2) {
                Matcher m = STEP_H2.matcher(text);
                currentStep = m.find() ? normStepId(m.group()) : null;
            } else if (lvl == 3) {
                Matcher m3 = STEP_H3.matcher(text);
                if (!m3.find()) {
                    problems.add("L" + h.line() + " 子步骤必须写作 `### stepN.M 子步骤名`（当前「" + text + "」）");
                } else {
                    Matcher mp = STEP_H3_PARENT.matcher(text);
                    String parent = mp.find() ? normStepId(mp.group(1)) : null;
                    if (currentStep == null || !currentStep.equals(parent)) {
                        problems.add("L" + h.line() + " 子步骤「" + text + "」不在同编号的步骤之下（当前父步骤："
                                + (currentStep == null ? "无，请先写 ## stepN" : "## " + currentStep) + "）");
                    }
                }
            }
            prevLevel = lvl;
        }

        return problems.isEmpty() ? null : structureError(problems);
    }

    private static String structureError(List<String> problems) {
        StringBuilder sb = new StringBuilder("definition 结构不符合技能规范：");
        for (String p : problems) {
            sb.append("\n- ").append(p);
        }
        sb.append("\n请按以下模板重写 promptTemplate：\n\n").append(SKILL_TEMPLATE);
        return sb.toString();
    }

    private static String normStepId(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static List<Heading> parseHeadings(String markdown) {
        List<Heading> out = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING.matcher(lines[i]);
            if (m.matches()) {
                out.add(new Heading(i + 1, m.group(1).length(), m.group(2).trim()));
            }
        }
        return out;
    }

    private record Heading(int line, int level, String text) {
    }
}
