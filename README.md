# 智群 AgentTeam · 服务端

AI 智能体团队系统的后端。基于 [AgentScope Java] 的 ReAct 循环实现真正的智能体编排：@项目经理提一个需求，他会自动拆解任务、把工作委派给程序员和测试、按需创建项目群，完成后把总结发回你的单聊。

## 相关仓库

- 前端界面 [agent-team-web](https://github.com/guyu0327/agent-team-web)：Vue 3 + TypeScript + Pinia，类微信深色界面
- 桌面壳 [agent-team-desktop](https://github.com/guyu0327/agent-team-desktop)：Electron 打包分发，自动拉起本服务并管理数据目录与备份

## 核心机制

### 回复规则
- @谁谁回；没人被 @ 时全员依次回复（后一个能看到前一个的发言）
- 群聊回复（被动与自由讨论）统一接入 AgentScope 的 `OpenAIMultiAgentFormatter`：历史里的成员消息与用户消息都携带发言人名称，请求侧合并为带署名的 `<history>`，成员能分清「谁说了什么」，不会把其他成员的发言当成自己的历史；自由讨论的「轮到你发言」「选下一位发言人」等控制指令通过 `MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE` 标记保持为真实用户轮，不混入历史
- 编排者例外：被 @ 或在场时，由编排者单独运行协作循环，其他成员由他调度
- 群聊模式（`chat_mode`）：被动（默认，按上述规则）| 自由讨论。自由讨论仅对无编排者的群生效：首轮回复后由「主持人」模型逐轮选出下一位发言人接龙，直到主持人判定结束、达到轮数上限（8）、总超时（10 分钟）或用户终止；`PUT /api/conversations/{id}/mode` 切换，建群时可通过 `chatMode` 直接指定

### 编排协作（透明协作）
编排者通过四个工具驱动团队：

| 工具 | 作用 |
| --- | --- |
| `list_team` | 查看可委派的成员及职责 |
| `create_team` | 需要多成员配合时自动创建项目群，协作过程进群 |
| `delegate` | 委派子任务；成员用自己的人设和模型独立执行，输出作为真实消息流式展示 |
| `finish` | 提交最终总结，自动发回用户发起请求的单聊 |

- 协作全程透明：编排者的每段发言、成员的完整输出都是真实持久化的聊天消息
- 编排者发言按工具调用自动切分为多段气泡
- 协作或自由讨论进行中可随时终止：`POST /api/conversations/{id}/stop`（同时终止编排协作与自由讨论，已产生的输出保留）
- 限制：maxIters=10，整体超时 8 分钟，单个成员 4 分钟

### 文件工具（沙箱）
- 所有智能体（含普通单聊直答）都运行在 ReAct 循环上，文件能力来自 AgentScope harness 的 `FilesystemTool`：`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`，另有 `execute` 终端命令工具（Windows 下为 cmd）
- 沙箱 = 主工作区目录 + 白名单目录 + 会话授权目录：相对路径解析到主工作区；绝对路径必须落在允许范围内，越界请求会返回错误说明供模型自行纠正；Windows 多盘符按路径自动路由到对应文件系统
- 会话授权：用户在聊天输入框附加文件/文件夹后，该路径自动对本会话开放读写（`conversation_file_grants` 表）；编排者建群时会把发起会话的授权复制一份到项目群（两份副本互相独立），文件工具的作用域全程跟随当前协作目标会话，群内撤销立即生效
- 通过 `PUT /api/settings/workspace` 运行时修改全局沙箱，立即生效，重启不丢（持久化于 `app_settings` 表）
- 文件/目录选择：桌面壳调用系统资源管理器原生对话框，前端拿到真实路径后交给后端

### 受控操作审批（人审卡片）
- 写入（`write_file`）、修改（`edit_file`）与终端命令（`execute`）是受控操作：智能体发起时回复暂停，前端弹出审批卡片，展示发起智能体、操作类型、目标文件与完整内容/命令
- 三个决定：**允许一次** / **本会话允许**（持久化于 `operation_grants` 表，按会话+操作类型生效，同类操作后续不再询问）/ **拒绝**；卡片 120 秒未响应按拒绝处理
- 被拒或超时的操作不会执行，模型收到拒绝说明并被告知不要反复重试；会话删除或消息重置时自动清除本会话授权
- 框架 `FilesystemTool` 没有删除工具，删除文件只能经 `execute` 命令完成，同样需要审批
- 决定接口：`POST /api/conversations/{id}/op-grant`（`requestId` + `decision: once|conversation|deny`）

### 图片消息（多模态）
- 附加文件时按内容自动识别类型：文件夹 / 图片 / 普通文件，授权表随之记录 `type`
- 用户消息中的图片附件会构建为多模态 `UserMessage`（文本 + ImageBlock）交给智能体：支持视觉的模型（如 mimo-v2.5）可直接理解图片内容；纯文本模型收到图片时由模型侧报错，走 `reply_error` 展示
- 历史消息中的图片在每轮对话都会重新注入，模型可随时回看
- 进入模型上下文的单图上限 8MB（超限自动跳过，文本标注不受影响）
- 前端气泡展示图片用只读端点：`GET /api/fs/content?path=`（仅限图片扩展名，单图 ≤ 20MB）

### 文生图工具（generate_image）
- 模型预设分四类协议：`openai-chat`（对话，默认）/ `dashscope-image` / `openai-image` / `siliconflow-image`（文生图）；图像类预设的「名称」即模型名（如 `z-image-turbo`、`dall-e-3`、`Kwai-Kolors/Kolors`），API 地址填官方文档的完整图像接口地址（如 `https://api.siliconflow.cn/v1/images/generations`），后端原样请求、不做任何路径拼接，密钥同样加密存储、不回显
- 智能体可绑定一个图像预设（可空）：绑定的智能体（单聊、群成员、编排者均生效）在 ReAct 循环中获得 `generate_image(prompt, size)` 工具，调用文生图服务后把图片保存到主工作区 `generated/` 目录（文件名时间戳+随机、只增不覆盖），并在回复中以 Markdown 引用路径展示
- 支持的模型：DashScope 同步端点（z-image-turbo、qwen-image）、OpenAI Images 兼容接口（dall-e-3、gpt-image-1 及同形聚合服务）与硅基流动（Kwai-Kolors/Kolors、Qwen/Qwen-Image，返回的图片 URL 一小时有效、适配器即时下载落盘）；wanx 等异步任务型暂不支持
- 生图写入位置固定且不覆盖已有文件，属用户主动要求的创作行为，豁免审批卡片；未绑定图像预设的智能体看不到该工具

### 实时语音转写（讯飞流式听写）
- WebSocket 端点 `/api/asr/stream`：前端发二进制 16k PCM 音频与 `{"type":"stop"}` 控制帧；后端按讯飞节奏（40ms / 1280B 一帧，base64）装帧转发到 `wss://iat-api.xfyun.cn/v2/iat`，识别结果转为 `{type:partial|final|error|end}` JSON 回传
- 每条前端连接一个 `SessionBridge`：队列缓冲（满丢最旧保延迟）、发送链串行化、60 秒上限自动收尾、stop 后排空残余等 final 再关，所有清理路径收敛到幂等 `shutdown()`
- 鉴权 URL 每次连接即时生成（HmacSHA256 签名，RFC1123 GMT date，本机时钟偏差需在 5 分钟内）；错误码透传中文提示（10105 鉴权、11200 免费次数用尽等）
- 配置持久化于 `app_settings`（key=`asr.streamConfig`），经 `GET/PUT /api/settings/asr-stream` 读写；三项全部留空保存则清除配置

## 功能特性

- 用户 / 智能体 / 模型预设 / 会话 / 消息完整 REST API
- 单聊、群聊、群成员管理、解散、置顶、重命名、已读未读
- OpenAI 兼容模型接入：每个智能体可关联不同预设、独立温度与角色设定
- 文生图：预设按协议分类（对话 / DashScope 文生图 / OpenAI Images 文生图），智能体绑定图像预设即获得 `generate_image` 工具，图片落工作区并在气泡中展示
- 编排者开关（`is_orchestrator`）：任意智能体可设为团队编排者
- 受控操作审批：AI 写入、修改文件与执行终端命令前弹卡片询问用户，支持允许一次 / 本会话允许 / 拒绝

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 框架 | Spring Boot 4.1.1（Java 21） |
| 编排 | AgentScope Java 2.0.1（ReActAgent + Toolkit + harness 文件/shell 工具） |
| 模型接入 | agentscope-extensions-model-openai（OpenAI 兼容接口） |
| 数据 | Spring Data JPA + SQLite（单文件，WAL 模式） |
| 其他 | Lombok、SseEmitter（SSE 流式推送） |

## 快速开始

环境要求：JDK 21。数据库使用 SQLite 单文件，无需安装，首次启动自动建库建表并创建默认用户。

```bash
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

服务监听 `127.0.0.1:8080`。前端开发服务器会将 `/api` 代理到该端口。

### 数据库与升级

- 数据文件默认为 `./data/agent_team.db`（目录不存在会自动创建，`-wal`/`-shm` 为 WAL 模式运行时文件），可通过 `spring.datasource.url` 覆盖路径
- 表结构由版本化迁移管理：`src/main/resources/db/migration/V{n}__xxx.sql`，启动时自动执行未应用的脚本并记录于 `_migration` 表，升级版本无需手动执行 SQL
- SQLite 同一时间只允许一个写入连接，连接池已固定为 1
- 数据库快照：`GET /api/settings/backup/database` 基于 `VACUUM INTO` 导出一致性热备份（含未落盘的 WAL 数据），返回 SQLite 数据库文件；桌面壳的「导出备份」即调用此接口

### 本地访问令牌

配置 `app.security.token` 后，所有 `/api/**` 请求必须携带 `X-AT-Token` 请求头（或 `token` 查询参数），防止本机其他进程或浏览器网页访问 API；留空则不校验（裸跑开发）。桌面壳（agent-team-desktop）启动时会自动生成并传入随机令牌。

### 密钥加密存储

- 模型预设的 API Key 与讯飞语音识别的 APIKey/APISecret 在数据库中经 Windows DPAPI 加密存储（`dpapi:` 前缀标识，JNA 调用，启动时自动迁移存量明文），数据库文件或备份被拷走后密钥不可解
- 加密绑定当前 Windows 账户：直接把 `agent_team.db` 拷到其他账户/机器会因解密失败而显示为未配置，需重新填写；非 Windows 环境降级为明文存储
- 密钥永不回传前端：`GET /api/model-presets` 与 `GET/PUT /api/settings/asr-stream` 只返回 `hasKey` 等状态；更新时密钥留空表示保持不变（讯飞三项全部留空表示清除配置）

### 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.datasource.*` | `./data/agent_team.db` | SQLite 数据文件路径 |
| `app.security.token` | 空（不校验） | 本地访问令牌，桌面壳自动注入 |
| `app.workspace.root` | `./workspace` | 未在设置页自定义时的默认工作区目录 |

## SSE 事件协议

`POST /api/conversations/{id}/messages`，响应为 `text/event-stream`：

| 事件 | 说明 |
| --- | --- |
| `user_message` | 用户消息已持久化（含 `attachments` 附件元数据，`type` 为 `file`/`dir`/`image`，无附件为空数组） |
| `reply_start` | 智能体开始回复（messageId / agentId / conversationId） |
| `delta` | 流式增量文本 |
| `reply_end` | 一段回复完成（含最终全文） |
| `reply_error` | 回复失败或为空 |
| `op_request` | 受控操作审批请求（`requestId` / `opType`: write\|edit\|shell / `agentName` / `target` / `detail`），等待 `POST /{id}/op-grant` 决定，期间该操作阻塞（最长 120 秒） |
| `image_start` / `image_end` | 文生图工具开始 / 结束（`generate_image` 执行窗口，前端据此显示生成动画；start 含 `agentName`，均携带 `conversationId`） |
| `conversation_created` | 编排者创建了项目群（含完整会话对象） |
| `coordination_start` / `coordination_end` | 编排协调状态开始 / 结束（按会话） |
| `discussion_start` / `discussion_end` | 自由讨论开始 / 结束（按会话） |
| `done` | 本轮全部回复结束 |

编排协作可能跨会话进行（建群协作、总结回单聊），`reply_start` 起的所有事件都携带 `conversationId`，客户端应按它路由消息。

## API 概览

| 前缀 | 说明 |
| --- | --- |
| `/api/user` | 当前用户（老板）信息 |
| `/api/agents` | 智能体 CRUD |
| `/api/model-presets` | 模型预设 CRUD |
| `/api/conversations` | 会话、消息、SSE 流式回复、会话文件授权（`/{id}/files`）、受控操作审批（`/{id}/op-grant`） |
| `/api/fs` | 图片内容读取（气泡缩略图数据源，只读） |
| `/api/asr/stream` | 实时语音转写 WebSocket（桥接讯飞流式听写） |
| `/api/settings` | 文件沙箱设置、实时语音识别配置（仅状态，不含密钥）、数据库快照导出 |

## 目录结构

```
src/main/java/com/guyu/agentteam/
├── config/                数据源/版本化迁移、本地令牌过滤、WebSocket 等配置
├── controller/            REST 与 SSE 端点
├── service/
│   ├── ChatStreamService        回复规则与流式回复
│   ├── ConversationStreamSupport  SSE 发送 / 持久化 / 分段流式（普通与编排共用）
│   ├── OpApprovalService        受控操作审批（卡片请求、阻塞等待与决定）
│   ├── AsrStreamService         实时语音识别配置与讯飞鉴权
│   ├── orchestration/
│   │   ├── OrchestrationService   编排协作循环与团队工具
│   │   └── AgentModelFactory      模型预设 → AgentScope 模型
│   └── tool/
│       ├── WorkspaceFileTools   文件工具与动态沙箱（多盘符路由 + 写入/修改门控）
│       ├── GatedShellTool       审批门控的终端命令工具
│       ├── ImageGenerationTools 文生图工具（按智能体图像预设注册）
│       ├── DashscopeImageAdapter / OpenAiImageAdapter / SiliconflowImageAdapter  文生图协议适配器
│       └── OpRequestSink        审批请求回调接口
├── ws/                    实时语音转写 WebSocket（讯飞桥接）
├── repository/            Spring Data JPA
└── entity / dto / common  实体、传输对象、公共层
src/main/resources/db/migration/  版本化迁移脚本（启动时自动执行）
```
