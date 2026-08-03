package io.github.wangyangxu.ailink.tool.impl.resume;

import io.github.wangyangxu.ailink.service.IintService;
import io.github.wangyangxu.ailink.tool.impl.word.WordOpHelper;
import io.github.wangyangxu.ailink.tool.impl.resume.ResumeData.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 生成 Word 简历文档操作。
 * <p>
 * 从 LLM 传入的结构化 JSON 数据生成格式规范的 Word 简历（.docx）。
 * 参考 resume-builder-cn 的 resume_writing_rules.md 中的排版与内容规则：
 * <ul>
 *   <li>学生默认 1 页，职场/科研 1-2 页</li>
 *   <li>使用 action + task + method + result 格式的 bullet</li>
 *   <li>中文简历使用 微软雅黑 字体，英文使用 Arial</li>
 *   <li>统一的日期、标题格式</li>
 * </ul>
 */
@Component
public class GenerateResumeOp implements ResumeOperation {

    private static final Logger log = LoggerFactory.getLogger(GenerateResumeOp.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "generate_resume";
    }

    @Override
    public String description() {
        return "generate_resume=从结构化JSON数据生成Word格式(.docx)简历文件";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", Map.of(
                "type", "string",
                "description", "姓名"
        ));
        params.put("data_json", Map.of(
                "type", "string",
                "description", "简历完整结构化数据（JSON格式），包含个人信息、教育背景、工作经历、项目、技能等所有字段。"
                        + "推荐由AI根据与用户的对话整理生成。详见 ResumeData 模型。"
        ));
        params.put("template", Map.of(
                "type", "string",
                "description", "简历模板风格：default（标准职场）、student（学生）、research（科研/学术CV）、modern（现代简洁），默认 default"
        ));
        params.put("language", Map.of(
                "type", "string",
                "enum", new String[]{"zh", "en", "bilingual"},
                "description", "简历语言：zh=中文, en=英文, bilingual=双语，默认 zh"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String name = args.has("name") ? args.get("name").asText() : "resume";
        String dataJson = args.has("data_json") ? args.get("data_json").asText() : null;
        String template = args.has("template") ? args.get("template").asText() : "default";
        String language = args.has("language") ? args.get("language").asText() : "zh";

        if (dataJson == null || dataJson.isBlank()) {
            return "{\"error\": \"缺少必填参数 data_json，请提供简历的结构化 JSON 数据\"}";
        }

        // 解析 JSON 到 ResumeData
        ResumeData data;
        try {
            data = objectMapper.readValue(dataJson, ResumeData.class);
        } catch (Exception e) {
            log.error("简历 JSON 解析失败: {}", dataJson, e);
            return "{\"error\": \"数据格式错误，无法解析简历 JSON: " + e.getMessage() + "\"}";
        }

        // 使用传入的 name 覆盖 data 中的 name
        if (data.getName() == null || data.getName().isBlank()) {
            data.setName(name);
        }

        // 设置语言
        data.setLanguage(language);

        Path resumeDir = Paths.get(outputDir);
        Files.createDirectories(resumeDir);

        String safeName = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeName + "_简历_" + System.currentTimeMillis() + ".docx";
        String filePath = resumeDir.resolve(fileName).toString();

        log.info("生成Word简历: name={}, template={}, language={}, filePath={}",
                name, template, language, filePath);

        try (XWPFDocument doc = new XWPFDocument()) {
            // 根据模板选择不同的渲染策略
            switch (template) {
                case "student":
                    renderStudentResume(doc, data);
                    break;
                case "research":
                    renderResearchResume(doc, data);
                    break;
                case "modern":
                    renderModernResume(doc, data);
                    break;
                default:
                    renderDefaultResume(doc, data);
                    break;
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                doc.write(fos);
            }
        }

        log.info("Word简历生成成功: {}", filePath);

        // 自动发送文件给当前用户
        boolean sent = WordOpHelper.sendFileToUser(filePath, iintService);
        if (sent) {
            log.info("简历文件已自动发送给用户: {}", filePath);
        }

        // 构建摘要
        StringBuilder summary = new StringBuilder();
        summary.append("✅ 简历已成功生成！\n\n");
        summary.append("📄 文件名：").append(fileName).append("\n");
        summary.append("📁 位置：").append(filePath).append("\n\n");
        summary.append("📋 简历概要：\n");
        summary.append("  - 姓名：").append(nullToDash(data.getName())).append("\n");
        summary.append("  - 目标岗位：").append(nullToDash(data.getTargetRole())).append("\n");
        summary.append("  - 语言：").append(getLanguageLabel(language)).append("\n");
        summary.append("  - 教育经历：").append(data.getEducationList().size()).append(" 条\n");
        summary.append("  - 工作经历：").append(data.getWorkExperienceList().size()).append(" 条\n");
        summary.append("  - 项目经历：").append(data.getProjectList().size()).append(" 条\n");
        summary.append("  - 技能类别：").append(data.getSkillList().size()).append(" 类\n");
        if (!data.getPublicationList().isEmpty()) {
            summary.append("  - 论文发表：").append(data.getPublicationList().size()).append(" 篇\n");
        }
        if (!data.getPatentList().isEmpty()) {
            summary.append("  - 专利：").append(data.getPatentList().size()).append(" 项\n");
        }
        if (!data.getAwardList().isEmpty()) {
            summary.append("  - 获奖荣誉：").append(data.getAwardList().size()).append(" 项\n");
        }

        return "{\"success\": true, \"file_path\": \"" + filePath
                + "\", \"file_name\": \"" + fileName
                + "\", \"sent\": " + sent
                + ", \"summary\": \"" + escapeJson(summary.toString()) + "\"}";
    }

    // ========= 默认职场简历 =========

    private void renderDefaultResume(XWPFDocument doc, ResumeData data) {
        boolean isZh = "zh".equals(data.getLanguage()) || "bilingual".equals(data.getLanguage());
        boolean isEn = "en".equals(data.getLanguage()) || "bilingual".equals(data.getLanguage());
        String headingFont = "微软雅黑";
        String bodyFont = isZh ? "微软雅黑" : "Arial";

        // === 页眉：姓名 + 联系方式 ===
        addHeaderBlock(doc, data, headingFont);

        // === 职业概述 ===
        if (data.getSummary() != null && !data.getSummary().isBlank()) {
            addSectionHeading(doc, isZh ? "职业概述" : "Professional Summary", headingFont);
            addBodyText(doc, data.getSummary(), bodyFont, false);
        }

        // === 工作经历 ===
        if (!data.getWorkExperienceList().isEmpty()) {
            addSectionHeading(doc, isZh ? "工作经历" : "Experience", headingFont);
            for (Experience exp : data.getWorkExperienceList()) {
                addExperienceEntry(doc, exp, bodyFont);
            }
        }

        // === 项目经历 ===
        if (!data.getProjectList().isEmpty()) {
            addSectionHeading(doc, isZh ? "项目经历" : "Projects", headingFont);
            for (Experience proj : data.getProjectList()) {
                addExperienceEntry(doc, proj, bodyFont);
            }
        }

        // === 教育背景 ===
        if (!data.getEducationList().isEmpty()) {
            addSectionHeading(doc, isZh ? "教育背景" : "Education", headingFont);
            for (Education edu : data.getEducationList()) {
                addEducationEntry(doc, edu, bodyFont);
            }
        }

        // === 核心技能 ===
        if (!data.getSkillList().isEmpty()) {
            addSectionHeading(doc, isZh ? "核心技能" : "Skills", headingFont);
            addSkillsSection(doc, data.getSkillList(), bodyFont);
        }

        // === 论文发表 ===
        if (!data.getPublicationList().isEmpty()) {
            addSectionHeading(doc, isZh ? "论文发表" : "Publications", headingFont);
            for (Publication pub : data.getPublicationList()) {
                addPublicationEntry(doc, pub, bodyFont);
            }
        }

        // === 专利 ===
        if (!data.getPatentList().isEmpty()) {
            addSectionHeading(doc, isZh ? "专利" : "Patents", headingFont);
            for (Patent patent : data.getPatentList()) {
                addPatentEntry(doc, patent, bodyFont);
            }
        }

        // === 获奖荣誉 ===
        if (!data.getAwardList().isEmpty()) {
            addSectionHeading(doc, isZh ? "获奖与荣誉" : "Awards & Honors", headingFont);
            for (Award award : data.getAwardList()) {
                addAwardEntry(doc, award, bodyFont);
            }
        }

        // === 语言能力/证书 ===
        boolean hasLang = !data.getLanguageList().isEmpty();
        boolean hasCert = !data.getCertificateList().isEmpty();
        if (hasLang || hasCert) {
            addSectionHeading(doc, isZh ? "语言与证书" : "Languages & Certifications", headingFont);
            if (hasLang) {
                for (Language lang : data.getLanguageList()) {
                    addBulletText(doc, formatLanguage(lang), bodyFont);
                }
            }
            if (hasCert) {
                for (String cert : data.getCertificateList()) {
                    addBulletText(doc, cert, bodyFont);
                }
            }
        }
    }

    // ========= 学生简历 =========

    private void renderStudentResume(XWPFDocument doc, ResumeData data) {
        boolean isZh = "zh".equals(data.getLanguage());
        String headingFont = "微软雅黑";
        String bodyFont = "微软雅黑";

        addHeaderBlock(doc, data, headingFont);

        if (data.getSummary() != null && !data.getSummary().isBlank()) {
            addSectionHeading(doc, isZh ? "个人简介" : "Profile", headingFont);
            addBodyText(doc, data.getSummary(), bodyFont, false);
        }

        // 学生简历：教育 > 项目 > 实习 > 技能 > 奖励
        if (!data.getEducationList().isEmpty()) {
            addSectionHeading(doc, isZh ? "教育背景" : "Education", headingFont);
            for (Education edu : data.getEducationList()) {
                addEducationEntry(doc, edu, bodyFont);
            }
        }

        if (!data.getProjectList().isEmpty()) {
            addSectionHeading(doc, isZh ? "项目经历" : "Projects", headingFont);
            for (Experience proj : data.getProjectList()) {
                addExperienceEntry(doc, proj, bodyFont);
            }
        }

        if (!data.getInternshipList().isEmpty()) {
            addSectionHeading(doc, isZh ? "实习经历" : "Internships", headingFont);
            for (Experience intern : data.getInternshipList()) {
                addExperienceEntry(doc, intern, bodyFont);
            }
        }

        if (!data.getSkillList().isEmpty()) {
            addSectionHeading(doc, isZh ? "技能" : "Skills", headingFont);
            addSkillsSection(doc, data.getSkillList(), bodyFont);
        }

        if (!data.getAwardList().isEmpty()) {
            addSectionHeading(doc, isZh ? "获奖与荣誉" : "Awards & Honors", headingFont);
            for (Award award : data.getAwardList()) {
                addAwardEntry(doc, award, bodyFont);
            }
        }
    }

    // ========= 科研简历 =========

    private void renderResearchResume(XWPFDocument doc, ResumeData data) {
        boolean isZh = "zh".equals(data.getLanguage());
        String headingFont = "微软雅黑";
        String bodyFont = "微软雅黑";

        addHeaderBlock(doc, data, headingFont);

        if (data.getSummary() != null && !data.getSummary().isBlank()) {
            addSectionHeading(doc, isZh ? "研究兴趣" : "Research Interests", headingFont);
            addBodyText(doc, data.getSummary(), bodyFont, false);
        }

        if (!data.getPublicationList().isEmpty()) {
            addSectionHeading(doc, isZh ? "代表性论文" : "Selected Publications", headingFont);
            for (Publication pub : data.getPublicationList()) {
                addPublicationEntry(doc, pub, bodyFont);
            }
        }

        // 科研简历：研究经历
        if (!data.getProjectList().isEmpty()) {
            addSectionHeading(doc, isZh ? "研究经历" : "Research Experience", headingFont);
            for (Experience proj : data.getProjectList()) {
                addExperienceEntry(doc, proj, bodyFont);
            }
        }

        if (!data.getWorkExperienceList().isEmpty()) {
            addSectionHeading(doc, isZh ? "工作经历" : "Professional Experience", headingFont);
            for (Experience exp : data.getWorkExperienceList()) {
                addExperienceEntry(doc, exp, bodyFont);
            }
        }

        if (!data.getEducationList().isEmpty()) {
            addSectionHeading(doc, isZh ? "教育背景" : "Education", headingFont);
            for (Education edu : data.getEducationList()) {
                addEducationEntry(doc, edu, bodyFont);
            }
        }

        if (!data.getSkillList().isEmpty()) {
            addSectionHeading(doc, isZh ? "实验/计算方法与技能" : "Methods & Skills", headingFont);
            addSkillsSection(doc, data.getSkillList(), bodyFont);
        }

        if (!data.getPatentList().isEmpty()) {
            addSectionHeading(doc, isZh ? "专利" : "Patents", headingFont);
            for (Patent patent : data.getPatentList()) {
                addPatentEntry(doc, patent, bodyFont);
            }
        }

        if (!data.getAwardList().isEmpty()) {
            addSectionHeading(doc, isZh ? "荣誉与奖励" : "Honors & Awards", headingFont);
            for (Award award : data.getAwardList()) {
                addAwardEntry(doc, award, bodyFont);
            }
        }
    }

    // ========= 现代简洁简历 =========

    private void renderModernResume(XWPFDocument doc, ResumeData data) {
        boolean isZh = "zh".equals(data.getLanguage());
        String headingFont = "微软雅黑";
        String bodyFont = "微软雅黑";

        // 现代风格：使用 A4 宽度设计，精简排版
        addHeaderBlock(doc, data, headingFont);

        // 两栏式：左侧技能+教育，右侧经历
        if (data.getSummary() != null && !data.getSummary().isBlank()) {
            addBodyText(doc, data.getSummary(), bodyFont, true);
        }

        if (!data.getWorkExperienceList().isEmpty()) {
            addSectionHeading(doc, isZh ? "工作经历" : "Experience", headingFont);
            for (Experience exp : data.getWorkExperienceList()) {
                addExperienceCompact(doc, exp, bodyFont);
            }
        }

        if (!data.getProjectList().isEmpty()) {
            addSectionHeading(doc, isZh ? "项目" : "Projects", headingFont);
            for (Experience proj : data.getProjectList()) {
                addExperienceCompact(doc, proj, bodyFont);
            }
        }

        if (!data.getEducationList().isEmpty()) {
            addSectionHeading(doc, isZh ? "教育" : "Education", headingFont);
            for (Education edu : data.getEducationList()) {
                addEducationCompact(doc, edu, bodyFont);
            }
        }

        if (!data.getSkillList().isEmpty()) {
            addSectionHeading(doc, isZh ? "技能" : "Skills", headingFont);
            addSkillsSection(doc, data.getSkillList(), bodyFont);
        }
    }

    // ========= 通用排版方法 =========

    private void addHeaderBlock(XWPFDocument doc, ResumeData data, String headingFont) {
        // 姓名
        XWPFParagraph namePara = doc.createParagraph();
        namePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun nameRun = namePara.createRun();
        nameRun.setText(data.getName() != null ? data.getName() : "");
        nameRun.setBold(true);
        nameRun.setFontSize(24);
        nameRun.setFontFamily(headingFont);
        nameRun.setColor("1a1a2e");

        // 目标岗位
        if (data.getTargetRole() != null && !data.getTargetRole().isBlank()) {
            XWPFParagraph targetPara = doc.createParagraph();
            targetPara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun targetRun = targetPara.createRun();
            String prefix = "zh".equals(data.getLanguage()) ? "目标岗位：" : "Target: ";
            targetRun.setText(prefix + data.getTargetRole());
            targetRun.setFontSize(11);
            targetRun.setFontFamily(headingFont);
            targetRun.setColor("0f6b62");
            targetRun.setBold(true);
        }

        // 联系方式行
        List<String> contactParts = new ArrayList<>();
        if (data.getPhone() != null) contactParts.add(data.getPhone());
        if (data.getEmail() != null) contactParts.add(data.getEmail());
        if (data.getLocation() != null) contactParts.add(data.getLocation());
        if (data.getGithub() != null) contactParts.add("GitHub: " + data.getGithub());
        if (data.getLinkedin() != null) contactParts.add("LinkedIn: " + data.getLinkedin());

        if (!contactParts.isEmpty()) {
            XWPFParagraph contactPara = doc.createParagraph();
            contactPara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun contactRun = contactPara.createRun();
            contactRun.setText(String.join("  |  ", contactParts));
            contactRun.setFontSize(9);
            contactRun.setFontFamily(headingFont);
            contactRun.setColor("52606d");
        }

        // 空行
        addEmptyLine(doc);
    }

    private void addSectionHeading(XWPFDocument doc, String text, String fontFamily) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(200);
        para.setSpacingAfter(100);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(14);
        run.setFontFamily(fontFamily);
        run.setColor("0f6b62");

        // 添加下划线装饰
        XWPFParagraph linePara = doc.createParagraph();
        linePara.setSpacingAfter(80);
        XWPFRun lineRun = linePara.createRun();
        lineRun.setText("————————————————");
        lineRun.setFontSize(8);
        lineRun.setColor("0f6b62");
        lineRun.setFontFamily(fontFamily);
    }

    private void addEducationEntry(XWPFDocument doc, Education edu, String bodyFont) {
        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setSpacingBefore(120);
        XWPFRun titleRun = titlePara.createRun();
        // 学校 + 学位 + 专业
        StringBuilder title = new StringBuilder();
        if (edu.getInstitution() != null) title.append(edu.getInstitution());
        if (edu.getDegree() != null) title.append("  |  ").append(edu.getDegree());
        if (edu.getMajor() != null) title.append("  ·  ").append(edu.getMajor());
        titleRun.setText(title.toString());
        titleRun.setBold(true);
        titleRun.setFontSize(11);
        titleRun.setFontFamily(bodyFont);

        // 日期
        if (edu.getStartDate() != null || edu.getEndDate() != null) {
            XWPFRun dateRun = titlePara.createRun();
            dateRun.setText("    " + (edu.getStartDate() != null ? edu.getStartDate() : "")
                    + " - " + (edu.getEndDate() != null ? edu.getEndDate() : ""));
            dateRun.setFontSize(10);
            dateRun.setFontFamily(bodyFont);
            dateRun.setColor("52606d");
        }

        // GPA
        if (edu.getGpa() != null && !edu.getGpa().isBlank()) {
            XWPFParagraph gpaPara = doc.createParagraph();
            XWPFRun gpaRun = gpaPara.createRun();
            gpaRun.setText("GPA: " + edu.getGpa());
            gpaRun.setFontSize(10);
            gpaRun.setFontFamily(bodyFont);
            gpaRun.setColor("52606d");
        }

        // 相关课程
        if (edu.getRelevantCourses() != null && !edu.getRelevantCourses().isEmpty()) {
            XWPFParagraph coursePara = doc.createParagraph();
            XWPFRun courseRun = coursePara.createRun();
            courseRun.setText("相关课程: " + String.join("、", edu.getRelevantCourses()));
            courseRun.setFontSize(10);
            courseRun.setFontFamily(bodyFont);
            courseRun.setColor("52606d");
        }

        // 荣誉
        if (edu.getHonors() != null && !edu.getHonors().isBlank()) {
            XWPFParagraph honorPara = doc.createParagraph();
            XWPFRun honorRun = honorPara.createRun();
            honorRun.setText(edu.getHonors());
            honorRun.setFontSize(10);
            honorRun.setFontFamily(bodyFont);
            honorRun.setColor("52606d");
        }
    }

    private void addEducationCompact(XWPFDocument doc, Education edu, String bodyFont) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(60);
        XWPFRun run = para.createRun();
        StringBuilder text = new StringBuilder();
        if (edu.getInstitution() != null) text.append(edu.getInstitution());
        if (edu.getDegree() != null) text.append("  |  ").append(edu.getDegree());
        if (edu.getMajor() != null) text.append("  ·  ").append(edu.getMajor());
        if (edu.getStartDate() != null || edu.getEndDate() != null) {
            text.append("  (").append(nullToEmpty(edu.getStartDate()))
                    .append(" - ").append(nullToEmpty(edu.getEndDate())).append(")");
        }
        run.setText(text.toString());
        run.setFontSize(10);
        run.setFontFamily(bodyFont);
    }

    private void addExperienceEntry(XWPFDocument doc, Experience exp, String bodyFont) {
        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setSpacingBefore(120);
        XWPFRun titleRun = titlePara.createRun();
        String title = exp.getRole() != null ? exp.getRole() : "";
        titleRun.setText(title);
        titleRun.setBold(true);
        titleRun.setFontSize(11);
        titleRun.setFontFamily(bodyFont);

        // 公司/组织 + 日期
        if (exp.getOrganization() != null || exp.getStartDate() != null) {
            XWPFRun metaRun = titlePara.createRun();
            String meta = "";
            if (exp.getOrganization() != null) meta += exp.getOrganization();
            if (exp.getLocation() != null) meta += "  ·  " + exp.getLocation();
            String dateStr = formatDateRange(exp.getStartDate(), exp.getEndDate());
            if (!dateStr.isEmpty()) {
                meta += "    " + dateStr;
            }
            if (!meta.isEmpty()) {
                metaRun.setText("    " + meta);
                metaRun.setFontSize(10);
                metaRun.setFontFamily(bodyFont);
                metaRun.setColor("52606d");
            }
        }

        // 项目范围
        if (exp.getScope() != null && !exp.getScope().isBlank()) {
            XWPFParagraph scopePara = doc.createParagraph();
            XWPFRun scopeRun = scopePara.createRun();
            scopeRun.setText(exp.getScope());
            scopeRun.setFontSize(10);
            scopeRun.setFontFamily(bodyFont);
            scopeRun.setItalic(true);
            scopeRun.setColor("52606d");
        }

        // Bullet points
        List<String> bullets = exp.getBulletPoints();
        if (bullets != null && !bullets.isEmpty()) {
            for (String bullet : bullets) {
                if (bullet != null && !bullet.isBlank()) {
                    addBulletText(doc, bullet, bodyFont);
                }
            }
        } else if (exp.getResponsibilities() != null && !exp.getResponsibilities().isEmpty()) {
            for (String resp : exp.getResponsibilities()) {
                if (resp != null && !resp.isBlank()) {
                    addBulletText(doc, resp, bodyFont);
                }
            }
        }

        // 方法/工具/结果
        if (exp.getMethods() != null || exp.getTools() != null || exp.getResults() != null) {
            StringBuilder details = new StringBuilder();
            if (exp.getMethods() != null) details.append("方法：").append(exp.getMethods()).append("；");
            if (exp.getTools() != null) details.append("工具：").append(exp.getTools()).append("；");
            if (exp.getResults() != null) details.append("成果：").append(exp.getResults());
            if (details.length() > 0) {
                addBulletText(doc, details.toString(), bodyFont);
            }
        }
    }

    private void addExperienceCompact(XWPFDocument doc, Experience exp, String bodyFont) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(60);
        XWPFRun run = para.createRun();
        StringBuilder text = new StringBuilder();
        if (exp.getRole() != null) text.append(exp.getRole());
        if (exp.getOrganization() != null) text.append("  @  ").append(exp.getOrganization());
        String dateStr = formatDateRange(exp.getStartDate(), exp.getEndDate());
        if (!dateStr.isEmpty()) {
            text.append("  (").append(dateStr).append(")");
        }
        run.setText(text.toString());
        run.setBold(true);
        run.setFontSize(10);
        run.setFontFamily(bodyFont);

        if (exp.getBulletPoints() != null) {
            for (String bullet : exp.getBulletPoints()) {
                if (bullet != null && !bullet.isBlank()) {
                    addBulletText(doc, bullet, bodyFont);
                }
            }
        }
    }

    private void addSkillsSection(XWPFDocument doc, List<SkillCategory> skills, String bodyFont) {
        for (SkillCategory cat : skills) {
            if (cat.getCategory() == null || cat.getCategory().isBlank()) continue;
            XWPFParagraph para = doc.createParagraph();
            XWPFRun catRun = para.createRun();
            catRun.setText(cat.getCategory() + "：");
            catRun.setBold(true);
            catRun.setFontSize(10);
            catRun.setFontFamily(bodyFont);

            if (cat.getSkills() != null && !cat.getSkills().isEmpty()) {
                XWPFRun skillRun = para.createRun();
                skillRun.setText(String.join("、", cat.getSkills()));
                skillRun.setFontSize(10);
                skillRun.setFontFamily(bodyFont);
                skillRun.setColor("333333");
            }
        }
    }

    private void addPublicationEntry(XWPFDocument doc, Publication pub, String bodyFont) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(80);
        XWPFRun run = para.createRun();
        StringBuilder text = new StringBuilder();
        if (pub.getAuthors() != null) text.append(pub.getAuthors()).append(". ");
        text.append(pub.getTitle() != null ? "《" + pub.getTitle() + "》" : "");
        if (pub.getJournal() != null) text.append(". ").append(pub.getJournal());
        if (pub.getYear() != null) text.append(". ").append(pub.getYear());
        if (pub.getVolume() != null) text.append("; ").append(pub.getVolume());
        if (pub.getIssue() != null) text.append("(").append(pub.getIssue()).append(")");
        if (pub.getPages() != null) text.append(": ").append(pub.getPages());
        if (pub.getDoi() != null) text.append(". DOI: ").append(pub.getDoi());

        run.setText(text.toString());
        run.setFontSize(10);
        run.setFontFamily(bodyFont);
    }

    private void addPatentEntry(XWPFDocument doc, Patent patent, String bodyFont) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(60);
        XWPFRun run = para.createRun();
        StringBuilder text = new StringBuilder();
        text.append(patent.getTitle() != null ? patent.getTitle() : "");
        if (patent.getPatentNumber() != null) text.append(" (").append(patent.getPatentNumber()).append(")");
        if (patent.getInventors() != null) text.append(". 发明人：").append(patent.getInventors());
        if (patent.getStatus() != null) text.append(". 状态：").append(patent.getStatus());

        run.setText(text.toString());
        run.setFontSize(10);
        run.setFontFamily(bodyFont);
    }

    private void addAwardEntry(XWPFDocument doc, Award award, String bodyFont) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(40);
        XWPFRun run = para.createRun();
        StringBuilder text = new StringBuilder();
        text.append("• ").append(award.getTitle() != null ? award.getTitle() : "");
        if (award.getOrganization() != null) text.append(" — ").append(award.getOrganization());
        if (award.getDate() != null) text.append(" (").append(award.getDate()).append(")");

        run.setText(text.toString());
        run.setFontSize(10);
        run.setFontFamily(bodyFont);
    }

    // ========= 辅助排版方法 =========

    private void addBodyText(XWPFDocument doc, String text, String fontFamily, boolean italic) {
        XWPFParagraph para = doc.createParagraph();
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontSize(10);
        run.setFontFamily(fontFamily);
        run.setItalic(italic);
        run.setColor("333333");
    }

    private void addBulletText(XWPFDocument doc, String text, String bodyFont) {
        XWPFParagraph para = doc.createParagraph();
        para.setIndentationLeft(420);
        para.setIndentationHanging(200);
        XWPFRun run = para.createRun();
        run.setText("•  " + text);
        run.setFontSize(10);
        run.setFontFamily(bodyFont);
        run.setColor("333333");
    }

    private void addEmptyLine(XWPFDocument doc) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingAfter(0);
        para.setSpacingBefore(0);
        XWPFRun run = para.createRun();
        run.setText("");
        run.setFontSize(6);
    }

    private String formatDateRange(String start, String end) {
        if (start == null && end == null) return "";
        if (start == null) return end;
        if (end == null) return start + " - 至今";
        return start + " - " + end;
    }

    private String formatLanguage(Language lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(lang.getLanguage() != null ? lang.getLanguage() : "");
        if (lang.getReading() != null || lang.getWriting() != null || lang.getSpeaking() != null) {
            sb.append("（");
            if (lang.getReading() != null) sb.append("读:").append(lang.getReading()).append(" ");
            if (lang.getWriting() != null) sb.append("写:").append(lang.getWriting()).append(" ");
            if (lang.getSpeaking() != null) sb.append("说:").append(lang.getSpeaking());
            sb.append("）");
        }
        if (lang.getCertification() != null) {
            sb.append(" [" + lang.getCertification() + "]");
        }
        return sb.toString();
    }

    private String getLanguageLabel(String lang) {
        switch (lang) {
            case "zh": return "中文";
            case "en": return "English";
            case "bilingual": return "中英双语";
            default: return lang;
        }
    }

    private String nullToDash(String s) {
        return s != null && !s.isBlank() ? s : "-";
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 通过 WechatBotService 将简历文件发送给当前用户。
     *
     * @return true 表示发送成功
     */
}
