# 墨序 MoXu

面向长篇小说创作的本地应用：角色与世界设定、章节大纲、上下文检索、流式写作、修订和归档。

[English](README.en.md) · [启动与配置](GETTING_STARTED.md) · [本地模型实测](docs/LOCAL_POLISH_EVALUATION.md)

## 架构

- Java 21 / Spring Boot 是唯一业务后端，默认监听 `127.0.0.1:8080`。
- React / Vite 前端（`web/`）默认监听 `127.0.0.1:3000`，代理 `/api` 和 `/ws` 到 Java。
- 数据保存在仓库根目录 `data/`，沿用现有项目、模型 profiles 和智能体分配。
- 独立本机 Ollama 提供默认本地润色；日常运行不需要 Python，应用启动器不会启动或停止 Ollama。

默认末尾润色使用已导入 Ollama 的本地模型 `moxu-polish:qwen3-1.7b`，并启用数字保护：根据上下文对齐，自动恢复被改动的原数字及单位；遇到数字遗漏、新增或对齐歧义时，保留原选区并返回警告。结果仍需在前端 diff 中确认后应用，不自动覆盖正文。通用 LLM 为用户手动选择的备用方式，不自动回退。

初测曾出现数字改变和情节删减；数字保护不等于完整的语义校验，仍需检查情节和事实。该模型用于生成式润色，不是 AI 文本检测器。

## 启动

安装 JDK 21（推荐）、Maven 3.9+、Node.js 18+ / npm。macOS / Linux 还需要 Bash 和 curl。

macOS / Linux，在仓库根目录运行：

```bash
bash start.sh
```

Windows：

```bat
start.bat
```

脚本构建 Java、必要时安装前端依赖，等待 `/health` 就绪后启动前端。构建产物和日志放在每次独立的 `.moxu-runtime/run.*/`，不覆盖现有 `backend-java/target/`。端口被占用时退出并保留已有服务；按 Ctrl+C 只停止本次启动的服务。

打开 [墨序](http://127.0.0.1:3000)。健康检查为 [GET /health](http://127.0.0.1:8080/health)，不包含模型连通性检测；当前没有 Swagger `/docs`。

启动器读取已导出的环境变量，**不会自动加载根目录 `.env`**；变量模板见 [`.env.example`](.env.example)，加载方法和手动启动见 [GETTING_STARTED](GETTING_STARTED.md)。

## 数据与兼容

保留完整 `data/`，其中 `llm_profiles.json` 和 `agent_assignments.json` 由 Java 继续使用。模型接入在应用的「设置 → 模型档案/分工」中管理。数据与本地配置可能包含密钥，不要提交到 Git。

Java 包名 `com.moxu`，配置键 `moxu.*`；Maven artifactId 为 `moxu-backend`。旧 Python 配置、PyInstaller 打包和启动方式不属于当前运行链路。

## 前端

`web/` 是独立的前端实现：React 19 + TypeScript strict + Vite + Tailwind 4 + TanStack Query。章节树与事实百科做了虚拟化，编辑器基于 CodeMirror 6，SSE/WebSocket 更新按动画帧合批。

## 验证与发布

```bash
mvn -f backend-java/pom.xml test
npm --prefix web run lint
npm --prefix web run typecheck
npm --prefix web run test
npm --prefix web run build
```

一键脚本用于本地开发。生产部署需单独托管 `web/dist/` 构建结果，并将 `/api`、`/ws` 转发到 Java；Java JAR 当前不内置前端页面。

## 许可

Copyright (c) 2026 AetherRadar。

本项目以 [MIT License](LICENSE) 发布。
