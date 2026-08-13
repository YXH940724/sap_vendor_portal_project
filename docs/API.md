# API 接口文档

## 通用约定

- 基础地址：`http://<host>:<port>`，默认 `http://localhost:3000`。
- 内容类型：请求体为 JSON 的接口使用 `Content-Type: application/json`；响应均为 JSON。
- 登录模式 `lark_bitable`：除健康检查、登录状态和登录接口外，业务接口需要浏览器 Cookie `PORTAL_SESSION`。
- 身份代理模式 `proxy_hmac`：业务接口需携带 `X-Portal-Vendor-Id`、`X-Portal-Timestamp`（毫秒，5 分钟有效）、`X-Portal-Signature`（`HMAC-SHA256(vendorId.timestamp)` 的十六进制值）。
- 返回字段中的 SAP 业务编码、日期和金额以 SAP 原始数据及映射结果为准；未提供的可选字段会因 `NON_NULL` 配置省略。
- `top` 在服务端被限制为 1–100；所有数据读取受当前供应商和权限范围限制。

### 通用错误格式

由业务校验、鉴权或外部服务错误抛出的 HTTP 错误统一返回：

```json
{ "message": "可供前端展示的错误说明" }
```

| 状态码 | 含义 | 常见情况 |
| --- | --- | --- |
| 400 | 请求不合法 | 参数、日期、数量、金额、单据行或跨公司/币种校验失败。 |
| 401 | 未登录或身份代理签名无效 | Cookie 缺失/过期、账号无效、代理时间戳过期或签名错误。 |
| 403 | 无权限 | 飞书授权表未维护对应权限，或请求超出供应商范围。 |
| 404 | 资源不存在 | `resourceName` 不在受支持列表中。 |
| 502 | 外部服务失败 | SAP OData 或 AI 服务返回错误/超时。 |
| 503 | 配置或飞书服务不可用 | 缺少必要环境变量、飞书授权表读取失败。 |

## 认证接口

### GET `/api/auth/session`

读取登录状态。无需请求参数。

未登录示例：

```json
{ "required": true, "authenticated": false }
```

已登录示例：

```json
{
  "required": true,
  "authenticated": true,
  "account": "vendor.demo",
  "vendorId": "13300006",
  "vendorName": "示例供应商",
  "permissions": ["ORDER_READ", "ASN_READ"]
}
```

代理模式示例：`{ "required": false, "authenticated": true }`。登录配置不完整时额外返回 `configurationIssue`。

### POST `/api/auth/login`

飞书多维表格登录。代理模式不使用该接口。

请求：

```json
{ "account": "vendor.demo", "password": "明文登录密码" }
```

成功响应 `200`：响应体与已登录的 `/api/auth/session` 相同，并通过 `Set-Cookie` 下发 `PORTAL_SESSION`（`HttpOnly`、`SameSite=Strict`）。密码不会回传。

失败：`401`（账号、密码、状态、有效期或登录配置无效）。

### POST `/api/auth/logout`

注销并使当前 Cookie 会话失效。无请求体；成功响应 `200`：

```json
{ "authenticated": false }
```

## 平台接口

### GET `/api/health`

检查应用配置状态。无鉴权、无参数。

```json
{ "ok": true, "configured": true, "issue": "" }
```

`configured=false` 时 `issue` 说明缺失的 SAP、认证或飞书配置。

### GET `/api/session`

返回当前数据范围。需有效身份。

```json
{
  "vendorId": "13300006",
  "identitySource": "lark_bitable",
  "storage": "signed_session"
}
```

身份代理模式的 `identitySource` 为 `signed_identity_proxy`，`storage` 为 `stateless_portal`。

### GET `/api/dashboard`

读取工作台和数据看板。需要 `ORDER_READ`、`ASN_READ`、`GOODS_RECEIPT_READ`、`SETTLEMENT_READ` 中对应数据可用；各数据源读取失败时会在 `dataIssues` 中返回，而不是阻断整张看板。

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `refresh` | boolean | `false` | `true` 时清理当前供应商缓存后重新读取 SAP。 |

响应结构：

```json
{
  "vendorId": "13300006",
  "sampleLimit": 100,
  "metrics": [{"label":"采购订单行","value":25,"code":"PO","hint":"…"}],
  "orderStatus": [{"label":"待交货","value":10}],
  "asnStatus": [{"label":"未处理","value":8}],
  "settlementStatus": [{"label":"可结算","value":11}],
  "purchaseManagementMetrics": [{
    "label":"供应商交付准时率", "value":"80%", "numerator":8, "denominator":10,
    "formula":"按时完成交付行 ÷ … × 100%", "note":"…"
  }],
  "dataIssues": [{"name":"收货凭证","message":"…"}],
  "retrievedAt":"2026-08-13T00:00:00Z"
}
```

### GET `/api/data/{resourceName}`

查询标准化业务数据。

| 路径参数 | 可选值 | 所需权限 |
| --- | --- | --- |
| `resourceName` | `suppliers` | `SUPPLIER_PROFILE_READ` |
|  | `purchaseOrders` | `ORDER_READ` |
|  | `asns` | `ASN_READ` |
|  | `materialDocuments` | `GOODS_RECEIPT_READ` |
|  | `invoices` | `SETTLEMENT_READ` |

| 查询参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `search` | string | 空 | 按 SAP 支持的范围搜索。 |
| `top` | integer | `30` | 返回上限，实际限制为 1–100。 |
| `refresh` | boolean | `false` | 是否跳过当前供应商缓存。 |

通用响应：

```json
{
  "resource":"purchaseOrders", "vendorId":"13300006", "count":1,
  "records":[{"PurchaseOrder":"4500000001","PurchaseOrderItem":"10"}],
  "retrievedAt":"2026-08-13T00:00:00Z"
}
```

主要记录字段：

| 资源 | 常用字段 |
| --- | --- |
| `suppliers` | `Supplier`、`BusinessPartner`、`OrganizationBPName1`、地址/邮箱/电话、`TaxNumber5`、`SupplierCompanies`、`SupplierBanks`、`Contacts`。 |
| `purchaseOrders` | `PurchaseOrder`、`PurchaseOrderItem`、`Material`、`MaterialDescription`、`OrderQuantity`、`DeliveryDate`、`PurchaseOrderStatus`、`OrderType`、`ReceivedQuantity`、`OpenReceiptQuantity`、`UnclearedAsnQuantity`、`AsnAvailableQuantity`、`CreatedAsnQuantity`、`SubcontractingComponents`。 |
| `asns` | `DeliveryDocument`、`DeliveryDirection`、`PurchaseOrder`、`PurchaseOrderItem`、`Material`、`ActualDeliveryQuantity`、`BatchBySupplier`、发运/交货状态字段。 |
| `materialDocuments` | `MaterialDocument`、`MaterialDocumentYear`、`MaterialDocumentItem`、`PurchaseOrder`、`PurchaseOrderItem`、`GoodsMovementType`、`QuantityInEntryUnit`、`PostingDate`。 |
| `invoices` | `SupplierInvoice`、`FiscalYear`、`InvoicingParty`、`PurchaseOrder`、`PurchaseOrderItem`、数量、金额、货币及发票状态字段。 |

请求示例：

```bash
curl -s -b cookies.txt 'http://localhost:3000/api/data/purchaseOrders?search=4500000001&top=10'
```

### GET `/api/reconciliation`

返回收货、发票和订单组合计算的结算池。需要 `SETTLEMENT_READ`。

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `refresh` | boolean | `false` | 是否重新读取 SAP。 |

```json
{
  "vendorId":"13300006", "count":1,
  "records":[{
    "receiptKey":"5000000001/2026/1", "purchaseOrder":"4500000001",
    "purchaseOrderItem":"10", "materialDocument":"5000000001",
    "postingDate":"2026-08-01", "receivedQuantity":10,
    "settledQuantity":4, "remainingQuantity":6,
    "settlementStatus":"可结算", "settlementInvoices":["5100000001"]
  }],
  "retrievedAt":"2026-08-13T00:00:00Z"
}
```

记录还可包含物料、单位、公司代码、币种、税码、净价、退货数量等字段。

### POST `/api/asns`

创建 ASN/内向交货单。需要 `ASN_CREATE`。服务会拒绝跨供应商行、退货订单、完全交付订单、数量非正或超过实时可发运量的行。

请求：

```json
{
  "portalAsnNumber":"ASN-20260813-001",
  "purchaseOrder":"4500000001",
  "sourcePurchaseOrders":["4500000001"],
  "shippingDate":"2026-08-13",
  "plannedDeliveryDate":"2026-08-15",
  "transportReference":"TRUCK-001",
  "items":[{
    "sourcePurchaseOrder":"4500000001", "sourcePurchaseOrderItem":"10",
    "material":"TG11", "quantity":5, "unit":"EA"
  }]
}
```

`portalAsnNumber` 可选，格式为 1–35 位字母、数字、下划线或连字符；`items` 至少一行。成功响应 `201`：

```json
{
  "vendorId":"13300006", "portalAsnNumber":"ASN-20260813-001",
  "sapInboundDelivery":"1800000001", "result":{}
}
```

### POST `/api/invoices`

创建 SAP 供应商预制发票。需要 `INVOICE_CREATE`。选中行必须为当前供应商的可结算收货行；同一单据要求公司代码、币种一致，且数量和金额必须通过实时校验。

请求：

```json
{
  "invoiceReference":"INV-20260813-001",
  "documentDate":"2026-08-13",
  "postingDate":"2026-08-13",
  "taxDeterminationDate":"2026-08-13",
  "headerText":"8月收货结算",
  "netAmount":1000.00,
  "taxAmount":130.00,
  "grossAmount":1130.00,
  "items":[{"receiptKey":"5000000001/2026/1","quantity":5}]
}
```

其中 `taxDeterminationDate`、`headerText` 可选；`grossAmount` 必须等于 `netAmount + taxAmount`。成功响应 `201`：

```json
{ "vendorId":"13300006", "result":{} }
```

创建结果字段由 SAP 实际响应决定。

## AI 助手接口

### GET `/api/agent/status`

读取 AI 配置状态，无请求参数。

```json
{ "enabled":true, "issue":"", "provider":"DeepSeek AI", "agent":"供应商协同助手" }
```

### POST `/api/agent/chat`

在当前供应商范围内向 AI 助手提问。需要 `AI_QUERY`。

请求：

```json
{
  "message":"查询可创建 ASN 的订单",
  "history":[{"role":"user","content":"今天有什么待办？"}]
}
```

- `message` 为必填非空文本，最大 2000 字符。
- `history` 可选；角色仅接受 `user` 或 `assistant`。服务端仅保留最近 4 条历史并截断每条内容。

成功响应：

```json
{
  "content":"……",
  "tools":[{"name":"query_asn_creatable","summary":"已查询可创建 ASN 的订单"}],
  "vendorScope":"当前登录供应商",
  "retrievedAt":"2026-08-13T00:00:00Z"
}
```

助手可调用的只读/草稿工具包括待办、待交订单、可创建 ASN、可结算收货、订单/收货/ASN/结算查询、ASN 草稿、发票草稿和打印准备；工具调用不会直接创建 SAP 单据。

