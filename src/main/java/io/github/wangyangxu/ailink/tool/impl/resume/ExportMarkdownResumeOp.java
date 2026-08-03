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
 * 生成 Markdown 格式简历操作。
 * <p>
 * 参考 resume-builder-cn 的 assets/templates/ 下的 Markdown 模板格式：
 * resume_student_zh.md / resume_student_en.md / resume_experienced_zh.md / resume_experienced_en.md
 */
@Component
public class ExportMarkdownResumeOp implements ResumeOperation {

    private static final Logger log = LoggerFactory.getLogger(ExportMarkdownResumeOp.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "generate_markdown";
    }

    @Override
    public String description() {
        return "generate_markdown=生成Markdown格式简历，支持zh（中文）和en（英文），适合快速预览和进一步编辑";
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
        String fileName = safeName + "_简历_" + System.currentTimeMillis() + ".md";
        String filePath = resumeDir.resolve(fileName).toString();

        String markdown = generateMarkdown(data);

        Files.writeString(Paths.get(filePath), markdown, java.nio.charset.StandardCharsets.UTF_8);

        log.info("Markdown简历生成成功: {}", filePath);

        return "{\"success\": true, \"file_path\": \"" + filePath
                + "\", \"file_name\": \"" + fileName + "\"}";
    }

    private String generateMarkdown(ResumeData data) {
        boolean isZh = "zh".equals(data.getLanguage());
        StringBuilder md = new StringBuilder();

        // 标题
        md.append("# ").append(nullToEmpty(data.getName())).append("\n\n");

        // 联系方式行
        List<String> contactParts = new ArrayList<>();
        if (data.getPhone() != null) contactParts.add(data.getPhone());
        if (data.getEmail() != null) contactParts.add(data.getEmail());
        if (data.getLocation() != null) contactParts.add(data.getLocation());
        if (data.getGithub() != null) contactParts.add("[GitHub](" + data.getGithub() + ")");
        if (data.getLinkedin() != null) contactParts.add("[LinkedIn](" + data.getLinkedin() + ")");
        if (!contactParts.isEmpty()) {
            md.append(String.join(" | ", contactParts)).append("\n\n");
        }

        // 目标岗位
        if (data.getTargetRole() != null && !data.getTargetRole().isBlank()) {
            md.append("**").append(isZh ? "目标岗位" : "Target Role")
              .append("：**").append(data.getTargetRole()).append("\n\n");
        }

        // 职业概述
        if (data.getSummary() != null && !data.getSummary().isBlank()) {
            md.append("## ").append(isZh ? "职业概述" : "Professional Summary").append("\n\n");
            md.append(data.getSummary()).append("\n\n");
        }

        // 工作经历
        if (!data.getWorkExperienceList().isEmpty()) {
            md.append("## ").append(isZh ? "工作经历" : "Experience").append("\n\n");
            for (Experience exp : data.getWorkExperienceList()) {
                md.append("### ").append(nullToEmpty(exp.getRole())).append("\n\n");
                md.append("**").append(nullToEmpty(exp.getOrganization())).append("**");
                if (exp.getLocation() != null) md.append(" · ").append(exp.getLocation());
                String dateRange = formatDateRange(exp.getStartDate(), exp.getEndDate());
                if (!dateRange.isEmpty()) md.append("  \n*").append(dateRange).append("*");
                md.append("\n\n");

                if (exp.getBulletPoints() != null) {
                    for (String b : exp.getBulletPoints()) {
                        if (b != null && !b.isBlank()) {
                            md.append("- ").append(b).append("\n");
                        }
                    }
                } else if (exp.getResponsibilities() != null) {
                    for (String r : exp.getResponsibilities()) {
                        if (r != null && !r.isBlank()) {
                            md.append("- ").append(r).append("\n");
                        }
                    }
                }
                md.append("\n");
            }
        }

        // 项目经历
        if (!data.getProjectList().isEmpty()) {
            md.append("## ").append(isZh ? "项目经历" : "Projects").append("\n\n");
            for (Experience proj : data.getProjectList()) {
                md.append("### ").append(nullToEmpty(proj.getRole())).append("\n\n");
                if (proj.getOrganization() != null) {
                    md.append("**").append(proj.getOrganization()).append("**\n\n");
                }
                if (proj.getBulletPoints() != null) {
                    for (String b : proj.getBulletPoints()) {
                        if (b != null && !b.isBlank()) md.append("- ").append(b).append("\n");
                    }
                }
                if (proj.getTools() != null) {
                    md.append("\n*" + (isZh ? "工具" : "Tools") + "：" + proj.getTools() + "*\n");
                }
                md.append("\n");
            }
        }

        // 教育背景
        if (!data.getEducationList().isEmpty()) {
            md.append("## ").append(isZh ? "教育背景" : "Education").append("\n\n");
            for (Education edu : data.getEducationList()) {
                md.append("### ").append(nullToEmpty(edu.getInstitution())).append("\n\n");
                md.append(nullToEmpty(edu.getDegree())).append(" · ").append(nullToEmpty(edu.getMajor()));
                String dateRange = formatDateRange(edu.getStartDate(), edu.getEndDate());
                if (!dateRange.isEmpty()) md.append("  \n*").append(dateRange).append("*");
                md.append("\n\n");
                if (edu.getGpa() != null) md.append("GPA: ").append(edu.getGpa()).append("\n\n");
                if (edu.getHonors() != null) md.append(edu.getHonors()).append("\n\n");
            }
        }

        // 技能
        if (!data.getSkillList().isEmpty()) {
            md.append("## ").append(isZh ? "核心技能" : "Skills").append("\n\n");
            for (SkillCategory cat : data.getSkillList()) {
                if (cat.getCategory() == null) continue;
                md.append("- **" + cat.getCategory() + "：**")
                  .append(String.join("、", cat.getSkills())).append("\n");
            }
            md.append("\n");
        }

        // 论文发表
        if (!data.getPublicationList().isEmpty()) {
            md.append("## ").append(isZh ? "论文发表" : "Publications").append("\n\n");
            for (Publication pub : data.getPublicationList()) {
                md.append("- ");
                if (pub.getAuthors() != null) md.append(pub.getAuthors()).append(". ");
                md.append("《").append(nullToEmpty(pub.getTitle())).append("》");
                if (pub.getJournal() != null) md.append(". ").append(pub.getJournal());
                if (pub.getYear() != null) md.append(". ").append(pub.getYear());
                md.append("\n");
            }
            md.append("\n");
        }

        // 获奖
        if (!data.getAwardList().isEmpty()) {
            md.append("## ").append(isZh ? "获奖与荣誉" : "Awards & Honors").append("\n\n");
            for (Award award : data.getAwardList()) {
                md.append("- ").append(nullToEmpty(award.getTitle()));
                if (award.getOrganization() != null) md.append(" — ").append(award.getOrganization());
                if (award.getDate() != null) md.append(" (").append(award.getDate()).append(")");
                md.append("\n");
            }
            md.append("\n");
        }

        return md.toString();
    }

    private String formatDateRange(String start, String end) {
        if (start == null && end == null) return "";
        if (start == null) return end;
        if (end == null) return start + " - 至今";
        return start + " - " + end;
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
