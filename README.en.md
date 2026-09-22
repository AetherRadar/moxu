# MoXu 墨序

A local application for long-form fiction: character and world cards, chapter outlines, context retrieval, streaming writing, revision, and archiving.

[中文](README.md) · [Startup and configuration guide (Chinese)](GETTING_STARTED.md)

## Architecture

- Java 21 / Spring Boot is the only business backend, on `127.0.0.1:8080` by default.
- The React / Vite frontend (`web/`) runs on `127.0.0.1:3000`, forwarding `/api` and `/ws` to Java.
- Existing projects, model profiles, and agent assignments remain in the repository's root `data/` directory.
- A separate local Ollama service provides the default local polishing model. Daily operation requires no Python; the launchers neither start nor stop Ollama.

The default final polishing step uses `moxu-polish:qwen3-1.7b`, already imported into Ollama, with numeric protection. Context alignment restores changed numbers and units to their original forms. Missing or added numbers, or ambiguous alignment, cause the original selection to be retained with a warning. Results still require confirmation in the frontend diff before application; the manuscript is not automatically overwritten. A general-purpose LLM is a manually selectable alternative, with no automatic fallback.

Initial evaluation found changed numbers and omitted plot details. Numeric protection is not a complete semantic check: review plot and factual consistency too. This is a generative polishing model, not an AI-text detector.

## Start

Install JDK 21 (recommended), Maven 3.9+, and Node.js 18+ with npm. macOS / Linux also needs Bash and curl.

From the repository root on macOS / Linux:

```bash
bash start.sh
```

On Windows:

```bat
start.bat
```

The launcher builds Java, installs frontend dependencies if missing, checks `/health`, then starts Vite. Each launch keeps its build and logs in a separate `.moxu-runtime/run.*/` directory without overwriting `backend-java/target/`. Occupied ports cause startup to stop without touching existing services. Ctrl+C stops only services created by that launch.

Open [MoXu](http://127.0.0.1:3000). [GET /health](http://127.0.0.1:8080/health) checks application readiness, not model connectivity. Swagger `/docs` is not provided.

Launchers read exported environment variables and **do not automatically load a root `.env` file**. See [`.env.example`](.env.example). Override `MOXU_BACKEND_PORT`, `MOXU_FRONTEND_PORT`, or `MOXU_DATA_DIR` as needed. Relative data paths resolve against the repository root. Vite still reads its own `web/.env*`; the launchers explicitly set backend HTTP/WS URLs and ports to keep them aligned.

## Data and compatibility

Keep the entire `data/` directory, including `llm_profiles.json` and `agent_assignments.json`. Manage model profiles in-app under Settings → Profiles / Assignments. Data and local configuration may contain credentials and must not be committed.

Java packages are `com.moxu`, and configuration keys are `moxu.*`. The Maven artifactId is `moxu-backend`. Legacy Python configuration and PyInstaller packaging are outside the current runtime.

## Frontend

`web/` is an independent frontend implementation: React 19 + strict TypeScript + Vite + Tailwind 4 + TanStack Query. The chapter tree and fact encyclopedia are virtualized, the editor is CodeMirror 6, and SSE/WebSocket updates are batched per animation frame.

## Validation and deployment

```bash
mvn -f backend-java/pom.xml test
npm --prefix web run lint
npm --prefix web run typecheck
npm --prefix web run test
npm --prefix web run build
```

These launchers are for local development. Production deployments must serve `web/dist/` separately and proxy `/api` and WebSocket `/ws` to Java. The Java JAR does not currently bundle the frontend.

## License

Copyright (c) 2026 AetherRadar.

Released under the [MIT License](LICENSE).
