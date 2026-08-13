# 项目部署与运维文档

## 编译、测试与打包

在项目根目录执行：

```bash
mvn test
mvn clean package
```

成功后可执行包为：

```text
target/sap-vendor-portal-0.1.0.jar
```

本地验证启动：

```bash
java -jar target/sap-vendor-portal-0.1.0.jar
```

默认监听 `3000` 端口，可用 `PORT` 环境变量覆盖。前端为 JAR 内 `src/main/resources/static/` 的静态资源，不需要单独构建或发布 Node.js 应用。

## 生产部署步骤

1. **准备运行账号和网络**：使用无交互的操作系统服务账号；确保应用服务器可访问 SAP OData、飞书开放 API，以及启用 AI 时的模型服务。
2. **构建制品**：在 CI 或受控构建环境执行 `mvn clean package`，仅发布生成的 JAR。
3. **安全注入配置**：通过部署平台的密钥管理、环境变量或受限 `.env` 文件提供凭据；不要把 `.env`、SAP 密码、飞书 `app_secret`、会话密钥或 AI API Key 放入 Git、镜像层和日志。
4. **选择认证模式**：
   - `lark_bitable`（默认）：配置飞书应用和授权表，供应商在 Portal 登录；每条授权记录维护供应商及权限范围。
   - `proxy_hmac`：用于企业身份代理。Portal 不显示浏览器登录要求，但每个业务请求必须由代理加入签名供应商范围头。
   - `single_vendor` 已在代码中明确禁用，不能用于部署。
5. **配置 SAP**：设置 `SAP_AUTH_MODE=basic` 并配置 `SAP_USERNAME`、`SAP_PASSWORD`，或设置 `SAP_AUTH_MODE=bearer` 与 `SAP_BEARER_TOKEN`。确认各 OData 服务地址和实体集对技术用户可访问。
6. **启动服务**：

   ```bash
   export PORT=3000
   java -jar target/sap-vendor-portal-0.1.0.jar
   ```

7. **反向代理与 HTTPS**：由 Nginx、负载均衡器或平台入口终止 TLS，并仅公开必要端口。应用本身未提供 TLS 配置、CORS 白名单或安全 Cookie 开关。
8. **验收**：请求 `/api/health`，完成一次供应商登录，分别验证订单、ASN、结算池、供应商资料和 AI（如启用）。

## 环境变量

| 变量 | 必填条件 | 说明 |
| --- | --- | --- |
| `PORT` | 否 | HTTP 端口，默认 `3000`。 |
| `PORTAL_AUTH_MODE` | 是 | `lark_bitable` 或 `proxy_hmac`；默认前者。 |
| `PORTAL_SAP_CACHE_TTL_SECONDS` | 否 | SAP 内存缓存秒数，默认 `120`，设 `0` 关闭。 |
| `LARK_APP_ID`、`LARK_APP_SECRET` | 飞书登录时 | 飞书应用身份。 |
| `LARK_BITABLE_APP_TOKEN`、`LARK_BITABLE_LOGIN_TABLE_ID` | 飞书登录时 | 授权表所在多维表格和数据表。 |
| `PORTAL_SESSION_SECRET` | 飞书登录时 | 会话签名密钥。 |
| `PORTAL_SESSION_TTL_MINUTES` | 否 | 会话有效期，默认 `480` 分钟，最短 15 分钟。 |
| `PORTAL_IDENTITY_HMAC_SECRET` | 代理模式时 | 校验身份代理签名的共享密钥。 |
| `SAP_AUTH_MODE` | 是 | `basic` 或 `bearer`。 |
| `SAP_USERNAME`、`SAP_PASSWORD` | Basic 模式时 | SAP 技术用户。 |
| `SAP_BEARER_TOKEN` | Bearer 模式时 | SAP Bearer Token。 |
| `SAP_*_URL` | 否 | 覆盖 SAP OData 服务根地址，完整列表见 `.env.example`。 |
| `AI_ENABLED` | 否 | 默认 `true`；设为 `false` 关闭 AI。 |
| `DEEPSEEK_API_KEY` | AI 启用时 | AI 服务密钥。 |
| `DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` | 否 | AI 兼容接口地址和模型，代码默认 Flash 模型。 |

## 缓存、会话与扩容限制

- 查询缓存和登录会话均在单个 JVM 内存中；服务重启后会话立即失效、缓存丢失。
- 缓存按供应商和查询键隔离。点击刷新、创建 ASN、创建预制发票会清理相关缓存。
- 多实例部署时，当前代码没有共享会话或共享缓存。使用飞书浏览器登录模式时，需要粘性会话或在后续版本接入共享 Session 存储；身份代理模式更适合无状态横向扩展。
- SAP HTTP 客户端连接超时为 10 秒、请求超时为 20 秒。SAP 慢响应或网络策略会直接影响页面和 AI 数据查询耗时。

## 安全注意事项

- 浏览器只访问 Portal，SAP、飞书和 AI 密钥仅由 Java 服务端读取。
- 飞书登录 Cookie 已设置 `HttpOnly` 和 `SameSite=Strict`，但当前代码固定 `Secure=false`。生产 HTTPS 环境应在上线前补充 `Secure=true` 的可配置 Cookie 策略；否则不要把本实现视为完成的生产级 Cookie 传输加固。
- 使用独立、最小权限的 SAP 技术用户。业务范围控制在 Portal 中执行，但 SAP 端也应配置最小授权作为纵深防护。
- `proxy_hmac` 共享密钥必须使用专用密钥管理服务保存和轮换；签名仅由可信身份代理生成。

## 常见问题排查

| 现象 | 检查与处理 |
| --- | --- |
| 启动后终端一直停在 `Started SapVendorPortalApplication` | 这是 Web 服务正常运行状态。访问 `http://localhost:3000`，用 `Ctrl+C` 停止。 |
| 登录页提示未启用飞书多维表格模式 | 设置 `PORTAL_AUTH_MODE=lark_bitable`，并确认应用重新启动后读取到该环境变量。 |
| 登录失败或提示账号、密码、授权状态无效 | 检查飞书表字段名、状态是否为“启用”、生效/失效日期、供应商编码、BCrypt 哈希和应用对表的读取权限。 |
| 页面显示其他供应商数据 | 重新登录并检查授权表的供应商编码；不要使用已废弃的 `single_vendor`。业务读取以登录会话或签名请求头为准。 |
| SAP OData 400/401/403 | 校验服务 URL、实体集、`$expand` 导航属性、技术用户/Token 及 SAP 端授权；从 `/api/health` 查看配置问题。 |
| SAP 查询慢 | 先检查网络与 SAP 响应；正常导航会使用短期缓存。需要实时数据时使用页面“刷新数据”或 `refresh=true`，不要把缓存 TTL 长期设为 `0`。 |
| AI 助手不可用或慢 | 检查 `AI_ENABLED`、`DEEPSEEK_API_KEY`、模型/服务地址和外网连通性。助手会读取当前供应商范围的 SAP 数据，SAP 或模型慢都会增加耗时。 |
| 代理模式报签名无效/过期 | 确认三项 `X-Portal-*` 头完整；时间戳为当前毫秒且不超过 5 分钟；签名原文严格为 `vendorId.timestamp`。 |
| 重启后用户都需重新登录 | 当前会话存于 JVM 内存，这是既定实现。单实例重启后重新登录；多实例需使用粘性会话或改造为共享会话存储。 |

