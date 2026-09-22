# 墨序架构整理

本轮将产品显示名从文枢 WenShape 改为墨序 MoXu，采用单一 Java 业务后端。`com.wenshape` 包名、现有 API 路径、配置文件和前端存储键保留，避免仅为改名破坏用户数据和兼容性。项目目录暂保持原路径。

## 日常运行

React/Vite 前端 → Spring Boot 后端 → 现有模型供应商；末尾润色默认使用本机 Ollama 的 Qwen3 1.7B 微调模型，并在 Java 中校验和恢复数字。项目、分卷、章节、人物、世界观、事实、检索及上下文管理仍由 Java 负责。

Python 业务后端、PyInstaller 打包和旧 Python 启动流程已移出主项目。模型格式转换用到的一次性 Python 工具位于忽略提交的 `work/` 下，不参与应用启动或日常推理。

旧润色流程中的算法评分、多候选搜索和多轮后处理从运行链中移除。现在一次请求生成一个候选，本地校验并恢复能明确对应的数字，显示差异，作者确认后应用。数字缺失、新增、对应含糊或内容明显删减时保留原选区。模型实验结论见 [本地模型评估](LOCAL_POLISH_EVALUATION.md)。

## 恢复位置

本机归档目录：`/Users/imac/PycharmProjects/WenShape-legacy-20260919.kJFycY/`。

归档包括 Python 后端及辅助文件、Python 虚拟环境/缓存、四份旧 Java/Python 对齐文档，以及前端 `pages/` 下未被代码引用的国际化转换和调试产物。`java-rewrite/` 另存旧算法改写器、规则资源和其专属测试。它们已从主项目移出，可从归档逐项恢复。

改造前源码快照：`work/before-moxu-refactor.tar.gz`。快照保存了开始整理时已有的未提交源码修改；不会使用 Git reset 或覆盖回退这些修改。`data/` 和 `/Users/imac/bookzd/sft_qwen3_1p7b_merged` 原始权重保持原位置。

不要提交或分享本机归档和源码快照，其中可能包含本地配置。
