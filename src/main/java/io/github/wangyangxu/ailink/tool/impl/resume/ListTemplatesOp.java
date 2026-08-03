package io.github.wangyangxu.ailink.tool.impl.resume;

import io.github.wangyangxu.ailink.service.IintService;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 列出可用简历模板操作。
 * <p>
 * 展示 ResumeTool 支持的模板类型及其适用场景，
 * 帮助 LLM 和用户选择合适的模板。
 */
@Component
public class   ListTemplatesOp implements ResumeOperation {

    private static final Logger log = LoggerFactory.getLogger(ListTemplatesOp.class);

    @Override
    public String name() {
        return "list_templates";
    }

    @Override
    public String description() {
        return "list_templates=列出可用的简历模板类型及其适用场景说明";
    }

    @Override
    public Map<String, Object> parameters() {
        // 此操作无需额外参数
        return Map.of();
    }

    @Override
    public String execute(JsonNode args, String outputDir, IintService iintService) throws Exception {
        String templates = """
            {
              "templates": [
                {
                  "name": "default",
                  "label_zh": "标准职场简历",
                  "label_en": "Corporate Resume",
                  "description_zh": "适合有工作经验的求职者，按 职业概述→工作经历→项目经历→教育背景→核心技能 顺序排版",
                  "description_en": "For experienced professionals. Order: Summary → Experience → Projects → Education → Skills",
                  "suitable_for": ["experienced", "career_changer"],
                  "default_pages": "1-2页"
                },
                {
                  "name": "student",
                  "label_zh": "学生简历",
                  "label_en": "Student Resume",
                  "description_zh": "适合在校生或应届毕业生，按 教育背景→项目经历→实习经历→技能→获奖 顺序排版",
                  "description_en": "For students and fresh graduates. Order: Education → Projects → Internships → Skills → Awards",
                  "suitable_for": ["student", "recent_graduate"],
                  "default_pages": "1页"
                },
                {
                  "name": "research",
                  "label_zh": "科研/学术简历",
                  "label_en": "Research CV",
                  "description_zh": "适合科研人员或学术岗位申请，突出论文、专利和研究经历，可多页",
                  "description_en": "For researchers and academic positions. Emphasizes publications, patents, and research experience.",
                  "suitable_for": ["researcher"],
                  "default_pages": "2页+"
                },
                {
                  "name": "modern",
                  "label_zh": "现代简洁简历",
                  "label_en": "Modern Resume",
                  "description_zh": "简洁现代风格，紧凑排版，适合设计、互联网等行业",
                  "description_en": "Clean modern style with compact layout. Suitable for design, tech, and creative industries.",
                  "suitable_for": ["experienced", "student"],
                  "default_pages": "1页"
                }
              ],
              "output_formats": [
                {
                  "format": "generate_resume",
                  "label": "Word (.docx)",
                  "description": "生成 Microsoft Word 格式简历文档，适合打印和提交"
                },
                {
                  "format": "generate_html",
                  "label": "HTML",
                  "description": "生成 HTML 格式简历，支持 ats（ATS友好）和 editable（可编辑）两种风格"
                },
                {
                  "format": "generate_markdown",
                  "label": "Markdown (.md)",
                  "description": "生成 Markdown 格式简历，适合快速预览和进一步编辑"
                }
              ],
              "reference_note": "详细的简历编写规则、模板和示例位于 resources/resume-builder/ 目录下，包括 resume_writing_rules.md、output_quality_checklist.md 等参考文档。"
            }
            """;

        log.info("列出简历模板");
        return templates;
    }
}
