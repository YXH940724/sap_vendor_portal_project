# SAP 供应商协同 Portal（Java）

面向 SAP PE/ES 的轻量级供应商 Portal 初版，后端使用 **Java 21 + Spring Boot 3**。它以 **SAP 标准 OData API** 为业务数据源，覆盖供应商主数据、采购订单、ASN、物料凭证、发票/结算对账的实时查询入口。

## 架构原则

- **无 Portal 业务数据库**：不复制 SAP 主数据、订单、收货或结算数据。
- **凭据不出服务端**：SAP 用户名、密码或 Bearer Token 仅由 Java 服务端从 `.env` 或部署环境读取；浏览器永远不直接访问 SAP。
- **供应商范围强制隔离**：后端为每个请求强制注入供应商过滤条件，缺少实体集或供应商字段配置时直接拒绝调用。
- **身份与权限外置**：生产环境应由企业 IAM/CIAM 使用 OIDC/SAML 完成账号、密码、MFA 和权限管理。Portal 不保存密码、权限表或 MFA 密钥。

## 本地启动

```bash
cp .env.example .env
# 在 .env 中填写 SAP_USERNAME、SAP_PASSWORD、PORTAL_VENDOR_ID 和 DEEPSEEK_API_KEY
mvn spring-boot:run
```

访问 <http://localhost:3000>。

> 请勿将 `.env`、SAP 凭据、Token 或身份代理密钥提交到 Git。

## 供应商协同 AI 助手

工作台右下角提供“供应商协同助手”。后端通过 DeepSeek Chat Completions 的 Function Calling 调用受控业务工具，支持当前供应商范围内的采购订单、ASN/发运、收货结算查询，以及 ASN/预制发票草稿依据生成。

- 配置：在 `.env` 设置 `DEEPSEEK_API_KEY`；可选设置 `DEEPSEEK_MODEL`（默认 `deepseek-v4-flash`）和 `DEEPSEEK_BASE_URL`。
- 模型说明：DeepSeek 的 `deepseek-chat` 曾指向 V3，但官方已公告该旧别名于 2026-07-24 停止服务；因此默认使用支持 Tool Calls 的 `deepseek-v4-flash`。如企业 DeepSeek 网关仍提供 V3 兼容模型，可仅覆盖 `DEEPSEEK_MODEL`，无需修改代码。
- 边界：大模型只能理解问题、选择工具和组织回答；数量、金额、状态和供应商隔离均由 Java 服务端按 SAP 实时数据校验。
- 写入：AI 仅生成草稿依据，ASN 与预制发票仍通过 Portal 表单由用户确认后提交，并执行既有服务端校验。
- 安全：浏览器不接触 DeepSeek 或 SAP 密钥；`vendorId` 从服务端登录范围注入，模型与前端均不能覆盖。
- 独立维护：运行时行为规则位于 `src/main/resources/ai/skills/supplier-collaboration-agent.md`，MCP 工具目录位于 `src/main/resources/ai/mcp-tools.json`；维护说明位于 `.codex/skills/supplier-collaboration-agent/SKILL.md`。调整后运行 `mvn test` 并重启服务。新增工具仍必须同步实现受控后端处理器和测试，不能仅修改 JSON 声明。

## 配置 SAP OData

`.env.example` 已写入本次提供的 5 个服务根地址；标准实体集和供应商隔离字段由 Portal 固定处理，无需逐项配置。创建 ASN 前，Portal 会读取 SAP `$metadata`，确认目标实体集存在且允许创建。

如租户确认写入实体集不是 `A_InbDeliveryHeader`，可额外设置 `SAP_ASN_CREATE_PATH`；如供应商字段不是 `Supplier`，可设置 `SAP_ASN_CREATE_VENDOR_FIELD`。这两个配置仅用于租户扩展，不影响默认标准 API。

## 飞书多维表格账号与权限管理

当前项目支持将飞书多维表格作为轻量授权中心。已在指定 Base `Vendor Portal Authorizaiton Configuration` 内创建 **Portal 登录授权** 表（表 ID：`tblmCxjQzBxZpIDi`）。每一行代表一个可登录供应商账号及其唯一供应商范围。

启用方式：

```bash
PORTAL_AUTH_MODE=lark_bitable
LARK_APP_ID=cli_xxx
LARK_APP_SECRET=***
LARK_BITABLE_APP_TOKEN=Ta7pbhUwGakUgXsZMHEcuHprnpc
LARK_BITABLE_LOGIN_TABLE_ID=tblmCxjQzBxZpIDi
PORTAL_SESSION_SECRET=使用高强度随机字符串
```

飞书应用需使用**应用身份**具备目标多维表格的读取权限。Portal 仅由服务端调用飞书 OpenAPI，浏览器不会取得飞书应用密钥或 Base 数据。

在“Portal 登录授权”表新增账号时，维护：`登录账号`、`密码哈希`、`供应商编码`、`状态=启用`，并按需维护采购组织、公司代码、工厂与权限。密码哈希必须是 BCrypt 格式（以 `$2a$`、`$2b$` 或 `$2y$` 开头），禁止填写明文密码。管理员可在本地生成哈希（命令执行后只输出哈希，不保存密码）：

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.yxh.sapvendorportal.common.utils.PasswordHashTool \
  -Dexec.args='请替换为实际密码'
```

账号密码、哈希和应用密钥都不应提交到 Git。

可选权限包括：`ORDER_READ`、`ASN_READ`、`ASN_CREATE`、`GOODS_RECEIPT_READ`、`SETTLEMENT_READ`、`INVOICE_CREATE`、`PRINT`、`SUPPLIER_PROFILE_READ`、`AI_QUERY`。登录后，Portal 以 HttpOnly、SameSite=Strict 会话 Cookie 绑定该账号，所有 SAP 查询强制使用授权记录中的供应商编码；未授予的页面、ASN 创建、预制发票创建与 AI 查询会被服务端拒绝。

## 企业身份代理模式（可选）

Portal 默认且推荐使用飞书多维表格登录。已取消 `single_vendor` 固定供应商模式，浏览器访问必须先完成供应商登录，供应商范围仅来自已登录账号的授权记录。企业如已具备 IAM/CIAM，可改为身份代理模式：

```text
供应商 → 企业 IAM/CIAM（登录/MFA） → 身份代理（签名供应商范围） → Portal → SAP OData
```

设置 `PORTAL_AUTH_MODE=proxy_hmac` 与 `PORTAL_IDENTITY_HMAC_SECRET` 后，身份代理需要在每个请求加入：

- `X-Portal-Vendor-Id`
- `X-Portal-Timestamp`（毫秒时间戳，5 分钟有效）
- `X-Portal-Signature`：`HMAC-SHA256(vendorId.timestamp)`

实际项目中应由 IAM 权益管理系统维护“用户—LIFNR—采购组织/公司代码/工厂”映射，身份代理校验 JWT 后生成上述签名请求头。Portal 仅校验身份代理签名并把范围用于 OData 过滤。

## 已实现与后续工作

- 已实现：Java Spring Boot 服务端 OData 代理、V2/V4 响应兼容、供应商范围过滤、防止未配置范围时全量查询、供应商/PO/ASN/物料凭证/发票页面，以及 ASN 写接口的 CSRF 和 `$metadata` 预检流程。
- 后续需 SAP 团队确认：各服务的业务字段、GR/IR 与付款状态服务、ASN 创建权限及发运字段映射、错误码与分页策略。

## 验证

```bash
mvn test
```
