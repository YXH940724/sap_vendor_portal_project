---
name: supplier-collaboration-agent
description: 维护 SAP 供应商协同 Agent 的运行时 Skill 与 MCP 工具目录。适用于新增或调整订单、ASN、收货和预制发票的 AI 能力。
---

# SAP 供应商协同 Agent 维护规范

## 可独立维护的运行时文件

- Agent 行为、边界和回答规范：`src/main/resources/ai/skills/supplier-collaboration-agent.md`
- MCP 工具声明（模型可见名称、说明、参数 JSON Schema）：`src/main/resources/ai/mcp-tools.json`。当前目录包含订单、收货凭证、ASN、结算组合查询，以及 ASN / 预制发票草稿工具。
- 后端受控工具处理器：`src/main/java/com/yxh/sapvendorportal/agent/SupplierCollaborationAgent.java`

## 修改流程

1. 先调整 Skill 文件，使权限边界、工具使用条件和用户可见说明一致。
2. 仅修改已有工具的说明或参数时，更新 `mcp-tools.json` 并保持 JSON Schema 有效。
3. 新增工具必须同时实现服务端受控处理器、供应商隔离、输入校验与测试；禁止仅在工具目录中登记一个尚无后端处理器的工具。
4. 写入 SAP 的能力必须采用“生成草稿—用户确认—服务端二次校验”的模式；不得让模型直接提交业务单据。
5. 运行 `mvn test` 验证资源可被加载，然后重启服务使打包资源生效。

## 数据口径同步

- 订单与交期来自采购订单 API；收货凭证来自物料凭证 API；ASN 状态来自 ASN API；发票事实来自供应商发票 API。
- 收货进度、可发运量与结算状态是后端组合计算，不能仅靠提示词推断。变更公式时，同步调整 `PortalController`、Agent 工具说明、运行时 Skill 与单元测试。
- 当前可发运量口径为：订单数量 - 已收货数量 - 未清 ASN 数量；已收货数量按 `101 - 102 + 123 - 122` 汇总，未清 ASN 数量为创建 ASN 累计量扣减已收货数量后取零以上。

## 安全约束

- 不在 Skill、工具目录、日志或前端中记录 SAP 或大模型密钥。
- 所有业务查询和动作必须继承当前登录供应商的数据范围。
- 不向模型提供超出完成任务所需的原始敏感字段。
