package io.github.wangyangxu.ailink.tool.impl.resume;

import io.github.wangyangxu.ailink.service.IintService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wangyangxu.ailink.tool.impl.resume.ResumeData.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 生成 HTML 格式简历操作。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>{@code ats} — ATS 友好单栏 HTML（参考 resume_ats_clean.html 模板）</li>
 *   <li>{@code editable} — 可编辑 HTML（参考 resume_editable_tuning.html 模板，支持 CSS 变量微调）</li>
 * </ul>
 */
@Component
public class ExportHtmlResumeOp implements ResumeOperation {

    private static final Logger log = LoggerFactory.getLogger(ExportHtmlResumeOp.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "generate_html";
    }

    @Override
    public String description() {
        return "generate_html=生成HTML格式简历，支持 ats（ATS友好单栏）和 editable（可编辑版，支持CSS变量微调）两种风格";
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
                "description", "简历完整结构化数据（JSON格式），同 generate_resume"
        ));
        params.put("html_style", Map.of(
                "type", "string",
                "enum", new String[]{"ats", "editable"},
                "description", "HTML风格：ats=ATS友好单栏, editable=可编辑版（支持CSS微调），默认 ats"
        ));
        params.put("language", Map.of(
                "type", "string",
                "enum", new String[]{"zh", "en"},
                "description", "简历语言：zh=中文, en=英文，默认 zh"
        ));
        return params;
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String name = args.has("name") ? args.get("name").asText() : "resume";
        String dataJson = args.has("data_json") ? args.get("data_json").asText() : null;
        String htmlStyle = args.has("html_style") ? args.get("html_style").asText() : "ats";
        String language = args.has("language") ? args.get("language").asText() : "zh";

        if (dataJson == null || dataJson.isBlank()) {
            return "{\"error\": \"缺少必填参数 data_json\"}";
        }

        ResumeData data;
        try {
            data = objectMapper.readValue(dataJson, ResumeData.class);
        } catch (Exception e) {
            return "{\"error\": \"数据格式错误: " + e.getMessage() + "\"}";
        }
        if (data.getName() == null || data.getName().isBlank()) {
            data.setName(name);
        }
        data.setLanguage(language);

        Path resumeDir = Paths.get(outputDir);
        Files.createDirectories(resumeDir);

        String safeName = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeName + "_简历_" + (htmlStyle.equals("editable") ? "可编辑" : "ATS")
                + "_" + System.currentTimeMillis() + ".html";
        String filePath = resumeDir.resolve(fileName).toString();

        String html;
        if ("editable".equals(htmlStyle)) {
            html = generateEditableHtml(data);
        } else {
            html = generateAtsHtml(data);
        }

        Files.writeString(Paths.get(filePath), html, java.nio.charset.StandardCharsets.UTF_8);

        log.info("HTML简历生成成功: {}", filePath);

        return "{\"success\": true, \"file_path\": \"" + filePath
                + "\", \"file_name\": \"" + fileName + "\"}";
    }

    /**
     * 生成 ATS 友好单栏 HTML 简历。
     */
    private String generateAtsHtml(ResumeData data) {
        boolean isZh = "zh".equals(data.getLanguage());
        StringBuilder html = new StringBuilder();
        String langCode = isZh ? "zh-CN" : "en";
        String name = safeHtml(data.getName());
        String role = safeHtml(data.getTargetRole());

        html.append("<!doctype html>\n")
            .append("<html lang=\"").append(langCode).append("\">\n")
            .append("<head>\n")
            .append("  <meta charset=\"utf-8\">\n")
            .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("  <title>").append(name).append(" - ").append(role).append("</title>\n")
            .append("  <style>\n")
            .append("    :root {\n")
            .append("      --ink: #17212b; --muted: #52606d; --accent: #0f6b62;\n")
            .append("      --rule: #cbd5dc; --paper: #ffffff; --screen: #edf1f3;\n")
            .append("      --page-width: 210mm; --page-padding-x: 14mm; --page-padding-y: 12mm;\n")
            .append("      --body-size: 10pt; --small-size: 8.7pt; --line-height: 1.34;\n")
            .append("    }\n")
            .append("    * { box-sizing: border-box; margin: 0; padding: 0; }\n")
            .append("    html { background: var(--screen); }\n")
            .append("    body { width: var(--page-width); margin: 18px auto; padding: var(--page-padding-y) var(--page-padding-x);\n")
            .append("           background: var(--paper); color: var(--ink); font-family: Arial, 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif;\n")
            .append("           font-size: var(--body-size); line-height: var(--line-height); }\n")
            .append("    a { color: inherit; text-decoration: none; }\n")
            .append("    header { display: grid; grid-template-columns: 1fr auto; gap: 7mm; align-items: end;\n")
            .append("             padding-bottom: 3.5mm; border-bottom: 1.5px solid var(--accent); }\n")
            .append("    h1 { margin: 0; font-size: 24pt; font-weight: 700; }\n")
            .append("    .target { margin-top: 2mm; color: var(--accent); font-size: 11pt; font-weight: 700; }\n")
            .append("    .contact { color: var(--muted); font-size: var(--small-size); text-align: right; }\n")
            .append("    section { margin-top: 4mm; }\n")
            .append("    h2 { margin: 0 0 1.8mm; color: var(--accent); font-size: 10.5pt; font-weight: 700;\n")
            .append("         text-transform: uppercase; border-bottom: 1px solid var(--rule); padding-bottom: 1mm; }\n")
            .append("    .entry { margin-top: 2.5mm; }\n")
            .append("    .entry-head { display: grid; grid-template-columns: 1fr auto; gap: 5mm; align-items: baseline; }\n")
            .append("    .entry-title { font-weight: 700; }\n")
            .append("    .entry-meta, .entry-date { color: var(--muted); font-size: var(--small-size); }\n")
            .append("    .entry-date { white-space: nowrap; text-align: right; }\n")
            .append("    ul { margin: 1mm 0 0 4.2mm; padding: 0; }\n")
            .append("    li { margin: 0.7mm 0; padding-left: 0.8mm; }\n")
            .append("    .skills { display: grid; grid-template-columns: 30mm 1fr; gap: 1.3mm 4mm; }\n")
            .append("    .skills dt { font-weight: 700; }\n")
            .append("    .skills dd { margin: 0; }\n")
            .append("    @page { size: A4; margin: 0; }\n")
            .append("    @media print { html { background: var(--paper); } body { margin: 0; box-shadow: none; } }\n")
            .append("  </style>\n")
            .append("</head>\n")
            .append("<body>\n");

        // Header
        html.append("  <header>\n")
            .append("    <div>\n")
            .append("      <h1>").append(name).append("</h1>\n");
        if (role != null && !role.isEmpty()) {
            html.append("      <div class=\"target\">").append(role).append("</div>\n");
        }
        html.append("    </div>\n")
            .append("    <div class=\"contact\">\n")
            .append("      ").append(buildContactLine(data)).append("\n")
            .append("    </div>\n")
            .append("  </header>\n\n")
            .append("  <main>\n");

        // Summary
        if (data.getSummary() != null && !data.getSummary().isBlank()) {
            html.append("    <section>\n")
                .append("      <h2>").append(isZh ? "职业概述" : "Professional Summary").append("</h2>\n")
                .append("      <p>").append(safeHtml(data.getSummary())).append("</p>\n")
                .append("    </section>\n\n");
        }

        // Work Experience
        if (!data.getWorkExperienceList().isEmpty()) {
            html.append("    <section>\n")
                .append("      <h2>").append(isZh ? "工作经历" : "Experience").append("</h2>\n");
            for (Experience exp : data.getWorkExperienceList()) {
                html.append(experienceToHtml(exp));
            }
            html.append("    </section>\n\n");
        }

        // Projects
        if (!data.getProjectList().isEmpty()) {
            html.append("    <section>\n")
                .append("      <h2>").append(isZh ? "项目经历" : "Projects").append("</h2>\n");
            for (Experience proj : data.getProjectList()) {
                html.append(experienceToHtml(proj));
            }
            html.append("    </section>\n\n");
        }

        // Education
        if (!data.getEducationList().isEmpty()) {
            html.append("    <section>\n")
                .append("      <h2>").append(isZh ? "教育背景" : "Education").append("</h2>\n");
            for (Education edu : data.getEducationList()) {
                html.append(educationToHtml(edu));
            }
            html.append("    </section>\n\n");
        }

        // Skills
        if (!data.getSkillList().isEmpty()) {
            html.append("    <section>\n")
                .append("      <h2>").append(isZh ? "核心技能" : "Skills").append("</h2>\n")
                .append("      <dl class=\"skills\">\n");
            for (SkillCategory cat : data.getSkillList()) {
                if (cat.getCategory() == null) continue;
                html.append("        <dt>").append(safeHtml(cat.getCategory())).append("</dt>\n")
                    .append("        <dd>").append(safeHtml(String.join(", ", cat.getSkills()))).append("</dd>\n");
            }
            html.append("      </dl>\n")
                .append("    </section>\n\n");
        }

        html.append("  </main>\n")
            .append("</body>\n")
            .append("</html>\n");

        return html.toString();
    }

    /**
     * 生成可编辑 HTML 简历（带 CSS 变量控制）。
     */
    private String generateEditableHtml(ResumeData data) {
        boolean isZh = "zh".equals(data.getLanguage());
        String name = safeHtml(data.getName());
        StringBuilder html = new StringBuilder();

        html.append("<!doctype html>\n")
            .append("<html lang=\"").append(isZh ? "zh-CN" : "en").append("\">\n")
            .append("<head>\n")
            .append("  <meta charset=\"utf-8\">\n")
            .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("  <title>").append(name).append(" - ").append(safeHtml(data.getTargetRole())).append("</title>\n")
            .append("  <style>\n")
            .append("    :root {\n")
            .append("      --body-size: 10pt;\n")
            .append("      --line-height: 1.34;\n")
            .append("      --page-padding-x: 14mm;\n")
            .append("      --page-padding-y: 12mm;\n")
            .append("      --content-align: justify;\n")
            .append("      --ink: #17212b;\n")
            .append("      --accent: #0f6b62;\n")
            .append("      --muted: #52606d;\n")
            .append("      --paper: #ffffff;\n")
            .append("    }\n")
            .append("    /* 修改上方 CSS 变量即可微调排版 */\n")
            .append("    body { font-family: Arial, 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif;\n")
            .append("           font-size: var(--body-size); line-height: var(--line-height);\n")
            .append("           color: var(--ink); background: var(--paper);\n")
            .append("           max-width: 210mm; margin: 0 auto; padding: var(--page-padding-y) var(--page-padding-x); }\n")
            .append("    h1 { font-size: 22pt; margin: 0 0 2mm; color: var(--ink); }\n")
            .append("    .contact { color: var(--muted); font-size: 9pt; margin-bottom: 4mm; }\n")
            .append("    h2 { font-size: 11pt; color: var(--accent); border-bottom: 1px solid #ccc;\n")
            .append("         padding-bottom: 0.5mm; margin: 3mm 0 1.5mm; }\n")
            .append("    .entry { margin: 2mm 0; }\n")
            .append("    .entry-title { font-weight: 700; }\n")
            .append("    .entry-meta { color: var(--muted); font-size: 9pt; }\n")
            .append("    ul { margin: 0.5mm 0 0 4mm; padding: 0; }\n")
            .append("    li { margin: 0.3mm 0; text-align: var(--content-align); }\n")
            .append("    .skills-grid { display: grid; grid-template-columns: 28mm 1fr; gap: 1mm 3mm; }\n")
            .append("    .skills-cat { font-weight: 700; }\n")
            .append("    .keep-token { display: inline-block; white-space: nowrap; }\n")
            .append("  </style>\n")
            .append("</head>\n")
            .append("<body>\n");

        // Header
        html.append("  <h1>").append(name).append("</h1>\n")
            .append("  <div class=\"contact\">")
            .append(buildContactLine(data))
            .append("</div>\n");

        if (data.getTargetRole() != null) {
            html.append("  <p><strong>")
               .append(isZh ? "目标岗位：" : "Target: ")
               .append(safeHtml(data.getTargetRole())).append("</strong></p>\n");
        }

        // Summary
        if (data.getSummary() != null) {
            html.append("  <h2>").append(isZh ? "职业概述" : "Professional Summary").append("</h2>\n")
                .append("  <p>").append(safeHtml(data.getSummary())).append("</p>\n");
        }

        // Experience
        if (!data.getWorkExperienceList().isEmpty()) {
            html.append("  <h2>").append(isZh ? "工作经历" : "Experience").append("</h2>\n");
            for (Experience exp : data.getWorkExperienceList()) {
                html.append("  <div class=\"entry\">\n")
                    .append("    <div class=\"entry-title\">").append(safeHtml(exp.getRole())).append("</div>\n")
                    .append("    <div class=\"entry-meta\">")
                    .append(safeHtml(exp.getOrganization()))
                    .append("  ").append(formatDateRange(exp.getStartDate(), exp.getEndDate()))
                    .append("</div>\n");
                addBulletList(html, exp);
                html.append("  </div>\n");
            }
        }

        // Projects
        if (!data.getProjectList().isEmpty()) {
            html.append("  <h2>").append(isZh ? "项目经历" : "Projects").append("</h2>\n");
            for (Experience proj : data.getProjectList()) {
                html.append("  <div class=\"entry\">\n")
                    .append("    <div class=\"entry-title\">").append(safeHtml(proj.getRole())).append("</div>\n")
                    .append("    <div class=\"entry-meta\">")
                    .append(safeHtml(proj.getOrganization()))
                    .append("</div>\n");
                addBulletList(html, proj);
                html.append("  </div>\n");
            }
        }

        // Education
        if (!data.getEducationList().isEmpty()) {
            html.append("  <h2>").append(isZh ? "教育背景" : "Education").append("</h2>\n");
            for (Education edu : data.getEducationList()) {
                html.append("  <div class=\"entry\">\n")
                    .append("    <div class=\"entry-title\">")
                    .append("<span class=\"keep-token\">").append(safeHtml(edu.getInstitution())).append("</span>\n")
                    .append("    </div>\n")
                    .append("    <div class=\"entry-meta\">")
                    .append(safeHtml(edu.getDegree())).append(" · ").append(safeHtml(edu.getMajor()))
                    .append("  ").append(formatDateRange(edu.getStartDate(), edu.getEndDate()))
                    .append("</div>\n");
                if (edu.getGpa() != null) {
                    html.append("    <div>GPA: ").append(safeHtml(edu.getGpa())).append("</div>\n");
                }
                html.append("  </div>\n");
            }
        }

        // Skills
        if (!data.getSkillList().isEmpty()) {
            html.append("  <h2>").append(isZh ? "核心技能" : "Skills").append("</h2>\n")
                .append("  <div class=\"skills-grid\">\n");
            for (SkillCategory cat : data.getSkillList()) {
                if (cat.getCategory() == null) continue;
                html.append("    <div class=\"skills-cat\">").append(safeHtml(cat.getCategory())).append("</div>\n")
                    .append("    <div>").append(safeHtml(String.join(", ", cat.getSkills()))).append("</div>\n");
            }
            html.append("  </div>\n");
        }

        // Publications
        if (!data.getPublicationList().isEmpty()) {
            html.append("  <h2>").append(isZh ? "论文发表" : "Publications").append("</h2>\n")
                .append("  <ul>\n");
            for (Publication pub : data.getPublicationList()) {
                html.append("    <li>").append(formatPublicationHtml(pub)).append("</li>\n");
            }
            html.append("  </ul>\n");
        }

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    // ========= HTML 辅助方法 =========

    private String buildContactLine(ResumeData data) {
        List<String> parts = new ArrayList<>();
        if (data.getPhone() != null) parts.add(safeHtml(data.getPhone()));
        if (data.getEmail() != null) parts.add(safeHtml(data.getEmail()));
        if (data.getLocation() != null) parts.add(safeHtml(data.getLocation()));
        if (data.getGithub() != null) parts.add("GitHub: " + safeHtml(data.getGithub()));
        if (data.getLinkedin() != null) parts.add("LinkedIn: " + safeHtml(data.getLinkedin()));
        return String.join(" &nbsp;|&nbsp; ", parts);
    }

    private String experienceToHtml(Experience exp) {
        StringBuilder html = new StringBuilder();
        html.append("      <article class=\"entry\">\n")
            .append("        <div class=\"entry-head\">\n")
            .append("          <div>\n")
            .append("            <div class=\"entry-title\">").append(safeHtml(exp.getRole())).append("</div>\n")
            .append("            <div class=\"entry-meta\">").append(safeHtml(exp.getOrganization()))
            .append(exp.getLocation() != null ? " · " + safeHtml(exp.getLocation()) : "")
            .append("</div>\n")
            .append("          </div>\n")
            .append("          <div class=\"entry-date\">")
            .append(formatDateRange(exp.getStartDate(), exp.getEndDate()))
            .append("</div>\n")
            .append("        </div>\n");
        if (exp.getBulletPoints() != null && !exp.getBulletPoints().isEmpty()) {
            html.append("        <ul>\n");
            for (String b : exp.getBulletPoints()) {
                if (b != null && !b.isBlank()) {
                    html.append("          <li>").append(safeHtml(b)).append("</li>\n");
                }
            }
            html.append("        </ul>\n");
        } else if (exp.getResponsibilities() != null && !exp.getResponsibilities().isEmpty()) {
            html.append("        <ul>\n");
            for (String r : exp.getResponsibilities()) {
                if (r != null && !r.isBlank()) {
                    html.append("          <li>").append(safeHtml(r)).append("</li>\n");
                }
            }
            html.append("        </ul>\n");
        }
        html.append("      </article>\n");
        return html.toString();
    }

    private String educationToHtml(Education edu) {
        StringBuilder html = new StringBuilder();
        html.append("      <article class=\"entry\">\n")
            .append("        <div class=\"entry-head\">\n")
            .append("          <div>\n")
            .append("            <div class=\"entry-title\">")
            .append(safeHtml(edu.getInstitution())).append("</div>\n")
            .append("            <div class=\"entry-meta\">")
            .append(safeHtml(edu.getDegree())).append(" · ").append(safeHtml(edu.getMajor()))
            .append("</div>\n")
            .append("          </div>\n")
            .append("          <div class=\"entry-date\">")
            .append(formatDateRange(edu.getStartDate(), edu.getEndDate()))
            .append("</div>\n")
            .append("        </div>\n")
            .append("      </article>\n");
        return html.toString();
    }

    private void addBulletList(StringBuilder html, Experience exp) {
        List<String> bullets = exp.getBulletPoints();
        if (bullets != null && !bullets.isEmpty()) {
            html.append("    <ul>\n");
            for (String b : bullets) {
                if (b != null && !b.isBlank()) {
                    html.append("      <li>").append(safeHtml(b)).append("</li>\n");
                }
            }
            html.append("    </ul>\n");
        }
    }

    private String formatPublicationHtml(Publication pub) {
        StringBuilder sb = new StringBuilder();
        if (pub.getAuthors() != null) sb.append(safeHtml(pub.getAuthors())).append(". ");
        if (pub.getTitle() != null) sb.append("<em>").append(safeHtml(pub.getTitle())).append("</em>");
        if (pub.getJournal() != null) sb.append(". ").append(safeHtml(pub.getJournal()));
        if (pub.getYear() != null) sb.append(". ").append(safeHtml(pub.getYear()));
        return sb.toString();
    }

    private String formatDateRange(String start, String end) {
        if (start == null && end == null) return "";
        if (start == null) return end;
        if (end == null) return start + " - 至今";
        return start + " - " + end;
    }

    private String safeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
