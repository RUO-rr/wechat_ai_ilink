# resume-builder

简历生成方法论、模板与案例资源，作为 `ResumeTool`（Function Calling 工具）的提示词资产使用。

## 来源

本目录内容改编自 OpenAI 开源项目 [openai-resume-builder](https://github.com/openai/openai-resume-builder)（resume-builder-cn skill），
保留其方法论、模板与案例，并根据本项目做了工程化适配。

## 本项目中的适配

- 通过 [ResumeTool](../../../java/io/github/wangyangxu/ailink/tool/impl/resume/ResumeTool.java) 接入 Function Calling 对话流程
- 支持 `generate_resume` / `generate_html` / `generate_markdown` / `list_templates` 四个操作
- 输出格式：Word（.docx）/ HTML / Markdown
- 原始 markdown 资源作为系统提示词与模板库随应用打包

## License

本目录沿用原项目 MIT License。完整版权声明以原仓库
[LICENSE](https://github.com/openai/openai-resume-builder/blob/main/LICENSE) 为准。

The MIT License (MIT)

Copyright (c) OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
