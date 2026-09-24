# Java 分析任务与降本验证

更新：2026-09-23。本文描述当前 Java 实现。功能实现与真实模型效果验收分别记录，不用单元测试替代模型质量评估。

## 已实现

2026-09-24 新增生成前增量同步，流程、适用入口及算法边界见 [生成前连续性检查](CONTINUITY_SYNC.md)。

- 阶段用量账本：summary、canon、combined、volume、book_reduce，以及发生时的研究、重排请求。模型调用前预记账，完成后用供应商 usage 结算；失败、缺失 usage 和进程中断时保留保守预估，不伪装成零成本。
- 批量任务持久化：保存每章结果、耗时、跳过数量、失败情况、下一章及阶段/章节统计。预览候选不写入正典。
- 预算：累计 token、累计执行时间、每章每次执行的模型调用上限。达到限额后暂停，同一 job_id 可提高限额后继续。恢复时未显式指定的预算沿用原设置。
- 增量分析：正文、标题、语言、模型配置、提示词版本、抽取选项、卡片变化会改变指纹。成功候选可复用；失败不覆盖已有成功缓存。旧候选入库前重新核对正文和标题。
- 批量保存默认跳过写作研究链路和补摘要调用，只保留算法绑定、章节 digest，并将写作记忆标记为待重建。手动单章保存仍走原有研究流程。
- 可选合并抽取：一次请求得到摘要、事实、时间线、人物状态；严格 JSON、截断及证据核验。正文完整提交，不拿采样片段替代事实依据。
- 低置信回补：仅结构无效、截断或模型自报低置信结果可回退旧双调用路径；连接失败不触发另一套抽取。所有补请求仍计入预算。
- 范围梗概：确定性摘录覆盖章节首尾与中间位置，按有界分组多层 reduce，节点按内容缓存。结果独立保存并标记待核对，不写入正典；摘录可能漏信息，不等同于完整阅读质量。
- 改写重算：支持 from_chapter；未变化的局部抽取仍可复用。正文或分析变化后，当前及后续章节记忆保守失效，使用时再重建；不改动之前章节。没有宣称实现精确的跨章因果依赖图。
- 卷摘要按章节摘要输入哈希复用；模型配置变化也触发重算。失败保留原摘要。
- Writer 保留原有工作记忆优先、去掉重复原始卡片/事实的逻辑；有工作记忆时最多带最近 3 章摘要，否则最多 10 章。
- 前端批量窗口支持显式启动、预览/覆盖入库、预算、服务端取消、重开窗口恢复任务、选择候选入库和查看范围梗概。小批次不再自动覆盖入库。

## 配置与回退

Spring 配置前缀为 `moxu.analysis`，可使用对应大写下划线环境变量：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `incremental` | `true` | 复用成功候选；关闭可对照每章重算 |
| `combined` | `false` | 合并抽取仍需显式启用，等待真实语料验收 |
| `compact-prompt` | `true` | 合并抽取的简洁提示词 |
| `legacy-fallback` | `true` | 无效/低置信合并结果允许旧抽取回补 |
| `bulk-mode` | `true` | 批量保存优化；单章保存不自动采用此模式 |
| `bulk-disable-focus-binding` | `true` | 批量时省略写作研究，保留算法绑定 |
| `bulk-disable-semantic-rerank` | `true` | 批量时不做写作研究中的 LLM 重排 |
| `max-tokens-per-job` | `0` | 后端默认不限；前端初始预算为 200000 |
| `max-minutes-per-job` | `30` | 累计执行时间上限，不计任务暂停期间 |
| `max-calls-per-chapter` | `6` | 包括失败及回补；恢复后可再次尝试 |
| `max-output-tokens` | `4000` | 分析作用域内未明确指定的输出上限 |
| `reduce-batch-size` | `20` | 每个范围梗概 reduce 节点最多输入的条目数 |

两个批量禁用开关任一开启时，整段写作研究被省略；要完整回退研究流程，设 `bulk-mode=false`。分析与写作不是同一个操作：开始写作时仍按需生成完整上下文。旧双调用分析的并发额度仍由 `MOXU_ANALYSIS_CONCURRENCY` 控制。

## API

原路径不变：

```json
POST /projects/{projectId}/session/analyze-batch
{
  "chapters": ["V1C1", "V1C2"],
  "combined": true,
  "bulk_mode": true,
  "max_tokens": 200000,
  "max_minutes": 30,
  "max_calls_per_chapter": 6,
  "book_summary": false
}
```

`analyze-sync` 使用相同请求结构，但分析后覆盖入库。未指定 chapters 时读取项目章节；`from_chapter` 可限定改写后的范围。`force=true` 跳过候选缓存，用于基线对照。每次最多 2000 章。

返回 `job_id`、`status`、`results`、`metrics`、`usage`、`remaining_chapters`。status 为 `running/complete/partial/paused`。用同一 job_id 再次提交即可继续；章节范围、预览/入库模式与抽取模式不能在恢复时改变。预算允许提高。结果对象中 `skipped=true` 表示复用了候选，不代表忽略该章。

- `GET /projects/{projectId}/session/analysis-jobs/{jobId}`：恢复进度与候选。
- `POST /projects/{projectId}/session/analysis-jobs/{jobId}/cancel`：停止后续模型调用。
- `GET /projects/{projectId}/session/analysis-book-summary`：最近生成的范围梗概；任务返回值的 synopsis 保留本次范围结果。

取消、时间预算在调用边界生效，不能保证远端已收到的请求立即停止或不计费。同一作品同时只允许一个批量分析/入库任务；此互斥只在单个 Java 进程内生效，不支持多个后端共享数据目录同时写入。

## 本地数据

`data/<project>/analysis/` 下：

- `manifest.json`：成功候选的指纹、来源、摘要和事实哈希。
- `results/<chapter>.json`：候选与源哈希；不等同于已归档。
- `jobs/<job_id>.json`：持久化任务、进度与候选。
- `cost_ledger.ndjson`：每次调用的 started 预记账及 complete/failed 结算。`budget_tokens` 是账务增量，结算时可为负数；求和才是记账总量。`accounted_tokens` 是该次调用最终记账数，`reported_tokens` 才是供应商报告数。
- `reductions/`、`book_summary.json`：范围梗概中间缓存与结果。

账本不保存请求正文、响应正文和密钥；候选文件含作品材料。这些运行数据已加入 gitignore。缺失 usage 使用输入 UTF-8 字节数与输出限额做保守准入估算，不是 tokenizer 实测，也不是供应商账单的硬上限。进程中断时保留未结算预约，避免恢复后漏算已发出的请求。

## 验证

```sh
mvn -f backend-java/pom.xml test
npm --prefix web run test
npm --prefix web run lint
npm --prefix web run build
node --test scripts/compare-analysis-jobs.test.mjs
```

测试覆盖 10/100 章受控语料的缓存复用及单章改动、指纹变化、失败重试、预算恢复、旧候选拒绝、API 参数验证、合并抽取与证据、取消及前端重开恢复。受控数据不使用用户小说，也不证明真实模型质量。

2026-09-23 本地验证：后端全量 341 项测试通过并成功打包；前端 54 项测试、ESLint、TypeScript 与生产构建通过。Windows 百章快照替换测试曾暴露短暂 `AccessDeniedException`，分析存储现采用有限退避重试，不删除原文件。批量保存预先核对整批候选；这不是多文件事务，执行期间的外部修改或 I/O 失败仍可能导致部分写入。

预览服务启动命令被当前执行策略拒绝，本轮未完成桌面/窄屏真实浏览器检查，不将组件测试视为视觉验收。

真实验收流程：

1. 在独立作品副本上，用相同模型、正文、标题运行旧双调用基线及启用合并抽取的候选任务；两次都用 `force=true`，防止把缓存收益冒充抽取收益。
2. 保留两份 jobs JSON；分别人工抽样核对事实，记录 `reviewed_facts` 与 `correct_facts`。用同一抽样规则，不能只挑成功样本。
3. 创建质量记录 `{"baseline":{"reviewed_facts":100,"correct_facts":95},"candidate":{"reviewed_facts":100,"correct_facts":94}}`，数字必须来自实际核对。
4. 运行 `node scripts/compare-analysis-jobs.mjs baseline.json candidate.json quality.json`。工具核对来源/模型一致性，比较 token、P95、成功率和质量；缓存样本、不足 100 章、缺少真实 usage 或人工质量记录时不允许通过。

**仍待真实模型验收**：100 章 token 降低至少 50%、P95 降低至少 40%、事实准确率下降不超过 5 个百分点。当前没有使用真实付费模型替用户执行这项评估，不能将这些目标写成已实现的效果。
