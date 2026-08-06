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
# 在 .env 中填写 SAP_USERNAME、SAP_PASSWORD、PORTAL_VENDOR_ID 和 ZHIPU_API_KEY
mvn spring-boot:run
```

访问 <http://localhost:3000>。

> 请勿将 `.env`、SAP 凭据、Token 或身份代理密钥提交到 Git。

## 供应商协同 AI 助手

工作台右下角提供“供应商协同助手”。后端通过智谱对话补全 API 的 Function Calling 调用受控业务工具，支持当前供应商范围内的采购订单、ASN/发运、收货结算查询，以及 ASN/预制发票草稿依据生成。

- 配置：在 `.env` 设置 `ZHIPU_API_KEY`；可选设置 `ZHIPU_MODEL`（默认 `glm-4.7`）和 `ZHIPU_BASE_URL`。
- 边界：大模型只能理解问题、选择工具和组织回答；数量、金额、状态和供应商隔离均由 Java 服务端按 SAP 实时数据校验。
- 写入：AI 仅生成草稿依据，ASN 与预制发票仍通过 Portal 表单由用户确认后提交，并执行既有服务端校验。
- 安全：浏览器不接触智谱或 SAP 密钥；`vendorId` 从服务端登录范围注入，模型与前端均不能覆盖。

## 配置 SAP OData

`.env.example` 已写入本次提供的 5 个服务根地址；标准实体集和供应商隔离字段由 Portal 固定处理，无需逐项配置。创建 ASN 前，Portal 会读取 SAP `$metadata`，确认目标实体集存在且允许创建。

如租户确认写入实体集不是 `A_InbDeliveryHeader`，可额外设置 `SAP_ASN_CREATE_PATH`；如供应商字段不是 `Supplier`，可设置 `SAP_ASN_CREATE_VENDOR_FIELD`。这两个配置仅用于租户扩展，不影响默认标准 API。

## 无数据库的账号与权限管理

开发模式可通过 `PORTAL_AUTH_MODE=single_vendor` + `PORTAL_VENDOR_ID` 将一个部署实例固定在单个供应商范围。生产多供应商模式使用：

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
