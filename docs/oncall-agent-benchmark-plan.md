# OnCall Agent Benchmark And Load Test Plan

本文档是 OnCall 智能运维 Agent 的评测与压测执行计划。目标是围绕简历中的项目贡献逐条验证，并产出可写入简历的量化指标。

说明：本文中的数字为预期目标或模拟示例，最终简历应替换为实际压测结果。

## 1. Agent 架构设计测试

### 简历待验证内容

Agent 架构设计：统一 DashScope 模型接入、5 类本地 Java 工具、MCP 外部工具、ReAct 自动调用、会话记忆和 SSE 流式输出流程；支持普通问答 Agent 与 Planner-Executor-Supervisor 三 Agent 协作模式，形成从告警读取、任务规划、工具执行到报告生成的闭环分析链路。

### 准备内容

- 启动 Spring Boot 项目。
- 配置 DashScope API Key。
- 启动或 Mock Prometheus。
- 启用 `cls.mock-enabled=true`，保证日志工具可用。
- 准备 6 类标准问题：
  - 当前时间查询。
  - 当前 Prometheus 告警查询。
  - 内部文档查询。
  - 日志查询。
  - `/api/chat_stream` 流式问答。
  - `/api/ai_ops` 多 Agent 告警分析。

### 测试内容

- `/api/chat` 是否能正常回答。
- `/api/chat` 是否会自动调用本地 Java Tool。
- `/api/chat_stream` 是否能通过 SSE 流式返回。
- `/api/ai_ops` 是否能启动 Supervisor 多 Agent 流程。
- 日志里是否能看到工具注册、工具调用完成和 Agent 执行状态。
- AIOps 是否能生成最终 Markdown 报告。

### 预期结果

- 普通问答接口成功率大于 95%。
- 流式接口完成率大于 95%。
- AIOps 报告生成成功率大于 80%。
- 本地工具调用成功率大于 90%。
- MCP 或 Mock 日志工具调用成功率大于 90%。

### 可写入简历的结果示例

在 120 次标准任务回放中，普通问答 Agent 成功率 96.7%，AIOps 多 Agent 报告生成成功率 85.8%，平均 `tool_steps=2.4`。

## 2. 长上下文治理测试

### 简历待验证内容

长上下文治理：将固定 6 轮历史窗口升级为分层记忆机制，按 `recentMessages` / `summaries` / `facts` / `openTasks` 管理上下文；基于轮数和 token 阈值异步压缩旧对话，仅保留最近 3 轮原文，并按相关性检索长期事实后注入 Prompt，降低长对话上下文膨胀与无关历史干扰。

### 准备内容

- 准备 12 组长对话脚本。
- 每组 12 到 20 轮。
- 每组包含明确 OnCall 实体：
  - `serviceName`: `order-service`
  - `region`: `ap-guangzhou`
  - `alertName`: `HighMemoryUsage`
  - `metricName`: `jvm_memory_used`
- 每组对话固定使用同一个 `sessionId`。
- 通过 `/api/chat/session/{sessionId}` 记录压缩前后的状态。

### 测试内容

- 对话超过 8 轮后是否触发压缩。
- 压缩后 `recentMessages` 是否只保留最近 3 轮，即 6 条消息。
- `summaries` 是否生成历史摘要。
- `facts` 是否提取出 service、region、alert 等长期事实。
- `openTasks` 是否提取出未完成排查任务。
- Prompt token 是否下降。

### 预期结果

- 12 组长对话全部触发压缩。
- 最近 3 轮原文保留率 100%。
- summary 生成成功率 100%。
- facts 提取成功率大于 80%。
- 平均 Prompt token 下降 25% 到 40%。

### 可写入简历的结果示例

在 12 组长对话评测中，将平均 Prompt token 从 6,900 降至 4,850，下降 29.7%，最近 3 轮原文保留率 100%。

## 3. 记忆检索与压缩测试

### 简历待验证内容

记忆检索与压缩：设计 JSON + Milvus 的长期记忆存储与检索链路，JSON 作为事实来源，Milvus 作为语义索引；结合向量相似度、关键词实体匹配和时间新鲜度进行混合排序，提高相关记忆命中率并减少无关记忆注入。

### 准备内容

- 准备 60 条长期事实。
- 按主题分组：
  - `order-service` 内存告警。
  - `payment-service` 慢响应。
  - `user-service` 服务不可用。
  - 磁盘高使用率告警。
  - CPU 高使用率告警。
- 将事实写入对应 session。
- 准备 20 个查询问题。
- 人工标注每个问题应该命中的 facts。

### 测试内容

- 查询 `order-service` 时是否召回 `order-service` 相关 facts。
- 查询 `ap-guangzhou` 时是否召回对应 region facts。
- 查询 `HighMemoryUsage` 时是否召回内存告警 facts。
- Top-5 中相关 fact 占比。
- 是否过滤低分无关记忆。
- Milvus 不可用时是否降级到关键词检索。

### 预期结果

- Top-5 相关记忆命中率大于 80%。
- 无关记忆注入率下降大于 30%。
- Milvus 不可用时，基本聊天不受影响。
- 关键词降级检索仍可返回相关记忆。

### 可写入简历的结果示例

在 60 条长期记忆与 20 个查询问题上，Top-5 相关记忆命中率达到 86.7%，无关记忆注入率下降 41.2%。

## 4. 工具安全与运行治理测试

### 简历待验证内容

工具安全与运行治理：统一收敛本地 Java Tool 与 MCP 外部工具注册入口，结合 `@ToolParam` 参数描述、Prompt 级工具调用规则、Mock/真实环境隔离、工具异常结构化返回和运行日志观测控制工具执行；在 AIOps 编排 Prompt 中约束工具失败处理与报告真实性，降低误调用和幻觉风险。

### 准备内容

- 准备 5 类本地工具测试问题：
  - 时间查询。
  - 文档查询。
  - Prometheus 告警查询。
  - AIOps 技能查询。
  - 日志查询。
- 准备异常场景：
  - Prometheus 地址不可用。
  - Milvus 停止。
  - 日志工具返回空结果。
  - 查询参数缺失。
- 打开应用日志，记录工具调用情况。

### 测试内容

- 模型是否调用正确工具。
- 工具参数是否合理，例如 `region=ap-guangzhou`。
- 工具失败时是否返回结构化错误。
- 工具失败后接口是否还能正常返回。
- AIOps 报告是否说明失败原因，而不是编造结果。

### 预期结果

- 工具调用成功率大于 90%。
- 错误结构化返回率大于 90%。
- 工具失败后接口可用率 100%。
- 明显错误工具调用率小于 10%。

### 可写入简历的结果示例

在 80 次工具调用评测中，工具调用成功率达到 92.5%，异常场景结构化返回率达到 95%，工具失败后主流程可用率 100%。

## 5. AIOps 多 Agent 编排测试

### 简历待验证内容

AIOps 多 Agent 编排：设计 Planner-Executor-Supervisor 协作流程，由 Supervisor 调度 Planner 拆解任务、Executor 执行工具查询并反馈证据，Planner 基于反馈再规划并生成最终报告。

### 准备内容

- 准备 6 类 OnCall 标准任务：
  - 活跃告警查询。
  - CPU 高使用率。
  - 内存高使用率。
  - 磁盘高使用率。
  - 服务不可用。
  - 慢响应。
- 每类任务跑 20 次，总计 120 次。
- 准备最终报告合格标准：
  - 有活跃告警清单。
  - 有根因分析。
  - 有处理建议。
  - 有结论。
  - 有工具证据。

### 测试内容

- `/api/ai_ops` 是否成功返回报告。
- Supervisor 是否先调用 Planner。
- 是否出现 Planner -> Executor -> Planner 的闭环。
- 最终报告格式是否合格。
- 是否出现无证据编造。
- 平均 `attempts` 和 `tool_steps`。

### 预期结果

- AIOps `pass_rate` 大于 80%。
- 最终报告格式合格率大于 90%。
- 平均 `attempts` 在 2 到 4 之间。
- 平均 `tool_steps` 在 2 到 3 之间。
- 无证据编造率小于 10%。

### 可写入简历的结果示例

在 6 类 OnCall 标准任务、120 次任务回放中，AIOps `pass_rate` 达到 85.8%，最终报告格式合格率 91.7%，平均 `attempts=2.8`，平均 `tool_steps=2.4`。

## 6. 评测与压测体系测试

### 简历待验证内容

评测与压测体系：构建覆盖 6 类 OnCall 场景的 benchmark 与压测脚本，自动汇总 `pass_rate`、`attempts`、`tool_steps`、`failure_category`、`latency` 与 `trace`；支持任务回放、失败归因和回归对比。

### 准备内容

- 准备 benchmark 脚本。
- 准备固定任务集。
- 每次请求记录以下字段：
  - `taskId`
  - `sessionId`
  - `endpoint`
  - `latencyMs`
  - `success`
  - `pass`
  - `toolSteps`
  - `attempts`
  - `failureCategory`
  - `trace`
- 输出 JSON 或 CSV 报告。

### 测试内容

- 120 次任务能否自动跑完。
- 是否能自动计算 `pass_rate`。
- 是否能统计 P50 / P95 / P99 延迟。
- 是否能统计失败原因分类。
- 是否能保存每次运行 trace。
- 第二次运行能否和第一次结果对比。

### 预期结果

- benchmark 自动执行率 100%。
- 结果文件生成成功率 100%。
- `pass_rate` / `latency` / `tool_steps` 自动统计成功。
- 失败能分类到固定 `failure_category`。

### 可写入简历的结果示例

构建覆盖 6 类 OnCall 场景的 benchmark 与压测脚本，自动汇总 `pass_rate`、`attempts`、`tool_steps`、`failure_category`、`latency` 与 `trace`；完成 120 次任务回放和 30 分钟混合流量压测，P95 延迟 18.6s，错误率 2.1%。

## 7. 推荐最终汇总指标

最终压测完成后，建议整理为如下表格：

| 指标 | 示例结果 |
| --- | --- |
| 标准任务类别 | 6 类 |
| 任务回放次数 | 120 次 |
| AIOps pass_rate | 85.8% |
| 普通问答成功率 | 96.7% |
| 最终报告格式合格率 | 91.7% |
| 平均 attempts | 2.8 |
| 平均 tool_steps | 2.4 |
| P95 latency | 18.6s |
| 工具调用成功率 | 92.5% |
| 工具异常结构化返回率 | 95.0% |
| 平均 Prompt token 降幅 | 29.7% |
| Top-5 相关记忆命中率 | 86.7% |
| Session 串记忆次数 | 0 |

## 8. 最终简历表述模板

```text
围绕 OnCall Agent 构建标准化 benchmark 与压测体系，按 Agent 架构、长上下文治理、记忆检索、工具调用、AIOps 多 Agent 编排 5 条链路设计评测任务；在 6 类标准 OnCall 场景、120 次任务回放中取得 85.8% pass_rate，平均 attempts=2.8、tool_steps=2.4，最终报告格式合格率 91.7%；在 12 组长对话评测中将平均 Prompt token 降低 29.7%，Top-5 相关记忆命中率达到 86.7%。
```

