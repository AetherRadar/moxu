# 墨序 MoXu 启动与配置

## 1. 环境

安装 JDK 21（推荐）、Maven 3.9+、Node.js 18+ / npm。macOS / Linux 使用 Bash 和 curl；Windows 使用 Windows PowerShell 5.1+。

```bash
java -version
mvn -v
node --version
npm --version
```

Maven 和启动器应使用同一 JDK：设置 `JAVA_HOME`，并把其 `bin` 加入 PATH。当前构建目标为 Java 21。

应用由 Java 单一业务后端和 React 前端组成，日常运行无需 Python。默认本地润色使用独立 Ollama 服务，启动器不会管理其进程。

## 2. 一键启动与退出

在仓库根目录执行：

```bash
# macOS / Linux
bash start.sh
```

```bat
REM Windows
start.bat
```

Windows 也可执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1`；执行策略覆盖只用于此次 PowerShell 进程。

脚本会依次检查端口、构建 Java、在缺少 Vite 时运行 `npm ci`、启动 Java、检查健康状态，再启动前端。首次需要联网下载 Maven 和 npm 依赖；一键构建跳过测试执行，正式验证请单独运行测试。

默认地址：

| 服务 | 地址 |
| --- | --- |
| 前端 | http://127.0.0.1:3000 |
| Java API | http://127.0.0.1:8080 |
| Java 健康检查 | http://127.0.0.1:8080/health |

健康响应为 `{"status":"ok","application":"moxu-backend"}`（字段顺序可能不同），不包含模型、Ollama 或外网连通性检查。Java 没有 Swagger `/docs`。

保持启动终端打开；按 Ctrl+C 会停止本次启动的前后端。端口被占用时直接报错，不结束已有进程，也不自动改端口。启动失败会清理本次已启动的服务。不要用全局进程清理命令处理端口冲突。

每次构建和日志位于独立的 `.moxu-runtime/run.*/`，不会覆盖现有 `backend-java/target/`。日志和打包配置可能含私人信息，请勿公开上传。

## 3. 环境变量与 .env

**根目录 `.env` 不会被 start.sh、start.ps1 或 Spring Boot 自动加载。** `.env.example` 只是变量模板；复制成 `.env` 本身不会生效。

macOS / Linux 可直接传入：

```bash
MOXU_BACKEND_PORT=8081 MOXU_FRONTEND_PORT=3001 bash start.sh
```

也可以在仓库根目录手动加载自己信任的配置文件：

```bash
cp .env.example .env
# 按需编辑 .env 后，在同一终端执行：
set -a
. ./.env
set +a
bash start.sh
```

Windows PowerShell 直接设置环境变量：

```powershell
$env:MOXU_BACKEND_PORT = '8081'
$env:MOXU_FRONTEND_PORT = '3001'
.\start.ps1
```

| 变量 | 默认值 / 用途 |
| --- | --- |
| `MOXU_BACKEND_PORT` | `8080` |
| `MOXU_FRONTEND_PORT` | `3000` |
| `MOXU_DATA_DIR` | 仓库根目录下的 `data/`；相对路径也相对于仓库根目录 |
| `JAVA_HOME` | 可选 JDK 路径，需与 Maven 的 JDK 一致 |
| `WENSHAPE_BACKEND_PORT` | 未设置 `MOXU_BACKEND_PORT` 时的兼容回退 |
| `WENSHAPE_FRONTEND_PORT` | 未设置 `MOXU_FRONTEND_PORT` 时的兼容回退 |
| `WENSHAPE_DATA_DIR` | 未设置 `MOXU_DATA_DIR` 时的兼容回退，也可用于直接启动 Java |
| `MOXU_POLISH_BASE_URL` | 本地 Ollama 地址，默认 `http://127.0.0.1:11434` |
| `MOXU_POLISH_MODEL` | 默认本地润色模型 `moxu-polish:qwen3-1.7b` |
| `MOXU_POLISH_TIMEOUT_SECONDS` | 本地润色请求超时，默认 `120` 秒 |

Vite 仍按自身规则加载 `web/.env*`。一键启动器显式设置前端端口、后端 HTTP 地址及 WebSocket 地址，覆盖旧的端口代理配置。直接运行 `npm run dev` 时，需要自行保证代理端口与 Java 一致。

旧 Python `.env`、`config.yaml` 不会被 Java 自动导入。

## 4. 数据与模型

启动脚本将数据目录转换为绝对路径，通过 `--wenshape.data-dir` 显式传给 Java；默认就是仓库根目录 `data/`，保留已有项目格式和内容。

请保留整个 `data/`，尤其是项目正文、卡片、设定、章节摘要、记忆包以及：

- `data/llm_profiles.json`：模型 profiles，可能含密钥。
- `data/agent_assignments.json`：智能体使用的 profile 分配。

在应用的智能体配置中管理模型。现有 `application.yml` 中的 LLM 配置继续生效，不要将真实密钥复制到示例文件或文档。Java 包名 `com.wenshape` 与 `wenshape.*` 配置键保持兼容。

默认末尾润色使用已成功导入 Ollama 的本地模型 `moxu-polish:qwen3-1.7b`，并启用数字保护。系统根据上下文对齐自动恢复原数字及单位；遇到数字遗漏、新增或对齐歧义时，保留原选区并返回警告。结果仍需在前端 diff 中确认后应用，不自动覆盖正文。通用 LLM 单次调用保留为手动可选备用，不会自动回退。

使用本地润色前，需单独运行 Ollama 并确保上述模型可用；Ollama 未运行不影响 Java / React 本身启动。初测曾出现数字改变和情节删减，数字保护不能保证情节与语义完全不变，仍须人工核对差异。该模型用于润色，不是 AI 文本检测器。

## 5. 手动运行与发布

以下 macOS / Linux 命令从仓库根目录执行：

```bash
mvn -f backend-java/pom.xml package
java -jar backend-java/target/moxu-backend.jar \
  --server.address=127.0.0.1 --server.port=8080 \
  --wenshape.data-dir="$PWD/data"
```

另一个终端启动前端：

```bash
cd /path/to/ai-writer/web
npm ci
VITE_BACKEND_URL=http://127.0.0.1:8080 \
VITE_BACKEND_WS_URL=ws://127.0.0.1:8080 \
npm run dev -- --host 127.0.0.1 --port 3000 --strictPort
```

IDE 入口为 `com.wenshape.WenShapeApplication`；工作目录设为仓库根目录，并显式配置数据绝对路径。不要用旧 Python 入口或 PyInstaller 打包。

生产部署执行 `npm --prefix web run build`，单独托管生成的 `web/dist/`，配置 SPA 路由回退，并把 `/api` 和 WebSocket `/ws` 转发到 Java。Java 支持 `/api` 前缀，代理可保留该前缀。当前 JAR 不包含前端静态页面，`vite preview` 也不等于完整的生产部署。

## 6. 验证与排错

```bash
mvn -f backend-java/pom.xml test
npm --prefix web run build
```

- 端口占用：保留已有服务，修改上述端口变量后重新启动。
- Java 构建失败：先检查 `mvn -v` 的 JDK，再查看此次 `build.log`。
- 健康检查失败：查看此次 `backend.log`；Windows 另有 `backend-error.log`。
- 前端连接失败：确认 HTTP 和 WebSocket 都指向同一个 Java 端口。
- 项目列表为空：先核对启动时显示的数据目录，不要覆盖已有 `data/`。

许可见 [LICENSE](LICENSE)。
