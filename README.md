# 智群 AgentTeam · 服务端

AI 智能体团队系统的后端。基于 [AgentScope Java] 的 ReAct 循环实现真正的智能体编排：@项目经理提一个需求，他会自动拆解任务、把工作委派给程序员和测试、按需创建项目群，完成后把总结发回你的单聊。

## 相关仓库

- 前端界面 [agent-team-web](https://github.com/guyu0327/agent-team-web)：Vue 3 + TypeScript + Pinia，聊天应用布局深色界面
- 桌面壳 [agent-team-desktop](https://github.com/guyu0327/agent-team-desktop)：Electron 打包分发，自动拉起本服务并管理数据目录与备份

## 核心机制

### 回复规则
- @谁谁回；没人被 @ 时全员依次回复（后一个能看到前一个的发言）
- 群聊回复（被动与自由讨论）统一接入 AgentScope 的 `OpenAIMultiAgentFormatter`：历史里的成员消息与用户消息都携带发言人名称，请求侧合并为带署名的 `<history>`，成员能分清「谁说了什么」，不会把其他成员的发言当成自己的历史；自由讨论的「轮到你发言」「选下一位发言人」等控制指令通过 `MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE` 标记保持为真实用户轮，不混入历史
- 编排者例外：被 @ 或在场时，由编排者单独运行协作循环，其他成员由他调度
- 群聊模式（`chat_mode`）：被动（默认，按上述规则）| 自由讨论。自由讨论仅对无编排者的群生效：首轮回复后由「主持人」模型逐轮选出下一位发言人接龙，直到主持人判定结束、达到总超时或用户终止（ReAct 迭代与接龙轮数不再设上限，时长完全由「协作限制」兜底）；`PUT /api/conversations/{id}/mode` 切换，建群时可通过 `chatMode` 直接指定

### 编排协作（透明协作）
编排者通过四个工具驱动团队：

| 工具 | 作用 |
| --- | --- |
| `list_team` | 查看可委派的成员及职责与能力（是否具备图像生成等） |
| `create_team` | 需要成员参与时（哪怕 1 个）先创建项目群，协作过程进群；编排者 + 成员 + 用户构成协作多方 |
| `delegate` | 委派子任务；成员用自己的人设和模型独立执行，输出作为真实消息流式展示；任务卡同时以编排者名义落库进群（「【委派任务卡 → 成员】」），委派了什么、题是什么在消息流直接可见 |
| `finish` | 提交最终总结，自动发回用户发起请求的单聊 |

- 协作全程透明：编排者的每段发言、成员的完整输出都是真实持久化的聊天消息
- 编排者发言按工具调用自动切分为多段气泡
- 编排者系统提示明确：受控操作被拒绝（如终端命令未获授权）时必须在群里说明情况与调整方案，不许默默跳过
- 编排者提前感知成员能力：`list_team` 返回成员职责与能力（是否具备图像生成），系统提示也注入成员能力清单，绘图任务只会委派给具备图像生成的成员
- 回复进行中可随时终止：`POST /api/conversations/{id}/stop`（覆盖普通回复、编排协作与自由讨论，直接掐断在途模型请求并按拒绝唤醒待审批请求，已产生的输出保留）
- 限制：ReAct 迭代不设上限；整体与单成员时长可在设置页配置（默认 60 / 5 分钟，持久化于 `app_settings` 的 `coordination.limits`），到时自动终止

### 文件工具（沙箱）
- 所有智能体（含普通单聊直答）都运行在 ReAct 循环上，文件能力来自 AgentScope harness 的 `FilesystemTool`：`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`，另有 `execute` 终端命令工具（Windows 下为 cmd）
- 沙箱 = 主工作区目录 + 白名单目录 + 会话授权目录：相对路径解析到主工作区；绝对路径必须落在允许范围内，越界请求会返回错误说明供模型自行纠正；Windows 多盘符按路径自动路由到对应文件系统
- 会话授权：用户在聊天输入框附加文件/文件夹后，该路径自动对本会话开放读写（`conversation_file_grants` 表）；编排者建群时会把发起会话的授权复制一份到项目群（两份副本互相独立），文件工具的作用域全程跟随当前协作目标会话，群内撤销立即生效
- 通过 `PUT /api/settings/workspace` 运行时修改全局沙箱，立即生效，重启不丢（持久化于 `app_settings` 表）
- 文件/目录选择：桌面壳调用系统资源管理器原生对话框，前端拿到真实路径后交给后端

### 受控操作审批（人审卡片）
- 写入（`write_file`）、修改（`edit_file`）与终端命令（`execute`）是受控操作：智能体发起时回复暂停，前端弹出审批卡片，展示发起智能体、操作类型、目标文件与完整内容/命令
- 三个决定：**允许一次** / **本会话允许**（持久化于 `operation_grants` 表，按会话+操作类型生效，同类操作后续不再询问，并立即放行该会话同类型的其他待审批请求）/ **拒绝**；卡片 120 秒未响应按拒绝处理
- 被拒或超时的操作不会执行，模型收到拒绝说明并被告知不要反复重试；会话删除或消息重置时自动清除本会话授权；终止协作/讨论或归档会话时按拒绝唤醒全部待审批请求
- 任务触发回合的自动放行：定时任务后台触发时没有人在审批卡片前，按任务级「后台自动放行」配置直接决定——终端命令按 `auto_shell`（默认不放行）、写入/修改按 `auto_write`（默认放行），未放行的操作立即按拒绝返回（不再空等 120 秒）；放行与拒绝均记入运行日志（「后台自动放行」/「后台未授权拒绝」）；编排者本人与被 `delegate` 成员的审批均跟随任务策略，对话里手动执行不受影响、仍走人审卡片（V10 迁移 `scheduled_tasks.auto_write` / `auto_shell`，任务表单可开关）
- 框架 `FilesystemTool` 没有删除工具，删除文件只能经 `execute` 命令完成，同样需要审批
- 决定接口：`POST /api/conversations/{id}/op-grant`（`requestId` + `decision: once|conversation|deny`）

### 会话归档（历史会话）
- `conversations.archived_at` 非空即视为已归档（历史会话），活跃列表与单聊复用查询一律排除，避免「恢复后又被旧会话顶掉」
- 归档入口：`POST /api/conversations/{id}/archive`（先中断进行中的回复/协作，再落归档标记；空会话直接物理删除，不产生空历史），适用于删除/解散/开始新会话等所有「聊天消失」操作
- 恢复：`POST /api/conversations/{id}/restore` 把会话拉回活跃列表；单聊冲突时，同智能体已有活跃单聊会先入历史（有消息）或被物理删除（空会话），保证同一智能体始终只有一个活跃单聊
- 归档只清置顶与未读，成员、文件授权、消息全部保留，恢复后可直接继续聊；彻底删除仍走 `DELETE /api/conversations/{id}`
- 历史列表：`GET /api/conversations?archived=true`，可选 `agentId` 过滤与其相关的会话（单聊命中或群聊含该成员）

### 上下文压缩（滚动摘要）
- 注入模型的历史有字符预算（默认 6 万，`app_settings` 的 `context.compression` 持久化，`GET/PUT /api/settings/context-compression` 读写）：回合开始时超预算则把水位线之后较旧的一段消息连同旧摘要一起，用回复智能体自己的模型压缩成一份完整新摘要，水位线推进；摘要拼进智能体 system prompt（「会话早期历史摘要」），近期消息保留原文
- 摘要与水位线持久化于 `conversations.context_digest` / `digest_watermark`（V5 迁移），重启不丢；单聊、群聊、编排协作、自由讨论共用同一条注入路径，自动生效
- 压缩失败（模型调用异常）自动降级为按预算纯截断（从最新往回取），只记日志不阻塞回复；最新一条消息（本轮用户消息）无论多大都保留
- 图片已阅：用户消息中的图片只在首次回复时注入视觉块，回合完整走完（未被终止）后自动标记 consumed（附件 JSON），后续轮次降级为文字注记「[图片·已阅]」，大幅降低视觉 token 消耗；图片文件与前端气泡展示不受影响
- `view_image` 工具：全部智能体可用，按路径把沙箱内（主工作区 + 白名单 + 会话授权）的图片重新注入为视觉块，用于重看已阅历史图片或查看工作区图片（png/jpg/jpeg/gif/webp/bmp，≤8MB）；压缩事件记入运行日志新类型 `compact`

### 智能体长期记忆（agent_memories）
- 基于 AgentScope 的 `LongTermMemory` 接口以 **AGENT_CONTROL** 模式接入：框架自动给智能体注册 `recordToMemory` / `retrieveFromMemory` 两个工具，记什么、何时取回完全由智能体在 ReAct 循环中自行决定（用户分享偏好、背景、重要事实或明确要求记住时写入）
- 存储为 SQLite `agent_memories` 表（V6 迁移），按智能体隔离、跨会话共享；单聊、群成员、编排协作（含受委派成员）全部生效
- 每轮回复系统提示注入当前记忆清单（时间正序，预算 6000 字符），智能体无需主动检索即可「记得」用户；`retrieveFromMemory` 按查询词过滤返回（命中部分限 8000 字符预算、从最新往回取），无查询词回退全部、全部不命中回退最新几条，避免检索空手而归或全量回注撑爆小上下文模型
- 上限：单智能体 300 条（超出裁最旧）、单条 2000 字符；删除智能体时级联清空其记忆
- 查看/清空：`GET /api/agents/{id}/memories`、`DELETE /api/agents/{id}/memories`

### 运行日志（app_logs）
- 关键事件落库，便于排查问题：`error` / `op_request` / `op_decision` / `coordination` / `discussion` / `image` / `compact` / `api_error`
- 写入经内存队列异步批量落库（业务线程只入队），避免 SQLite 单连接被日志阻塞；队列满丢弃新日志并限流告警
- 保留 30 天，批量写入后低频触发过期清理；查询走 `GET /api/logs`，支持 `type` / `from` / `to` / 分页，前端「设置-数据管理-查看日志」即基于此

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

### 定时任务（scheduled_tasks）
- 任务模型：名称 + 内容 + 触发方式（`once` 单次 runAt / `daily` 每天 timeOfDay / `weekly` 每周 timeOfDay+daysOfWeek（1=周一…7=周日）/ `interval` 每 intervalMinutes 分钟），不引入 cron，字段结构化由后端校验（V7 迁移）；任务类型 `mode`：`normal` 普通（智能体独立执行，绑定其任务线程）/ `collab` 协作（编排者执行并拉非编排者成员建任务项目群，成员必选，V9 迁移）；`catch_up` 开关控制错过的单次任务启动后 24h 内是否补发，默认不补发（补发关闭时错过的任务直接转 done 并记日志）
- 会话绑定：无协作成员 → 智能体专属任务线程（`category=task` 单聊，同智能体多任务共用一条，消息带 `task_id`/`task_name` 打标供筛选）；有协作成员 → 任务项目群（`category=task` 群聊「任务：名称」，触发时成员一起执行）；任务全部删光的会话不再出现在任务页
- 触发管线：到点向绑定会话注入合成用户消息（前缀【定时任务触发·名称】，正文明确「这是已有任务的自动触发，直接执行、勿再创建」，并澄清「以任务卡形式委派」等说法指把卡片内容作为 `delegate` 参数传给成员而非聊天文本输出，落库打任务标），走 `ChatStreamService.stream()` 全管线（回复 / 协作 / 落库照旧）；触发回合内 `schedule_task` 工具直接拒绝（防把触发误当新请求循环建任务），同名同内容的重复创建也被后端拦截；上一轮未结束自动顺延 1 分钟；一次性任务触发后转 `done`，周期任务算下一次
- 触发轮纠错：任务触发的编排协作轮若没有实际执行证据（纯文本收场、只调用了被拒绝或只读的工具、或协作任务未委派成员自己包揽），自动追加一条带任务标的纠正指令用户消息重试（「请立即调用 delegate / 文件工具实际执行」），并记运行日志 `task`；每个触发轮最多纠错重试两次（首次 + 两次纠正共三轮），被终止或出错的回合不重试
- 实时可见：任务触发的回合用 `Broadcast` 扇出发射器，`GET /api/conversations/{id}/events`（SSE，令牌走 `token` 查询参数）常驻订阅后推给正在查看的前端；用户主动发消息仍走 POST 响应流，不重复
- 错过补发：应用启动时恢复任务（`ApplicationReadyEvent`），开启 `catch_up` 的错过一次性任务 24 小时内补发（消息标注「错过补发」），未开启默认不补发直接转 `done` 并记日志；周期任务只重排未来
- AI 工具：智能体在对话中可用 `schedule_task` / `update_task` / `list_tasks` / `cancel_task` 为用户安排或调整任务；系统提示与工具描述明确：周期性/定时需求一律走 `schedule_task`（需要成员参与传 `member_ids` 建协作任务群），不得用建普通群聊代替；用户侧走 REST（见 API 概览），日志类型 `task`
- 删除归档与恢复：删除任务（单个或批量）后其消息整批移入归档会话（`category=task` + `archived_at`，历史页以「定时任务」标签展示），任务配置以 JSON 快照存进 `conversations.task_snapshot`（V8 迁移）；`POST /api/tasks/restore/{conversationId}` 按快照走创建流程重建任务（once 已过期顺延 1 分钟执行），归档消息随之搬回任务线程挂到新任务名下、归档会话删除（历史页不再显示）
- 立即执行：`POST /api/tasks/{taskId}/run` 手动触发与到点相同的执行流程（会话忙碌直接报错不排队）；周期任务的下一次排期不受影响，暂停中的任务执行后保持暂停（不产生排期）

### 微信通道（iLink Bot）
- 扫码接入：`POST /api/wechat/login` 取二维码并长轮询扫码状态（支持配对码验证、二维码过期自动刷新、已绑定他实例检测），登录颁发的 bot_token 存于 `app_settings`（key=`wechat.bot`）；通道开关与参数持久化于 `wechat.channel`，`GET/PUT /api/wechat/settings` 读写——处理智能体（空 = 编排者优先）、回复长度上限（200–20000，默认 2000）、回合超时（1–60 分钟，默认 10）、后台自动放行（写改文件 / 终端命令，默认均不放行）
- 收消息：后台守护线程对 `getupdates` 长轮询（`get_updates_buf` 断点续传 + message_id 去重兜底）；`wechat_bindings` 表（V11 迁移）把微信发送者绑定到系统单聊（`conversations.channel=wechat`，V12 迁移），绑定持久保留，断开重连后消息继续进原会话；-14 会话超时需重新扫码
- 回复管线：收到的文本走 `ChatStreamService.stream()` 全管线（后台回合：审批按通道配置自动放行，决定记运行日志），聚合本轮全部智能体文本经 `sendmessage` 推回微信（回传 context_token，超长按上限截断）；上一轮未结束自动等空闲，回合异常/空回复也有明确提示回传
- 智能体切换：通道配置的处理智能体即时生效——已有绑定会话的成员随之替换，无需重新绑定
- 媒体消息（对照官方 openclaw-weixin 协议）：图片/文件/视频经微信 CDN 下载并 AES-128-ECB/PKCS7 解密（`full_url` 优先，密钥兼容 base64 原始与十六进制两种编码，≤50MB），存入主工作区「微信接收/」；图片以图片附件进入管线（模型直接注入视觉块），文件/视频作为文件附件供文件工具读写；语音优先使用微信侧转写文本当文字处理，无转写则回复暂不支持；下载/解密失败回复失败说明并记日志
- 实时可见：与定时任务触发的回合一样经 `Broadcast` 扇出 SSE（`GET /api/conversations/{id}/events`），正在查看该会话的前端实时渲染流式输出；媒体落盘在沙箱内，`view_image` 与文件工具可直接访问

### 实时语音转写（讯飞流式听写）
- WebSocket 端点 `/api/asr/stream`：前端发二进制 16k PCM 音频与 `{"type":"stop"}` 控制帧；后端按讯飞节奏（40ms / 1280B 一帧，base64）装帧转发到 `wss://iat-api.xfyun.cn/v2/iat`，识别结果转为 `{type:partial|final|error|end}` JSON 回传
- 每条前端连接一个 `SessionBridge`：队列缓冲（满丢最旧保延迟）、发送链串行化、60 秒上限自动收尾、stop 后排空残余等 final 再关，所有清理路径收敛到幂等 `shutdown()`
- 鉴权 URL 每次连接即时生成（HmacSHA256 签名，RFC1123 GMT date，本机时钟偏差需在 5 分钟内）；错误码透传中文提示（10105 鉴权、11200 免费次数用尽等）
- 配置持久化于 `app_settings`（key=`asr.streamConfig`），经 `GET/PUT /api/settings/asr-stream` 读写；三项全部留空保存则清除配置

## 功能特性

- 用户 / 智能体 / 模型预设 / 会话 / 消息完整 REST API
- 单聊、群聊、群成员管理、解散、置顶、重命名、已读未读；删除/解散/重置统一下沉为会话归档（历史会话），可恢复或彻底删除
- OpenAI 兼容模型接入：每个智能体可关联不同预设、独立温度与角色设定
- 文生图：预设按协议分类（对话 / DashScope 文生图 / OpenAI Images 文生图），智能体绑定图像预设即获得 `generate_image` 工具，图片落工作区并在气泡中展示
- 编排者开关（`is_orchestrator`）：任意智能体可设为团队编排者，且能感知成员的绘图能力
- 受控操作审批：AI 写入、修改文件与执行终端命令前弹卡片询问用户，支持允许一次 / 本会话允许（批量放行同类待审批）/ 拒绝
- 上下文压缩：历史超预算自动滚动摘要（设置页可调预算与开关），图片首轮看完即降级为文字注记，`view_image` 工具按需重看
- 运行日志：协作/讨论/审批/生图/压缩/错误等关键事件异步落库，可按类型与时间范围查询
- 智能体长期记忆：框架 AGENT_CONTROL 模式，智能体自己决定记住什么，SQLite 落库、跨会话生效，可查看与清空
- 定时任务：单次 / 每天 / 每周 / 固定间隔四种触发，独立任务会话（线程或项目群）不污染普通聊天，用户与智能体均可增删改查，错过的单次任务 24h 内补发；后台触发回合按任务配置自动放行受控操作（写改默认放行、命令默认不放行），触发轮只输出文字未实际执行时自动纠错重试（最多两次）
- 微信通道：扫码接入微信 iLink Bot，手机微信直接与绑定智能体单聊；图片/文件/视频可处理、语音用微信转写文本；桌面端对应会话只读展示，回复实时可见

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
| `reply_pending` | 一位成员即将回复（agentId / conversationId）：模型思考窗口（含接龙/依次回复间隔期）先行预告，首个非空增量才发 `reply_start`，前端据此显示「谁在思考」 |
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

定时任务与微信通道触发的后台回合没有 POST 请求方，事件经 `Broadcast` 扇出到 `GET /api/conversations/{id}/events`（同协议常驻 SSE 订阅，令牌走 `token` 查询参数），同一会话两类流互不重复。

## API 概览

| 前缀 | 说明 |
| --- | --- |
| `/api/user` | 当前用户（老板）信息 |
| `/api/agents` | 智能体 CRUD、长期记忆查看/清空（`/{id}/memories`） |
| `/api/model-presets` | 模型预设 CRUD |
| `/api/conversations` | 会话、消息、SSE 流式回复、会话文件授权（`/{id}/files`）、受控操作审批（`/{id}/op-grant`）、历史会话归档/恢复（`/{id}/archive`、`/{id}/restore`） |
| `/api/tasks` | 定时任务 CRUD（`GET` 按会话分组、`POST` 创建、`PUT /{taskId}` 更新含暂停恢复、`DELETE /{taskId}`）、`/{taskId}/run` 立即执行一次、`/conversation/{id}` 会话任务列表、`/conversations` 任务会话列表（仅还剩任务的）、`/restore/{conversationId}` 从历史归档恢复任务 |
| `/api/fs` | 图片内容读取（气泡缩略图数据源，只读） |
| `/api/asr/stream` | 实时语音转写 WebSocket（桥接讯飞流式听写） |
| `/api/wechat` | 微信 iLink 通道：连接状态（`GET /status`）、扫码登录（`POST /login` 发起、`GET /login/status` 轮询、`POST /login/verify` 配对码、`POST /login/cancel` 取消）、断开（`POST /disconnect`）、通道设置（`GET/PUT /settings`） |
| `/api/settings` | 文件沙箱设置、实时语音识别配置（仅状态，不含密钥）、协作时长限制、上下文压缩（预算/开关）、数据库快照导出 |
| `/api/logs` | 运行日志查询（按类型与时间范围分页） |

## 目录结构

```
src/main/java/com/guyu/agentteam/
├── config/                数据源/版本化迁移、本地令牌过滤、WebSocket 等配置
├── controller/            REST 与 SSE 端点
├── service/
│   ├── ChatStreamService        回复规则与流式回复
│   ├── ConversationStreamSupport  SSE 发送 / 持久化 / 分段流式（普通与编排共用）
│   ├── OpApprovalService        受控操作审批（卡片请求、阻塞等待、批量放行与取消）
│   ├── AppLogService            运行日志异步落库与过期清理
│   ├── CoordinationLimitsService 协作/讨论时长限制（app_settings 持久化）
│   ├── ScheduledTaskService      定时任务（CRUD/调度/恢复补发/触发，TaskScheduler + 句柄表）
│   ├── wechat/                  微信 iLink 通道（扫码登录/长轮询收发/CDN 媒体下载解密/会话绑定）
│   ├── AsrStreamService         实时语音识别配置与讯飞鉴权
│   ├── orchestration/
│   │   ├── OrchestrationService   编排协作循环与团队工具
│   │   └── AgentModelFactory      模型预设 → AgentScope 模型
│   └── tool/
│       ├── WorkspaceFileTools   文件工具与动态沙箱（多盘符路由 + 写入/修改门控）
│       ├── GatedShellTool       审批门控的终端命令工具
│       ├── ImageGenerationTools 文生图工具（按智能体图像预设注册）
│       ├── ScheduledTaskTools   定时任务工具（schedule/update/list/cancel_task）
│       ├── AbstractImageAdapter  文生图适配器基类（公共下载/落盘/尺寸逻辑）
│       ├── DashscopeImageAdapter / OpenAiImageAdapter / SiliconflowImageAdapter  文生图协议适配器
│       └── OpRequestSink        审批请求回调接口
├── ws/                    实时语音转写 WebSocket（讯飞桥接）
├── repository/            Spring Data JPA
└── entity / dto / common  实体、传输对象、公共层
src/main/resources/db/migration/  版本化迁移脚本（启动时自动执行）
```
