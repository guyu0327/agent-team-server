# 智群 AgentTeam · 服务端

AI 智能体团队系统的后端。基于 [AgentScope Java] 的 ReAct 循环实现真正的智能体编排：@项目经理提一个需求，他会自动拆解任务、把工作委派给程序员和测试、按需创建项目群，完成后把总结发回你的单聊。

配套前端：`agent-team-web`（Vue 3 类微信界面，见相关仓库）

## 核心机制

### 回复规则
- @谁谁回；没人被 @ 时全员依次回复（后一个能看到前一个的发言）
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
- 所有智能体（含普通单聊直答）都运行在 ReAct 循环上，可调用 `write_file` / `read_file` / `list_dir`
- 沙箱 = 主工作区目录 + 白名单目录 + 会话授权目录：相对路径解析到主工作区；绝对路径必须落在允许范围内，越界请求会返回错误说明供模型自行纠正
- 会话授权：用户在聊天输入框附加文件/文件夹后，该路径自动对本会话开放读写（`conversation_file_grants` 表）；编排者建群时会把发起会话的授权复制一份到项目群（两份副本互相独立），文件工具的作用域全程跟随当前协作目标会话，群内撤销立即生效
- 通过 `PUT /api/settings/workspace` 运行时修改全局沙箱，立即生效，重启不丢（持久化于 `app_settings` 表）
- 文件选择弹窗数据源：`GET /api/fs/list?path=` 服务端目录浏览（只读；path 为空返回盘符）

## 功能特性

- 用户 / 智能体 / 模型预设 / 会话 / 消息完整 REST API
- 单聊、群聊、群成员管理、解散、置顶、重命名、已读未读
- OpenAI 兼容模型接入：每个智能体可关联不同预设、独立温度与角色设定
- 编排者开关（`is_orchestrator`）：任意智能体可设为团队编排者
- 调试冒烟端点：`GET /api/debug/agent-smoke?agentId=xxx` 快速验证某个智能体的模型连通性

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 框架 | Spring Boot 4.1.1（Java 21） |
| 编排 | AgentScope Java 2.0.1（ReActAgent + Toolkit） |
| 模型接入 | agentscope-extensions-model-openai（OpenAI 兼容接口） |
| 数据 | Spring Data JPA + MySQL 8 |
| 其他 | Lombok、SseEmitter（SSE 流式推送） |

## 快速开始

环境要求：JDK 21、MySQL 8.0+。

1. 初始化数据库（按顺序执行 `db/` 下的脚本）：

```bash
mysql -uroot -p < db/agent_team.sql
```

2. 按需修改 `src/main/resources/application.yaml` 中的数据源配置（默认 `root/123456`，库 `agent_team`）。

3. 启动：

```bash
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

服务监听 `8080`。前端开发服务器会将 `/api` 代理到该端口。

### 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.datasource.*` | `localhost:3306/agent_team` | 数据库连接 |
| `app.workspace.root` | `./workspace` | 未在设置页自定义时的默认工作区目录 |

## SSE 事件协议

`POST /api/conversations/{id}/messages`，响应为 `text/event-stream`：

| 事件 | 说明 |
| --- | --- |
| `user_message` | 用户消息已持久化（含 `attachments` 附件元数据，无附件为空数组） |
| `reply_start` | 智能体开始回复（messageId / agentId / conversationId） |
| `delta` | 流式增量文本 |
| `reply_end` | 一段回复完成（含最终全文） |
| `reply_error` | 回复失败或为空 |
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
| `/api/conversations` | 会话、消息、SSE 流式回复、会话文件授权（`/{id}/files`） |
| `/api/fs` | 服务端目录浏览（文件选择弹窗数据源，只读） |
| `/api/settings` | 文件沙箱设置 |
| `/api/debug` | 调试端点（模型连通性冒烟） |

## 目录结构

```
src/main/java/com/guyu/agentteam/
├── controller/            REST 与 SSE 端点
├── service/
│   ├── ChatStreamService        回复规则与流式回复
│   ├── ConversationStreamSupport  SSE 发送 / 持久化 / 分段流式（普通与编排共用）
│   └── orchestration/
│       ├── OrchestrationService   编排协作循环与团队工具
│       ├── AgentModelFactory      模型预设 → AgentScope 模型
│       └── tool/WorkspaceFileTools 文件工具与动态沙箱
├── repository/            Spring Data JPA
├── entity / dto / common  实体、传输对象、公共层
db/                        建库与迁移脚本
```

## 相关仓库

- 前端界面 [agent-team-web]：Vue 3 + TypeScript + Pinia，类微信深色界面
