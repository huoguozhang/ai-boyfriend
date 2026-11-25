# 小满 · AI 女友数字人

一个可运行的 AI 陪伴 Web Demo：和她长期聊天，她说话像真人、记得你说过的事、会主动发照片和视频。

- 前端：React 18 + Vite + antd-mobile 5（`front/`）
- 后端：Java 21 + Spring Boot 4 + WebFlux，对话编排用 **Spring AI Alibaba Graph**（`server/`），
  与 `seeky-ai-runtime` 同构：同一套 `StateGraph` + 节点契约 + 技能/提示词加载方式
- 账号：**用户名 + 密码**（PBKDF2 加盐哈希），登录拿 **JWT**（HS256，7 天），
  `/api/v1/**` 除注册/登录外全部要带 `Authorization: Bearer <token>`
- 记忆：**本地 mem0**（docker，`memory-service/`，fastembed 向量化；向量库可选 faiss 或 pgvector），只存长期事实；
  业务数据（session / message）在本机 PostgreSQL（库 `ai-soulmate`）里，两不相干
- 配置：**全部在 `server/.env`**（模板 `.env.example`）——五类 AI 服务 + 角色卡 + mem0
- 提示词与技能是**本机 md 文件**（`server/src/main/resources/prompts|skills/`），启动加载进内存
- 端口：主服务 **6100**、mem0 **6101**

设计细节见 **[docs/BACKEND-PLAN.md](docs/BACKEND-PLAN.md)**。

---

## 技术栈

### 后端 `server/`

| 层 | 技术 | 版本 | 用途 |
| --- | --- | --- | --- |
| 语言 / 运行时 | Java | **21**（LTS） | 主服务 |
| 框架 | Spring Boot | **4.0.4** | 容器、自动装配、配置绑定 |
| Web | Spring WebFlux（Reactor Netty） | Boot 自带 | 响应式 HTTP，SSE 流式输出靠它 |
| 对话编排 | Spring AI Alibaba Graph Core | **2.0.0-M1.1** | `StateGraph` 搭对话图（改写 → 意图 → 扇出 → 输出） |
| 模型扩展 | AgentScope（dashscope 扩展） | BOM 管理 | 大模型客户端底层 |
| 数据库 | PostgreSQL + HikariCP | PG **16** | 只存 session / message / users |
| 鉴权 | jjwt（HS256） | — | 登录发 JWT，`/api/v1/**` 校验 Bearer |
| API 文档 | springdoc-openapi（Swagger UI） | — | `http://127.0.0.1:6100/swagger-ui.html` |

### 前端 `front/`

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| React | **18.3.1** | UI 框架 |
| Vite | **5.4.11** | 开发服务器 + 打包 |
| antd-mobile | **5.43.0** | 移动端组件库 |
| playwright-core | 1.65.x | 真实浏览器 E2E（复用本机 Chrome，不下载 Chromium） |

### 记忆与基础设施

| 组件 | 说明 |
| --- | --- |
| mem0（`memory-service/`） | Python FastAPI 包一层 mem0 OSS，docker 跑；向量化用 fastembed 本地 ONNX，向量库存 faiss（本地）或 pgvector（服务器） |
| PostgreSQL 16 | 业务库（session/message/users），本地开发用 Homebrew，生产用 docker compose |
| pgvector / pg17 | mem0 的向量库（服务器部署时） |

---

## 前置依赖

本地开发需要这几样（版本写死的是踩过坑的，别用更低）：

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | **21** | Temurin / Adoptium 均可；Spring Boot 4 要求 17+，项目锁定 21 |
| Maven | 3.9+ | 构建；首次会从 `~/.m2` 离线仓库取依赖 |
| Node.js | 18+ | 前端 `npm install` / `npm run dev` |
| Docker + Compose v2 | 任意较新 | 跑 mem0（`docker compose up`） |
| PostgreSQL | **16** | 业务库，启动时自动建表；库名 `ai-soulmate` |
| （可选）macOS `say`/`afconvert` | — | 生成真人声做 ASR 测试（见「测试」） |

> 没有本机 PG 也没关系：把 `server/.env` 的 `PG_URL` 指向任意可达的 PostgreSQL 16 即可，
> 表结构由服务启动时自动建（`PgStore#init`），不需要手工跑 DDL。

---

## 前后端怎么联调

前后端**同源**，没有跨域问题，也**不用后端开 CORS**。两条路径：

### 开发态：Vite 代理

```
浏览器 ── https://localhost:5173（vite dev server）
            ├─ /api/*   → http://127.0.0.1:6100   （后端）
            └─ /media/* → http://127.0.0.1:6100   （后端托管媒体）
```

- vite 代理目标读 `server/.env` 的 `PORT`（兜底 6100），**后端换端口不用改前端**；
  要临时指向别处：`VITE_API_TARGET=http://127.0.0.1:6100 npm run dev`。
- 手机真机调录音必须 https（见下方「运行」），证书由 `front/gen-cert.sh` 生成。

### 生产态：nginx 反代

```
用户 ── https://101.43.26.29（nginx :443）
            ├─ /            → /opt/soulmate/web（front/dist 静态）
            └─ /api/        → 127.0.0.1:6100（Java，systemd）
```

- 前端构建产物 `front/dist` 由 nginx 托管，`/api/` 反代到后端，仍然是同源。
- **SSE 必须关 nginx buffering**（`proxy_buffering off`），否则回复会「憋一大段」。
- 完整部署见 **[`deploy/README.md`](deploy/README.md)**。

### 约定

- 接口统一前缀 **`/api/v1/**`**；对话是 **SSE**（`text/event-stream`），每帧 `event:` + `data:` JSON。
- 媒体文件走 **`/media/**`**，由后端静态托管（本地）或 nginx 直接伺服（生产）。
- 后端默认 **6100**、mem0 **6101**、前端 dev **5173**。

---

## 运行

需要 JDK 21、Maven、Docker（跑 mem0）、本机 PostgreSQL 16。

```bash
# 0. 业务库（本机 PG，只需一次；库名带连字符要加引号）
psql -d postgres -c 'CREATE DATABASE "ai-soulmate";'
#   连接信息在 server/.env 的 PG_URL / PG_USERNAME / PG_PASSWORD，表由服务启动时自动建

# 1. 记忆服务（首次会构建镜像并下载 embedding 模型，约 1~3 分钟）
cd memory-service && cp .env.example .env && docker compose up -d --build
curl -s http://127.0.0.1:6101/health        # 期望 {"status":"ok","ready":true,...}

# 2. 后端
cd ../server && cp .env.example .env        # 填 LLM_* 三个值
./run.sh                                     # http://127.0.0.1:6100
#   ./run.sh --hot                           改 Java 代码自动重启（devtools）
curl -s http://127.0.0.1:6100/api/health

# 3. 前端
cd ../front && npm install && npm run dev    # http://127.0.0.1:5173
```

`vite.config.js` 的代理目标读 `server/.env` 的 `PORT`（兜底 6100），后端换端口不用改前端。
要临时指向别处：`VITE_API_TARGET=http://127.0.0.1:6100 npm run dev`。

### 手机真机调试（录音 / 语音）

手机连同一个 Wi-Fi 打开 `http://<本机IP>:5173` 时**录音一定不可用**，这不是浏览器兼容问题：
浏览器只在「安全上下文」（**https** 或 **localhost**）里暴露 `navigator.mediaDevices`，
http 局域网地址下它是 `undefined`，连"申请麦克风权限"的入口都没有，也不会有授权弹窗。

所以 dev server 要挂 https：

```bash
cd front
./gen-cert.sh          # 生成自签证书（SAN 自动带上当前局域网 IP）
npm run dev            # 终端会打印 Network: https://192.168.x.x:5173
```

手机打开终端里那个 `https://` 地址，首次会有「此连接非私人」警告，两种处理：

- **直接放行**：显示详细信息 → 访问此网站。多数情况录音即可用（权限还会再弹一次，选允许）。
- **彻底消警告**：手机打开 `https://<本机IP>:5173/__dev/ca.crt` 下载证书（或 AirDrop `front/certs/ai-soulmate-dev.crt`），
  设置 → 通用 → VPN与设备管理 → 安装，再到 设置 → 通用 → 关于本机 → 证书信任设置 里对该证书**启用完全信任**。重开页面即无警告。

换了 Wi-Fi 或 DHCP 换了 IP，重跑 `./gen-cert.sh` 再重启 dev server 即可（证书只对生成时的 IP 有效）。
`front/certs/` 已在 `.gitignore` 里 —— 私钥不进仓库。

单端口预览（不上 vite）：

```bash
cd front && npm run build      # 产出 front/dist，后端会直接托管
```

## 测试

自测分两层，**"能启动"不算通过**，两层都绿才算这一轮做完了。

### 后端：JUnit（含真库集成）

```bash
# 集成测试要一个独立库，只建一次；它会被清空，别指向 ai-soulmate
psql -d postgres -c 'CREATE DATABASE "ai-soulmate-test";'

cd server && mvn -o test
```

两层内容：

| 测试类 | 数量 | 验什么 |
| --- | --- | --- |
| `PasswordHasherTest` | 7 | 格式自描述、盐随机、错密码/篡改哈希/垃圾串一律 false、中文口令不被截断 |
| `JwtServiceTest` | 8 | 签发校验往返、改签名/换密钥/换 issuer/alg=none 全部拒绝、空串不抛异常 |
| `AuthServiceTest` | 11 | 注册重名 4411、并发撞索引回退、密码只存哈希、登录不区分"用户不存在/密码错"、禁用账号 4412 |
| `ChatStoreTest` | 11 | **连真实 PG**：跨用户 `ensureSession` 抛 4403、两用户并发抢同一 sessionId 只有一个赢、history/recent 按 user 隔离、message_count 自增 |

`ChatStoreTest` 特意不用 H2 / 不 mock：要验的正是
`INSERT ... ON CONFLICT (id) DO UPDATE ... WHERE sessions.user_id = EXCLUDED.user_id`
这条"写不进去 = 没权限"的原子判断，换内存库就测不到。

### 前端：真实浏览器点击

```bash
cd front && npm run e2e
```

用**本机已装的 Google Chrome**（`playwright-core` 不带浏览器，不用下载 Chromium），
`ignoreHTTPSErrors` 过自签证书。前置条件：后端 6100、前端 5173 都在跑。

覆盖的路径就是用户真会走的：打开登录页 → 注册 → 发一条消息等真实回复 →
语音按钮在演示模式下的提示 → 刷新保持登录 → 设置页退出 → 重新登录看历史 → 错密码的文案。

几条刻意的断言：

- **"演示模式没有把固定文本发进对话"**：确认 ASR mock 时不会把 `MOCK_TEXT` 当用户消息发出去；
- **"这一轮说完后输入区恢复可输入"**：发现过一个真 bug —— busy 期间点录音静默无响应，已补提示；
- **"页面无 JS 报错"**：把 `console.error` 与 `pageerror` 也当断言。

失败项会打印实际值（不是只报 false），截图落在 `front/e2e/shots/`（已 gitignore）。

### 语音识别：真的听懂了才算过

```bash
# 造一段真人声（macOS say），16kHz 单声道 —— 正好是 recorder.js 产出的格式
say -v Tingting "感冒了怎么办" -o /tmp/a.aiff
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/a.aiff /tmp/asr-test.wav

cd front && AUDIO=/tmp/asr-test.wav EXPECT=感冒 npm run e2e:voice
```

这条测试让 Chrome 的假麦克风**播放这段真人声**（`--use-file-for-fake-audio-capture`），
走完 按下录音 → 上传 → 方舟转写 → 变成一条消息 的全过程，并从接口读回落库的文字断言"包含 感冒"。

为什么要单独一条：`npm run e2e` 用的是合成音（正弦波），能证明"链路通"，但证明不了"听得懂"。
只有放真人才会验证到识别质量，也才发现过一个真 bug —— ASR 返回空白时前端会插入一条
刷新后消失的"幽灵消息"（已修：发送前 trim）。

### 服务器部署：mem0 换 postgres(pgvector)

本地是 faiss（单文件、零依赖）。服务器上用 `compose.pg.yaml`：多一个 postgres，mem0 的向量库换成 pgvector，多实例/批量导入不会打架。

```bash
cd memory-service
cp .env.pg.example .env                     # 改 PG_PASSWORD、MEM0_API_KEY
docker compose -f compose.pg.yaml up -d --build
curl -s http://127.0.0.1:6101/health        # "vector_store":"pgvector" 才算对
```

几个刻意的点：

- `PG_PASSWORD` 没配时 compose 直接报错退出（`${PG_PASSWORD:?}`），不会用默认密码起库。
- postgres 只绑 `127.0.0.1:5432`，公网连不到；远程要连库走 `ssh -L 5432:127.0.0.1:5432`。
- 向量在 `pgdata` 卷里（`down` 不删，`down -v` 才删），history.db 仍是 SQLite，落在 `./data`。
- 建库时 `initdb/01-vector.sql` 自动 `CREATE EXTENSION vector`，mem0 自己也会再兜一次。
- 国内源全在 Dockerfile 里：pip 走清华、模型走 hf-mirror；基础镜像用 build arg `BASE_IMAGE` 覆盖
  （服务器 daemon 已配腾讯云加速器，`python:3.11-slim` 直连 docker.io 即可）。

#### 已部署实例

`101.43.26.29` 上已经跑着这份（`/opt/soulmate/memory-service`，mem0ai 2.2.1 + pgvector/pg17）。
这台是**轻量应用服务器**（Lighthouse，`lhins-6awak6xw`，ap-shanghai），**没有安全组概念**，
放行端口走的是「防火墙」规则，用 `lighthouse` 产品的 `CreateFirewallRules` 加：

| 端口 | 用途 | 来源 |
|---|---|---|
| 80 / 443 | web（nginx，宝塔） | 0.0.0.0/0（原有） |
| 8888 | 宝塔面板 | 0.0.0.0/0（原有） |
| 22 | SSH | 0.0.0.0/0（原有） |
| **6101** | mem0 | 125.121.161.38/32 |
| **5432** | postgres | 125.121.161.38/32 |
| **6100** | 后端（预留，还没部署） | 125.121.161.38/32 |

```bash
curl -s http://101.43.26.29:6101/health      # 直连，只在白名单 IP 上通
# 接口要带 X-API-Key，key 在服务器 /opt/soulmate/memory-service/.env
```

80 端口另外留了一段 nginx 反代兜底（`/www/server/panel/vhost/nginx/mem0.conf`，
`server_name` 用 IP 精确匹配，只代理 `/mem0/` 前缀）——**出口 IP 变了或被挡时**，
把 BASE_URL 换成 `http://101.43.26.29/mem0` 就能继续用，因为 80 是全域放行的。

本地 `server/.env` 默认走反代这条（IP 一动也不至于断）：

```dotenv
MEM0_BASE_URL=http://101.43.26.29/mem0
MEM0_API_KEY=<服务器 .env 里的 MEM0_API_KEY>
```

> 两条路都是明文 HTTP，key 在链路上可见；真上线应该挂域名 + HTTPS。
> 5432 只放了自己的 IP —— Postgres 对公网 0.0.0.0/0 是找爆破。

### 关于 `exit code 143`

停后端时 Maven 会打一段 `Failed to execute goal … spring-boot-maven-plugin … Process terminated with exit code: 143` 然后 `BUILD FAILURE`。**这不是构建失败**：143 = 128 + 15（SIGTERM），只是进程被正常终止，Maven 把它当异常报了出来。编译是好的、服务也是好的。

`./run.sh` 走的是「打完包 `java -jar`」，没有 Maven 这层包装，Ctrl+C 退出不会再出现这段红字。只有 `./run.sh --hot`（`mvn spring-boot:run`）才会有，属正常现象。

## 生产部署

已经跑在 **`https://101.43.26.29`**（腾讯云 Lighthouse，2 核 3.5G）。前端静态 + `/api/` 反代 + `/mem0/` 反代共用一个 nginx，后端由 systemd 托管，业务库是 docker 里的 PostgreSQL 16（与本地开发版本一致）。

```
:443 nginx ─┬─ /           /opt/soulmate/web        front/dist
（LE IP 证书）├─ /api/       127.0.0.1:6100           Java（systemd: ai-soulmate）
            └─ /mem0/      127.0.0.1:6101           mem0

:80  nginx ─┬─ /  → 301 到 https
            ├─ /mem0/ 仍留明文（本地后端还在用它）
            └─ /.well-known/acme-challenge/  给 Let's Encrypt 续期用
```

**必须用 https**：浏览器只在安全上下文里给麦克风，http 下「按住说话」直接不可用。

证书是 **Let's Encrypt 的 IP 地址证书**（2026-01 起 GA，不需要域名，浏览器天然信任）。代价是有效期只有 **160 小时**，靠 `certbot-renew.timer` 每 3~4 天自动续一次，所以 `systemctl list-timers certbot-renew.timer` 值得偶尔瞟一眼。自签私有 CA 仍留在 `/opt/soulmate/certs/` 作回退。

部署文件、证书申请与续期的两个坑（默认 30 天阈值 > 证书寿命、renew 默认随机 sleep 5 分钟）、更新方式和其余注意事项都在 **[`deploy/README.md`](deploy/README.md)**。

---

## 配置：只有一个 .env

`server/.env` 的每一行都会被 `DotEnvEnvironmentPostProcessor` 以 **UTF-8** 读进环境（
官方 properties 读取是 ISO-8859-1，角色卡里的中文会变乱码，所以自己读）。真实环境变量优先级更高。

| 键 | 说明 |
| --- | --- |
| `PORT` / `HOST` | 后端端口，默认 6100 |
| `LLM_BASE_URL` `LLM_API_KEY` `LLM_MODEL` | 大模型（OpenAI 兼容端点；当前接火山方舟 Agent Plan） |
| `LLM_THINKING` | 是否让模型输出思考过程。**默认关**：开了之后每轮先吐几百个 reasoning token，首字要等十几秒 |
| `MEM0_BASE_URL` `MEM0_AGENT_ID` `MEM0_SEARCH_LIMIT` | 记忆服务与召回条数 |
| `TTS_BASE_URL` `TTS_API_KEY` `TTS_MODEL` `TTS_VOICE` `TTS_RESOURCE_ID` | 语音合成（火山豆包语音合成 2.0，`doubao-seed-tts-2.0` + `zh_female_gaolengyujie_uranus_bigtts`；Key 与 `LLM_API_KEY` 相同）。`TTS_PROTOCOL=mock` 时出一段提示音，链路仍可验收 |
| `COMPANION_*` | 她是谁：名字、年龄、城市、职业、性格、说话方式、猫叫什么 |

某类能力没配好时**只有它自己不可用**，其余照常：语音、图像、视频各自独立降级，
`/api/health` 的 `capabilities` 会说明缺什么。

---

## 一轮对话发生了什么

```
START → 问题改写（+技能召回）→ 意图 → 扇出 ┬→ 记忆召回 ┐
                                        ├→ 角色画像 ┼→ 工具 → 输出 → END
                                        └─────────┘
```

- **改写**：把「那它呢」补成自包含问句，否则拿去做记忆语义检索会检索出完全无关的事。
  同一次调用顺手让模型从技能清单里挑这一轮要遵守的规则（省掉一次往返）。
- **技能**：`skills/chat/output/*/SKILL.md`，名字即目录（`chat.output.comfort`），
  frontmatter 的 `description` 决定会不会被召回，`capability` 声明能力依赖（图/视频没配就整条不加载）。
- **记忆**：mem0 语义检索后拼成「记忆块」注入提示词；回答完再异步抽一轮新事实写回去。
  同槽位（`profile.city` 这种）以新为准，旧值删掉。
- **工具**：`send_photo` / `send_video` / `remember` / `recall`。她先判断要不要动手，再开口说话；
  照片/视频在文字之前发出，避免「我发给你了」然后什么都没有。
- **输出**：全图唯一流式节点，逐字推给前端。

SSE 帧：`text-delta` / `tool-call-start` / `tool-result` / `media` / `media-pending` / `notice` /
`memory-updated` / `done` / `error`。

---

## 改她的说话方式 / 加一条技能

提示词：`server/src/main/resources/prompts/*.md`，文件名即 key（`persona.chat.system.md` → `persona.chat.system`），
`{{变量}}` 是占位符，**缺变量会直接启动失败**（静默留空会拼出半句话，行为诡异却查不出原因）。

```bash
curl -X POST localhost:6100/api/v1/prompts/reload -H 'Content-Type: application/json' -d '{}'
curl -X POST localhost:6100/api/v1/prompts/list   -H 'Content-Type: application/json' -d '{}'
```

技能：在 `skills/chat/output/<名字>/SKILL.md` 新建，`description` 写给「什么时候用我」，
正文写给「这一轮怎么说话」，两者不要混。

```markdown
---
description: 用户情绪低落、抱怨累时怎么接话
priority: 10
---

## 这一轮：先接住情绪，再谈事
- 第一句回应她的感受，一句话就够。
```

改完重启（技能目前只在启动时加载）。

---

## 语音识别（ASR）

三条路，按 `ASR_PROTOCOL` 选：

| 协议 | 用什么 | 需要什么 | 说明 |
| --- | --- | --- | --- |
| `ark`（推荐） | 方舟 `/api/plan/v3/chat/completions`，音频当 `input_audio` 发进去 | **与 `LLM_API_KEY` 同一把 ark Key** | 不用另开账号；实测「感冒了怎么办」原样转出，1.2s 音频约 4~5 秒返回 |
| `volcengine` | 火山「语音技术」录音文件识别极速版 | 单独的 openspeech Key（与 ark **不通用**） | 要和资源 ID 配套，否则 45000000 |
| `mock` | 固定文本 | 无 | 仅为演示链路；前端**不会**把它当成你说的话发出去 |

跑的是哪条，`/api/v1/session/bootstrap/get` 的 `capabilities.asr` 会如实回 `{configured, mock}`，
E2E 里有一条断言专门钉住 `mock === false`，防止"以为在真识别、其实在读固定文本"。

服务端 ASR 不可用时，前端会自动降到**浏览器原生识别**（Web Speech API，iOS Safari 支持）：
好处是零配置立刻能用，代价是拿不到音频，只能出文字（"你的语音气泡"这个形态就没了）。
所以它只是兜底，配了 Key 一定优先走服务端。

---

## 语音（TTS）

走**火山引擎豆包语音合成 2.0** 的 HTTP 单向流式接口。用的是方舟 Agent Plan 的专属 Key
（和 `LLM_API_KEY` 同一个），不需要另开豆包语音的 appid / access_token：

- `POST https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional`
- header：`X-Api-Key`（专属 Key）+ `X-Api-Resource-Id: seed-tts-2.0`
  （2.0 音色必须配 2.0 的 resource id，配成 1.0 会直接 45000000 音色鉴权失败）
- 请求体：`{"user":{"uid"},"req_params":{"text","speaker","audio_params":{"format","sample_rate","bit_rate"}}}`
- 响应是**换行分隔的 JSON 包**（不是裸二进制）：每包 `{"code":0,"data":"<base64 音频>"}`，
  中间可能夹一包只有 `sentence` 的时间戳（`data` 为 null），最后一包 `code=20000000` 表示结束。
  各包 `data` 依次 base64 解码拼起来就是完整 MP3。
- **前端按句切分后一次请求一句**（`front/src/voice.js`）：整段返回比拼分片简单，拿到就能播；
  需要「边合成边播」时用流式接口。

```bash
# 整段（前端用的就是这个）：{audioBase64, contentType, format, voice, characters, bytes}
curl -s -X POST localhost:6100/api/v1/speech/tts -H 'Content-Type: application/json' \
  -d '{"text":"今天降温了，记得多穿点。"}' | python3 -c "import json,sys,base64;d=json.load(sys.stdin)['data'];open('/tmp/a.mp3','wb').write(base64.b64decode(d['audioBase64']));print(d['bytes'],'bytes')"

# 流式：start → audio(seq 递增) → done，失败补 error
curl -N -X POST localhost:6100/api/v1/speech/tts/stream -H 'Content-Type: application/json' \
  -d '{"text":"下班路上慢点，风挺大的。"}'

# 当前生效的音色/模型（不含 Key）
curl -s localhost:6100/api/v1/speech/tts/config
```

实测：12 字 → 53KB / 2.66 秒音频，合成耗时约 0.9 秒；17 字 0.97s / 65KB；
640 字自动切段 → 4.7MB / 233 秒音频（44 秒合成）。音频是 24kHz 单声道 MP3 / 160kbps。

上限是按句切分后的单句，`.env` 的 `TTS_MAX_TEXT_LENGTH`（默认 600）只是客户端保守值；
整段接口超了会自动按标点切段再拼接，流式接口则返回 4201 要求调用方自己切。

**音色与 resource id 必须配套**：`*_uranus_bigtts` / `*_moon_bigtts` 是 2.0 音色（配 `seed-tts-2.0`），
默认用 `zh_female_gaolengyujie_uranus_bigtts`（清冷御姐，贴「嘴硬心软」的人设）。
换成 1.0 音色时 `TTS_RESOURCE_ID` 要一起改成 `seed-tts-1.0`。

---

## 账号与鉴权

```bash
# 注册（用户名 3~24 位，密码 ≥6 位；用户名不区分大小写）
curl -s -X POST http://127.0.0.1:6100/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"chenyu","password":"chenyu123","nickname":"陈屿"}'
# → {"success":true,"data":{"token":"eyJ...","tokenType":"Bearer","expiresIn":604800,"user":{...}}}

# 登录
curl -s -X POST http://127.0.0.1:6100/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"chenyu","password":"chenyu123"}'

# 之后每个业务请求都带上
TOKEN=...   # 上面返回的 data.token
curl -s -X POST http://127.0.0.1:6100/api/v1/session/bootstrap/get \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
```

| 接口 | 说明 |
| --- | --- |
| `POST /api/v1/auth/register` | 注册，成功后直接返回 token（不用再登一次） |
| `POST /api/v1/auth/login` | 登录 |
| `GET\|POST /api/v1/auth/me` | 当前 token 对应的用户（前端刷新时校验登录态） |

几条硬规则：

- **身份只来自 token**：请求体里的 `userId` 一律忽略；拿别人的 `sessionId` 发消息会得到
  `error` 帧 `code=4403`（会话归属由一条 `ON CONFLICT ... WHERE user_id 相同` 的原子写校验）。
- **没登录**：`/api/v1/**` 回 **HTTP 401** + `{"code":4401}`（全项目唯一不返回 200 的地方）。
  前端 `api.js` 收到 4401 就清登录态、自动回登录页。
- **退出登录**：前端删 localStorage 里的 token。JWT 无状态，服务端没有会话可销毁；
  真要立刻吊销只能换 `JWT_SECRET`（代价是所有人一起下线）。
- **账号表**：`users`（id / username / password_hash / nickname / enabled / created_at /
  updated_at / last_login_at），`lower(username)` 上唯一索引 —— `Alice` 和 `alice` 是同一个人。
- 密码用 **PBKDF2-HMAC-SHA256（21 万轮 + 随机盐）**，不是 BCrypt：Boot 4.0.4 管理的
  `spring-security-crypto` 7.0.4 不在本机 m2，钉 6.5.x 与 Spring 7 混用有风险，而 PBKDF2 是 JDK 自带。

> 从单用户版本升上来的老数据（`user_id = 'local-user'`）没有绑定任何账号，登录后看不到。
> 想把它归到你新注册的账号上：
> ```sql
> UPDATE sessions SET user_id = '<新用户 id>' WHERE user_id = 'local-user';
> UPDATE messages SET user_id = '<新用户 id>' WHERE user_id = 'local-user';
> ```

---

## 记忆

```bash
# 全部记忆
curl -s "http://127.0.0.1:6101/memories?user_id=local-user&agent_id=xiaoman&top_k=100" | python3 -m json.tool
# 语义检索
curl -s -X POST http://127.0.0.1:6101/search -H 'Content-Type: application/json' \
  -d '{"query":"他住哪","user_id":"local-user","agent_id":"xiaoman","limit":5}'
```

写入口令：**所有写入都带 `infer=false`**（事实是上游模型已经抽好的，再让 mem0 用 LLM 二次加工
既慢又容易改写原意）。

---

## 目录

```
front/                 React 前端（页面：聊天 / 记忆 / 设置）
server/                Java 后端
  src/main/java/com/seeky/airuntime/
    service/chat/      对话图：SoulmateBotService + nodes/
    service/memory/    mem0 客户端与记忆读写
    service/speech/    语音合成（火山豆包语音合成 2.0）
    service/chat/tools/工具实现（发图/发视频/记住/想起来）
    skill/ prompt/     技能与提示词的本地加载
    store/             PostgreSQL（session / message）
  src/main/resources/  prompts/ skills/ 与配置
memory-service/        本地 mem0（docker，FastAPI 包一层 mem0 OSS）
deploy/                生产部署（compose / systemd unit / nginx 站点），见 deploy/README.md
docs/                  方案与设计文档
```

---

## 当前进度

已完成并实测：对话主链路（逐字流式）、mem0 记忆读写与槽位覆盖、技能召回注入、
四个工具与媒体帧（照片 / 视频任务轮询 / 记忆刷新）、`.env` UTF-8 加载、健康检查与资源接口、
**语音合成**（整段 + SSE 流式，火山 `doubao-seed-tts-2.0`）。

未完成：语音分析 ASR（前端录音是 Base64，现有方案是「音频 URL + 异步任务」，形态不匹配，
`/api/v1/speech/asr` 暂时返回 4301 明确提示）、设置页配置存在浏览器（请求头带上，不落服务端）、主动回访、前端三页联调收尾。
