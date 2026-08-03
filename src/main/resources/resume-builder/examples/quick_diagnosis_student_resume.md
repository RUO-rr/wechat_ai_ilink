<!-- fictional-data: all people, organizations, roles, dates, and metrics are fictional -->

# Example: Quick Diagnosis for a Student Resume

> 示例目的：当用户只说“帮我看看简历有什么问题”“这份简历哪里需要优化”时，不应直接生成完整求职材料包。应先做快速诊断、指出关键信息缺口和修改优先级。  
> 本案例为虚构示范，不得复制其中事实。

## 1. User Input

用户上传一份中文学生简历，目标不明确，只说：

> 帮我看看这份简历有什么问题。

简历片段：

```markdown
教育背景  
A大学 生物技术 本科 2021-2025  
GPA：3.2/4.0

项目经历  
参加过蛋白质组学相关项目，负责实验和数据分析。  
熟悉 Python、R、质谱、细胞培养、Western blot、PCR、机器学习。  
获得良好结果。

校园经历  
学生会干事，组织过活动。

自我评价  
本人学习能力强，认真负责，沟通能力好，抗压能力强。
```

## 2. Internal Classification

```json
{
  "candidate_stage": "STUDENT",
  "candidate_modifiers": ["UNDERGRADUATE", "RESEARCH_ORIENTED"],
  "resume_scenario": "GENERAL_RESUME",
  "language_market": "CHINESE",
  "workflow_mode": "QUICK_DIAGNOSIS",
  "confidence": "MEDIUM",
  "missing_decision_fields": ["target_role", "confirmed_project_details", "verified_outputs"]
}
```

## 3. Expected Output

### 信息完整度诊断

目前简历已有基础教育背景、项目经历、技能和校园经历，但缺少以下核心信息：

- 求职目标不明确：无法判断应突出科研能力、实验能力、数据分析能力还是通用实习能力。
- 项目经历缺少具体任务、方法、个人贡献和结果。
- 技能堆叠过多，但缺少证据支撑。
- GPA 中等，不一定需要前置，除非目标岗位明确要求成绩。
- 自我评价偏泛化，缺少证据。

### 主要问题

| 问题 | 严重程度 | 说明 | 建议 |
|---|---:|---|---|
| 项目经历过于笼统 | 高 | “负责实验和数据分析”无法体现具体能力 | 拆成任务、方法、工具、结果 |
| 技能缺少证据 | 高 | Python/R/质谱/机器学习同时出现，但没有项目证明 | 仅保留有证据支持的技能 |
| 求职定位缺失 | 高 | 不知道用于实习、科研助理、企业岗位还是升学 | 先确认目标方向 |
| 自我评价泛化 | 中 | “认真负责、抗压能力强”缺少证据 | 改为能力 + 证据 + 岗位相关性 |
| 校园经历关联弱 | 低-中 | 若申请科研/技术岗位，学生会经历权重较低 | 压缩或放后 |

### 修改优先级

1. 先确认目标岗位或方向。
2. 重写项目经历，补充具体实验/数据分析流程。
3. 删除没有证据支撑的技能。
4. 根据目标岗位决定 GPA、校园经历和自我评价是否保留。
5. 如果要投递具体岗位，再做岗位要求匹配矩阵。

### 不应直接生成完整简历的原因

当前缺少目标岗位和项目证据。如果直接生成完整简历，容易出现以下问题：

- 技能与经历不匹配；
- 项目 bullet 空泛；
- 无法判断内容取舍；
- 可能误把弱相关校园经历放在过高位置。

## 4. Bad Output Example

```markdown
你很优秀，可以这样写：
本人具备扎实的生物技术背景，熟练掌握蛋白质组学、机器学习和多种实验技能，具有良好的团队合作能力。
```

### Why it fails

- 编造或放大“扎实”“熟练”；
- 没有证据；
- 没有诊断；
- 没有指出缺失信息；
- 没有帮助用户决定下一步。

## 5. Good Output Pattern

```markdown
你这份简历最大的问题不是排版，而是“证据颗粒度不足”。目前项目经历没有说明你具体做了什么、用了什么方法、产出了什么结果，因此很难支撑技能栏中的 Python、R、质谱和机器学习。

建议先补充这 4 个信息：
1. 项目名称和时间；
2. 你具体负责的实验或数据分析任务；
3. 使用的方法、软件、仪器或数据类型；
4. 最终输出，例如图表、报告、论文、poster、代码或结论。
```

## 6. Quality Notes

本场景只需要输出诊断，不需要生成：

- `job-analysis-report.md`
- `matching-matrix.md`
- `evidence-trace-table.md`
- PDF
- photo prompt
