# SAP 供应商协同 Portal

面向供应商的 SAP S/4HANA 协同门户。系统以当前登录供应商的授权范围为边界，聚合采购订单、ASN/送货单、收货凭证、供应商发票与供应商主数据，并提供创建 ASN、创建预制发票、单据打印、数据看板和 AI 协同助手。

本项目不保存 SAP 业务数据，也不包含本地业务数据库：运行时通过服务端调用 SAP OData，供应商账号与授权范围由飞书多维表格维护。

## 功能清单

| 模块 | 已实现能力 |
| --- | --- |
| 供应商登录与范围控制 | 飞书多维表格账号、BCrypt 密码哈希、启停与有效期校验；供应商、采购组织、公司代码、工厂、权限范围控制。也支持由企业身份代理签名供应商范围。 |
| 工作台与数据看板 | 订单、发运、结算状态汇总；供应商交付准时率、采购订单履约率及公开计算口径。 |
| 采购订单 | 查询、搜索、订单状态与免费/外协/退货等类型识别；组合计算已收货、未清 ASN、可发运量；外协组件及物料描述补充。 |
| ASN / 发运 | 查询内向交货单；退货采购订单查询外向送货单；创建 ASN 前进行订单范围、完成状态、退货与可发运量校验。 |
| 收货与结算 | 查询物料凭证；按移动类型计算净收货；关联收货、订单、发票计算可结算、已结算及剩余结算数量。 |
| 预制发票 | 根据已选可结算收货行创建 SAP 供应商预制发票；校验金额、税额、数量、公司代码、币种和实时可结算量。 |
| 供应商资料 | 显示基础资料、地址、邮箱、电话、联系人、银行、公司代码结算信息及税号。 |
| 打印 | 在浏览器中按采购订单或 ASN 生成打印视图；同一次打印不合并不同订单。 |
| AI 协同助手 | 当前供应商范围内的待办、订单、收货、ASN、结算查询及创建/打印操作指引；模型工具调用受限于白名单，不直接写入 SAP。 |
| 性能与一致性 | 供应商隔离的短期缓存、并发请求合并；刷新、创建 ASN、创建发票后清理相关缓存。 |

## 技术栈

- Java 21、Spring Boot 3.4.5、Maven
- Spring Web、Spring Validation、Spring Security Crypto（BCrypt）
- 原生 HTML/CSS/JavaScript 静态前端（无 Node.js 构建步骤）
- SAP S/4HANA OData V2 / V4
- 飞书多维表格开放 API（登录与授权）
- DeepSeek OpenAI 兼容 Chat Completions API（可选 AI 功能）

## 环境要求

- JDK 21
- Maven 3.9+（或可用的 Maven Wrapper；本仓库当前未提供 Wrapper）
- 可访问 SAP OData、飞书开放 API；启用 AI 时还需可访问 AI 服务
- 一个维护“Portal 登录授权”记录的飞书多维表格，及已授权读取该表的飞书应用

## 本地启动

1. 进入项目根目录并更新稳定版本。

   ```bash
   cd /Users/Yexinghui/sap_vendor_portal_project/sap_vendor_portal_project
   git fetch origin --prune
   git switch main
   git pull --ff-only origin main
   ```

2. 创建本地环境文件。`.env` 不应提交到 Git。

   ```bash
   cp .env.example .env
   ```

3. 在 `.env` 填写飞书登录、SAP 连接信息。启用 AI 时再填写 `DEEPSEEK_API_KEY`。请勿把密码、Token、应用密钥写入 README 或提交到仓库。

4. 启动并访问 `http://localhost:3000`。

   ```bash
   mvn spring-boot:run
   ```

应用启动日志出现 `Tomcat started on port 3000` 和 `Started SapVendorPortalApplication` 后，进程保持运行是正常现象；用 `Ctrl+C` 停止。

### 最小环境变量

默认认证模式为飞书多维表格登录：

```dotenv
PORT=3000
PORTAL_AUTH_MODE=lark_bitable
LARK_APP_ID=cli_xxx
LARK_APP_SECRET=***
LARK_BITABLE_APP_TOKEN=***
LARK_BITABLE_LOGIN_TABLE_ID=***
PORTAL_SESSION_SECRET=***
SAP_AUTH_MODE=basic
SAP_USERNAME=***
SAP_PASSWORD=***
```

可选项：`PORTAL_SAP_CACHE_TTL_SECONDS`（默认 120 秒）、`PORTAL_SESSION_TTL_MINUTES`（默认 480 分钟）、`AI_ENABLED`、`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`。完整变量及默认服务地址见 [`.env.example`](.env.example) 与 [`application.yaml`](src/main/resources/application.yaml)。系统环境变量或 JVM 属性优先于当前目录的 `.env`。

### 飞书授权表字段

登录表需要包含下列字段名：`登录账号`、`密码哈希`、`供应商编码`、`供应商名称`、`状态`、`生效日期`、`失效日期`、`采购组织`、`公司代码`、`工厂`、`权限`。

- `状态` 必须为“启用”。
- `密码哈希` 必须为 BCrypt 格式；可通过 `PasswordHashTool` 生成，不能保存明文密码。
- 采购组织、公司代码、工厂与权限均支持多选数组或逗号分隔文本。
- 常用权限：`ORDER_READ`、`ASN_READ`、`ASN_CREATE`、`GOODS_RECEIPT_READ`、`SETTLEMENT_READ`、`INVOICE_CREATE`、`SUPPLIER_PROFILE_READ`、`AI_QUERY`。

## 接口测试说明

应用启动后可先检查配置：

```bash
curl -s http://localhost:3000/api/health
curl -s http://localhost:3000/api/auth/session
```

飞书登录模式下，使用 Cookie 文件保持会话：

```bash
curl -i -c cookies.txt -X POST http://localhost:3000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"供应商账号","password":"你的密码"}'

curl -s -b cookies.txt http://localhost:3000/api/dashboard
curl -s -b cookies.txt 'http://localhost:3000/api/data/purchaseOrders?top=10'
curl -s -b cookies.txt 'http://localhost:3000/api/reconciliation?refresh=true'
```

执行自动化测试：

```bash
mvn test
```

完整接口定义、请求示例与错误说明见 [API 接口文档](docs/API.md)。

## 项目结构

```text
.
├── .env.example                     # 环境变量模板
├── pom.xml                          # Maven 依赖与构建配置
├── docs/                            # 交付文档
├── src/main/java/com/yxh/sapvendorportal/
│   ├── SapVendorPortalApplication.java # 启动类
│   ├── agent/                       # AI 对话、模型客户端、工具编排
│   ├── common/                      # 缓存、异常、统一错误、范围安全、工具
│   ├── config/                      # .env 装载与配置属性
│   ├── controller/                  # HTTP API
│   ├── integration/                 # 飞书与 SAP OData 客户端
│   ├── mapper/                      # SAP OData 记录标准化映射
│   └── service/                     # 登录和业务服务实现
├── src/main/resources/
│   ├── ai/                          # AI Skill 与 MCP 工具白名单
│   ├── application.yaml             # Spring Boot 配置
│   └── static/                      # 单页前端资源
└── src/test/java/                   # 单元与集成测试
```

## 交付文档

- [完整 API 接口文档](docs/API.md)
- [数据库与外部数据结构说明](docs/DATABASE.md)
- [部署与运维文档](docs/DEPLOYMENT.md)
