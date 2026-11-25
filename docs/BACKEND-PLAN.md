# 后端技术方案（Java / AgentScope）

> 目标：后端**不再是 Node 重写版**，而是与 `seeky-ai-runtime` 同构的 Java 服务，
> 把 `seeky-app-server` 的业务分层并进同一个进程。提示词与技能放本地文件，启动载入内存。
>
> **已定稿**：端口走 6xxx（后端 `6100`、mem0 `6101`）；记忆只用 mem0，PostgreSQL 只存业务数据；
> 前端代理跟着改。剩下的实现顺序见 §12。

---

## 1. 判定标准（做到什么算"完全参考"）

| 维度 | 判定 |
| --- | --- |
| 技术栈 | Spring Boot 4.0.4 + WebFlux + AgentScope 2.0.3 + spring-ai-alibaba-graph-core 2.0.0-M1.1，JDK 21 |
| 编排 | Spring AI Alibaba `StateGraph`，节点实现同一套 `GraphNode` 契约，不自己写循环 |
| 技能 | `AgentSkill` → `SkillCatalog`（content/description/priority + 前缀隔离），节点召回的技能注入 prompt |
| 提示词 | `PromptKeys` 常量 + `PromptCatalog`，**本地 classpath 文件**，启动全量载入内存 |
| 业务接口 | `Result<T>` 信封 + `ErrorCode` + `BusinessException` + `@RestControllerAdvice`，Controller/Service/Repository 三层 |
| 流式 | `Flux<ServerSentEvent<String>>`，帧类型与 ai-runtime 一致 |

---

## 2. 工程形态

一个主进程 + 一个记忆进程，都在 6000 段（8080 是本机 Nacos，不碰）：

| 进程 | 端口 | 是什么 |
| --- | --- | --- |
| `server/` | **6100** | Java 主服务：对话图、业务接口、SSE、托管前端构建产物 |
| `memory-service/` | **6101** | 本地 mem0，独立进程，只有记忆的读写检索 |

```
ai-soulmate/
├── front/                    React（接口仍是相对路径 /api/v1/*，走 vite 代理 → 6100）
├── server/                   ← Java 主服务（原 Node 版在 legacy/node-server）
│   ├── pom.xml
│   ├── .env                  真实配置（含 Key，gitignore）
│   ├── .env.example          占位符模板
│   └── src/main/
│       ├── java/com/seeky/airuntime/     ← 包名沿用，便于对照
│       └── resources/
│           ├── application.yml
│           ├── prompts/                  *.md，文件名即 key
│           └── skills/chat/output/*/SKILL.md
└── memory-service/           ← 本地 mem0（Node + mem0ai/oss），数据落 data/mem0/
```

**依赖裁剪**：去掉 Nacos config/discovery、Redis、PostgreSQL、R2DBC、Flyway、Jaeger/Langfuse；
Java 侧存储只留 `postgresql` 驱动 + `HikariCP`。业务表 `sessions` / `messages` 落本机 PG 16
（库名 `ai-soulmate`），Hikari 池 4 条连接，不引 JPA / Flyway。

**为什么记忆要单独一个进程**：ai-runtime 里 mem0 本来就是独立服务（Nacos 发现后 HTTP 调），
不是嵌在业务进程里的。Java 生态没有 mem0 SDK，所以这里把 mem0 原样做成一个本地服务，
Java 通过 `Mem0Client` 走 HTTP —— 形态与 ai-runtime 一致，只是发现方式从 Nacos 换成固定地址。

---

## 3. 包结构（与 ai-runtime 逐目录对应）

```
com.seeky.airuntime
├── AiRuntimeApplication
├── common/          Result · ErrorCode · BusinessException · GlobalExceptionHandler
├── config/          ModelProperties · CompanionProperties · ModelConfig · WebConfig
├── controller/      Chat · Memory · Settings · Speech · Media · Prompt · Health
├── dto/             请求/响应 record
├── prompt/          PromptKeys · PromptCatalog（本地加载，替代 registry/PromptCatalog）
├── skill/           SkillKeys · SkillCatalog（复制）· LocalSkillLoader（替代 AiRegistryRefresher）
├── store/           PgStore · ChatStore（只有会话与消息）
└── service/
    ├── chat/        SoulmateBotService（= SeekyMainBotService）· HistoryContextBuilder
    │   └── nodes/   GraphNode 契约 + 各节点 + common（ENodeName/ENodeType/StateKeys…）
    ├── memory/      Mem0Client（HTTP）· MemoryService（读/写/合并）· MemoryExtractor（LLM 抽记忆）
    ├── speech/      AsrService · TtsService
    ├── media/       ImageService · VideoService
    └── settings/    SettingsService（.env 回写 + 内存视图）
```

---

## 4. 主对话图（照 SeekyMainBotService 的建图方式）

ai-runtime 的图：`Rewrite → Intent → FanOut → {Retrieval, Memory, Persona} → Detail → MedicalRecordSave → Output`。

陪伴场景去掉 RAG/医疗，**保留"改写 → 意图 → 三路并行 → 输出"的骨架**：

```
START
  → rewriteNode      把「那它呢」补成自包含的一句话（失败回退原句，不阻断）
  → intentNode       归类：chat / emotion / ask_media / ask_memory / smalltalk
  → fanOutNode       分发（无逻辑，只连边）
      ├→ memoryNode   读长期记忆（mem0 语义 + 本地事实表）
      ├→ personaNode  读画像（名字/城市/职业/偏好，来自 facts 的 profile 类）
      └→ mediaNode    判断本轮要不要发图/发视频（按能力是否配置决定是否挂载）
  → outputNode       system 拼装 + 技能注入 + 流式输出（唯一 streamToUser 节点）
  → END
```

- 建图 API 与 ai-runtime 一致：`new StateGraph(name, KeyStrategy.REPLACE)`、`addNode`、`addEdge`、
  `compile(CompileConfig.builder().saverConfig(空).build())`（必须显式传空，否则自动挂 MemorySaver）。
- 每个节点经 `GraphNodeRunState.wrap(...)` 包一层，产出 runTimeState/Langfuse 埋点（先只留本地日志）。
- 三路并行用图的扇出边，不是 `Promise.all`——这是 ai-runtime 的做法，保持一致。

---

## 5. 技能与工具

### 5.1 技能：本地文件夹 → AgentSkill → SkillCatalog

ai-runtime 从 Nacos Skill Registry 拉；这里改成启动扫描：

```
resources/skills/chat/output/send_photo/SKILL.md   → 技能名 chat.output.send_photo
```

frontmatter：`description`（决定会不会被召回）、`priority`（冲突裁决）。
`LocalSkillLoader` 用 `AgentSkill.builder()` 构造后 `catalog.apply(...)` 整表替换，**运行期不读盘**。
不在 `chat.output.` 前缀下的技能直接启动失败（和 ai-runtime 的前缀隔离一致）。

### 5.2 召回：照 ai-runtime 的改写节点做法

`chat.skill.recall.system.md` 里列出「技能名 + description」，一次低温调用让模型返回本轮要遵守的技能名，
再 `catalog.contains()` 净化幻觉名，按 `## 名字（优先级 N）` 注入 output 节点的 prompt。

### 5.3 工具：Toolkit 只放"真能干活"的函数

`send_photo` / `send_video` / `remember` / `recall` 注册进 `Toolkit`（`io.agentscope.core.tool.Toolkit`），
参数 schema 写在 Java 里（代码契约），描述取 SKILL.md。
**技能正文 ≠ 工具描述**：正文是"本轮行为规则"，工具描述是"什么时候调我"，两者分开（这是 ai-runtime 的关键设计）。

能力未配置时不注册对应工具，也不进召回清单——她不会答应"等下拍给你"然后永远发不出来。

---

## 6. 提示词

| 项 | ai-runtime | 本方案 |
| --- | --- | --- |
| 存储 | Nacos Prompt Registry | `classpath:prompts/*.md` |
| 加载 | 启动拉取，缺键失败 | 启动扫描进 `Map`，缺键失败（`5201`） |
| 刷新 | 轮询版本号增量 | `POST /api/v1/prompts/reload` 重建快照 |
| 键 | `PromptKeys` 常量 | 同左，键 = 文件名去 `.md`（`persona.chat.system`） |
| 渲染 | 键 + 变量 | `{{var}}` 替换，缺变量抛 `5202` |

必需键：`persona.chat.system`、`chat.intent.system`、`chat.rewrite.system`、
`chat.skill.recall.system`、`memory.extract.system`、`memory.summary.system`。

---

## 7. 记忆：mem0 是唯一权威

**定稿口径**：记忆只存 mem0，PostgreSQL 不碰记忆（没有 `facts`、没有 `fact_events`），
PG 里只有 `sessions` / `messages` 两张业务表。

| 层 | ai-runtime | 本方案 |
| --- | --- | --- |
| 语义记忆 + 结构化事实 | mem0（语义）+ PostgreSQL `persona`（槽位） | **全部 mem0**：一条记忆 = `{id, text, slot, category, importance, createdAt, updatedAt}` |
| 变更史 | PG `fact_events` 审计表 | mem0 自带 `history`（每次 add/update/delete 都留版本） |
| 会话与消息 | PG / Redis | 本机 PG（`ai-soulmate`）的 `sessions` / `messages` |
| 缓存/队列 | Redis | 进程内队列（`Sinks` / `ArrayDeque`），单用户够用 |
| 读 | L1 摘要 → 不充分再 L2 明细 | 保留两级：`search` 命中就注入 topK；命中不足再 `get_all` 补齐 |
| 写 | 异步、不阻塞 SSE | 同左：回复结束后异步入队抽取 → `add` |

### 7.1 memory-service（本地 mem0，docker，6101）

**接口契约完全照 ai-runtime 的 `Mem0Service`** —— 它调的就是 mem0 OSS REST API，这里一字不改：

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/memories` | `{messages:[{role,content}], user_id, run_id, agent_id, metadata, infer}` | 写入。`infer=false`：文本由 Java 侧组装，mem0 不再二次抽取 |
| `GET` | `/memories` | `?user_id&agent_id&top_k&run_id` | 列出（无分页，只认 `top_k`，上限 1000） |
| `POST` | `/search` | `{query, user_id, agent_id, top_k, filters}` | 语义检索 |
| `PUT` | `/memories/{id}` | `{text, metadata}` | 覆盖文本（新值生效） |
| `DELETE` | `/memories/{id}` | — | 删除 |
| `GET` | `/memories/{id}/history` | — | 变更史 |
| `GET` | `/health` | — | 就绪探针（`docker healthcheck` 用） |

身份三维与 ai-runtime 一致：`user_id`（隔离）、`run_id`（= sessionId 或 `persona`）、`agent_id`（固定 `xiaoman`）。
请求头 `X-API-Key`，Key 只从 `.env` 读，不落日志。

**部署形态**：`memory-service/` 一个 docker 容器，宿主 6101 → 容器 8000。

```
memory-service/
├── Dockerfile            python:3.11-slim（走 1ms 国内镜像源）+ 清华 pip 源
├── requirements.txt      mem0ai / fastapi / uvicorn / fastembed / faiss-cpu
├── compose.yaml          端口、卷、healthcheck
├── .env.example          API Key、agent_id、检索条数、embedding 模型
├── app/main.py           上面 7 个端点（FastAPI，约 200 行）
└── data/                 faiss 索引 + history.db（挂载，删容器不丢记忆）
```

**为什么不直接跑官方镜像**：官方 mem0 server 默认绑 `pgvector` + OpenAI 的 LLM/embedder，
而火山 Agent Plan 明确不支持 embedding 模型（`UnsupportedModel`，已实测），换 OpenAI Key 又违反"不硬编码 Key"。
所以镜像里只换存储与向量化两层，**HTTP 契约与 ai-runtime 调的完全一致**：

| 组件 | 官方镜像 | 本方案 | 原因 |
| --- | --- | --- | --- |
| 向量库 | pgvector（需另起 Postgres） | **faiss**（单文件落 `/data`） | 不再多一个容器，也不再需要 Postgres |
| Embedder | openai `text-embedding-3-small` | **fastembed `BAAI/bge-small-zh-v1.5`**（384 维，ONNX 本地） | 零 Key、离线、中文短句够用；模型构建期预下载 |
| LLM | openai `gpt-5-mini` | **不用**（`infer=false`） | 抽取由 Java 的 prompt 做，mem0 只负责存与检索 |

> 这套取舍带来一个必须守住的约定：**写入必须 `infer=false`**。
> 一旦有人改成 `infer=true`，mem0 会去找 LLM，而容器里没有 → 写入直接失败。
> `Mem0Client.add()` 里已把 `infer` 固定为 false，不给调用方留口子。

### 7.2 矛盾以新为准怎么保证

mem0 自己的 `infer:true` 会用它的 LLM 决定 ADD/UPDATE/DELETE，但那样会绕开我们的槽位体系，
「先住北京后来搬上海」不保证以新为准。所以：

1. `MemoryExtractor` 用 `memory.extract.system.md` 抽出候选：`{slot, text, category, importance}`；
2. `MemoryService` 拿 slot 去 `search` 找同 slot 旧记忆；
3. 同 slot 且文本不同 → **删旧加新**（`delete` 旧 id → `add` 新文本），新值生效，旧版本留在 history；
4. 同 slot 同义 → 只更新 `importance`/`updatedAt`，不新增条目。

`slot` 举例：`profile.city`、`preference.avoid.香菜`、`relationship.称呼`。

### 7.3 降级

mem0 进程没起来 / 超时：`memoryNode` 返回空记忆并置 `degraded=true`，对话照常，前端在
记忆页与设置页显示「记忆服务不可用」；写完失败只记日志，不重试、不回写 PG。
—— 记忆挂了只是"她暂时想不起来"，不能变成"她不能说话"。

---

## 8. 模型接入：火山方舟 Agent Plan

已实测通过（`ark-code-latest` 正常返回）：

```
LLM_BASE_URL=https://ark.cn-beijing.volces.com/api/plan/v3
LLM_MODEL=ark-code-latest
LLM_PROTOCOL=openai
```

- 协议就是 OpenAI `/chat/completions`，用 `WebClient` 调，`stream=true` 解析 SSE 增量。
- 注意响应里有 `reasoning_content`（思考过程），**只取 `message.content`**，不要把思考过程吐给前端。
- `LLM_PROTOCOL=mock` 时走本地规则实现，无 Key 也能跑完整链路（验收用）。
- Key 只在 `.env`，接口只回 `hasKey` + 掩码，日志过脱敏。

---

## 9. 业务接口（对齐 app-server）

| 路径 | 说明 |
| --- | --- |
| `POST /api/v1/chat/stream` | SSE：`text-delta` / `tool-call-start` / `media` / `notice` / `done` / `error` |
| `POST /api/v1/auth/register\|login` | 注册 / 登录，返回 JWT；`/auth/me` 取当前用户 |
| `POST /api/v1/session/bootstrap/get` | 首屏：角色卡、历史、能力、偏好、当前用户 |
| `POST /api/v1/memory/overview/get` | 记忆分组 + 每条的记忆史（`mem0 all` + `history`） |
| `POST /api/v1/memory/create\|delete` | 手动增删（直接打 mem0，不经 PG） |
| `POST /api/v1/settings/get\|update\|test` | 五类服务配置（Key 掩码） |
| `POST /api/v1/speech/asr\|tts` | 语音 |
| `POST /api/v1/prompts/list\|get\|reload`、`/skills/list` | 资源查看与热加载 |
| `GET /api/health`、`/ai/health` | 能力与资源状态 |

返回统一 `{success,data,errMsg,code,userMsg}`，HTTP 恒 200 —— **唯一例外是鉴权失败**：
过滤器直接回 HTTP 401（body 仍是信封，`code=4401`），让浏览器/代理一眼看出"没登录"。

### 9.1 鉴权（JWT）

| 项 | 做法 |
| --- | --- |
| 拦截范围 | `/api/v1/**` 全量强制，仅 `auth/register`、`auth/login` 放行（精确匹配，防前缀绕过）；健康检查与文档不在 `/api/v1` 下，天然放行 |
| 令牌 | HS256，载荷只有 `sub`（用户 id）+ `username`；`iss=ai-soulmate`，`exp` 默认 7 天（`JWT_TTL`） |
| 密钥 | `JWT_SECRET`（.env，≥32 字节）。留空则启动时随机生成 —— 能跑但重启即全员掉线 |
| 密码 | PBKDF2-HMAC-SHA256，21 万轮 + 16 字节随机盐，`pbkdf2-sha256$轮数$盐$哈希` 自描述格式 |
| 身份来源 | **只认 token**：请求体里的 `userId` 全部忽略；控制器统一 `AuthContext.requireUserId(exchange)` |
| 会话归属 | `INSERT ... ON CONFLICT (id) DO UPDATE ... WHERE sessions.user_id = EXCLUDED.user_id`，影响 0 行即越权（4403）。比"先查属主再写"少一个 TOCTOU 窗口 |
| 登录失败 | 用户不存在与密码错都回 `4410 用户名或密码不正确`；用户不存在时也空跑一次哈希，避免用响应时间枚举用户名 |
| 退出登录 | 无服务端接口：JWT 无状态，前端删 token 即退出。要真正吊销得引黑名单/版本号，本 Demo 不做 |

**为什么不是 BCrypt**：Boot 4.0.4 管理的是 `spring-security-crypto` 7.0.4，而本机 m2 里只有 6.5.x ——
钉旧版与 Spring 7 混用有兼容风险，引整套 spring-security 又太重。PBKDF2 是 JDK 自带，零依赖。

---

## 10. 配置

`.env` 用 `spring.config.import: optional:file:./.env[.properties]` 直接当 properties 读
（`KEY=VALUE` 正好是 properties 语法），真实环境变量优先级更高。
设置页保存 = 回写 `.env` 对应行（保留注释与顺序）+ 热更新内存。

> 注：现在实际实现是 `DotEnvEnvironmentPostProcessor` 用 UTF-8 读 `./.env`（官方 properties 读取是
> ISO-8859-1，中文角色卡会乱码），不再用 `spring.config.import`。

鉴权相关：`JWT_SECRET`（签名密钥）、`JWT_TTL`（有效期秒）、`JWT_ISSUER`、`PBKDF2_ITERATIONS`。
存储相关：`PG_URL` / `PG_USERNAME` / `PG_PASSWORD` / `PG_POOL_SIZE`。

---

## 11. 与 ai-runtime 的对应表

| ai-runtime | 本方案 | 说明 |
| --- | --- | --- |
| `SeekyMainBotService`（966 行） | `SoulmateBotService` | 保留建图、SSE 映射、runTimeState 埋点 |
| `nodes/GraphNode` + `common/*` | 原样复制 | 节点契约与状态键不改 |
| `QueryRewriteNode` / `IntentNode` / `FanOutNode` / `OutputNode` | 原样保留 | 提示词换成本地文件 |
| `MemoryNode` / `PersonaNode` | 保留 | 都读 mem0（记忆 + 画像记忆），不再分两处存储 |
| `RetrievalNode` / `DetailNode` / `MedicalRecordSaveNode` | **删除** | 无 RAG、无医疗业务 |
| `SkillCatalog` | 原样复制 | 数据源换成 `LocalSkillLoader` |
| `AiRegistryClient` / `AiRegistryRefresher` / `PromptConfigSource` | **删除** | 被本地加载替代 |
| `Mem0Service` | `Mem0Client` | 保留 HTTP 语义，去掉 Nacos 发现 |
| `PersonaStore`（R2DBC） | `MemoryService`（mem0） | 槽位信息存进 mem0 的 metadata，不再单开表 |
| `SpeechController` / `TextToSpeechService` | 保留 | 协议不变 |
| `AppServerTools`（MCP 调业务） | **删除** | 已合进同一进程，直接调 Service |
| `common/Result`（app-server） | 原样 | 字段与语义一致 |

---

## 12. 结论与实现顺序

已定（你的口径）：

1. **端口 6xxx**：后端 `6100`，mem0 `6101`，前端 vite 代理读 `server/.env` 的 `PORT`，兜底值同步改成 6100。
2. **记忆只用 mem0**：PG 的 `facts` / `fact_events` 已从建表 SQL 删掉，只剩 `sessions` / `messages`。
3. **前端**：接口仍是相对路径 `/api/v1/*`，`api.js` 不用改，只改代理兜底 + README。

只剩一件小事没定（不影响开工，默认值已可跑）：**技能召回每轮多一次模型调用**，
多 100~400ms —— 我按 ai-runtime 的做法**保留**（否则技能只剩关键字规则，就不是"和 ai-runtime 一样"了）。

实现顺序（每步都能单独跑起来）：

| 步 | 内容 | 验收 |
| --- | --- | --- |
| 1 | `memory-service/`：mem0 包 HTTP，6101 可读写 | `add` → `search` 命中 → 重启后 `all` 还在 |
| 2 | 主服务跑通：健康检查 + prompt/skill 载入 + `/prompts/list` | `curl /api/health` 见 skills/prompts 计数 |
| 3 | 装图：rewrite → intent → fanout → output，先只接 LLM 与历史 | 一轮 SSE 能出字 |
| 4 | 接 memory / persona / media 三路节点 + 异步写记忆 | 说"我住上海"→ 记忆页出现 → 换会话仍记得 |
| 5 | 语音、发图发视频工具、设置页回写 | 各能力开关生效 |
| 6 | 前端联调 + 主动回访 + 文档收尾 | 全链路走一遍 |

---

> **当前进度（按上表）**：第 1～4 步已完成并实测，第 5 步完成"发图发视频工具 + TTS"，ASR 与设置页回写未做，第 6 步未做。
>
> - 步 1 ✅ `memory-service/`（docker，faiss + fastembed `bge-small-zh-v1.5`，512 维），
>   写/查/列/改/删/变更史全部走通，容器重启后数据仍在。
> - 步 2 ✅ 健康检查（`/api/health` 报 capabilities 与 resources 计数）、prompt 8 条 / skill 3 条加载、`/prompts/list|get|reload`。
> - 步 3 ✅ 图：`START → 改写 → 意图 → 扇出 → {记忆, 画像, 工具} → 输出 → END`，逐字流式（`text-delta`）。
> - 步 4 ✅ 记忆召回注入 + 异步抽取写回（槽位覆盖生效）+ 工具节点（`send_photo` / `send_video` / `remember` / `recall`）
>   + 媒体帧（`media` / `media-pending`）+ `/api/v1/media/job/get`。
> - 步 5 ⏳ 语音：**TTS ✅**（`TtsService`，**火山豆包语音合成 2.0** HTTP 单向流式
>   `POST /api/v3/plan/tts/unidirectional`，header `X-Api-Key` + `X-Api-Resource-Id: seed-tts-2.0`，
>   响应为换行分隔 JSON 包（base64 音频，`code=20000000` 结束）；音色
>   `zh_female_gaolengyujie_uranus_bigtts`；对外 `/api/v1/speech/tts` 整段 + `/tts/stream` 流式；
>   12 字 0.9s / 53KB / 2.66 秒音频，640 字自动切段 44s / 4.7MB / 233 秒音频）。
>   一律走火山，不接百炼；Key 复用 Agent Plan 专属 Key。
>   ASR 未做（前端给的是 Base64，ai-runtime 那套是「音频 URL + 异步任务」，形态不匹配，
>   接口先返回 4301、`ASR_ENABLED=false`）；设置页回写 `.env` 未做。
> - 步 6 ⏳ 前端三页联调、主动回访未做。
>
> 排查记录（两处值得记住的坑）：
> 1. **流式偶发一个字都不回**：`.env` 里 `LLM_THINKING=`（空值）让 `@Value` 的 boolean 绑定判错，
>    请求没带 `thinking:{type:disabled}`，模型只回 `reasoning_content`；叠加网关静默废弃的连接被复用时，
>    表现就是卡满超时。修法：字符串宽松解析开关 + 自建连接池 + `Connection: close` + 首包 15 秒超时 +
>    "一个字都没吐时"重试一次。
> 2. **工具节点没调工具时不能塞空 GraphFlux**：框架会判 `Empty flux detected`，把一次正常闲聊毁掉。
