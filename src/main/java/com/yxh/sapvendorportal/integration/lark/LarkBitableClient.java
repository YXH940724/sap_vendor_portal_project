package com.yxh.sapvendorportal.integration.lark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.config.PortalProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 服务端读取飞书多维表格授权记录；应用密钥不会暴露给浏览器。 */
@Component
public class LarkBitableClient {
    private static final String OPEN_API = "https://open.feishu.cn/open-apis";
    private final PortalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String accessToken;
    private long tokenExpiresAt;

    public LarkBitableClient(PortalProperties properties, ObjectMapper objectMapper) { this.properties = properties; this.objectMapper = objectMapper; }

    public JsonNode loginRecords() {
        PortalProperties.Bitable bitable = properties.getBitable();
        String uri = OPEN_API + "/bitable/v1/apps/" + bitable.getAppToken() + "/tables/" + bitable.getTableId() + "/records?page_size=500";
        return execute(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + appAccessToken()).GET().build()).path("data").path("items");
    }

    private synchronized String appAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) return accessToken;
        PortalProperties.Bitable bitable = properties.getBitable();
        String payload = "{\"app_id\":\"" + json(bitable.getAppId()) + "\",\"app_secret\":\"" + json(bitable.getAppSecret()) + "\"}";
        JsonNode response = execute(HttpRequest.newBuilder(URI.create(OPEN_API + "/auth/v3/app_access_token/internal")).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build());
        accessToken = response.path("app_access_token").asText();
        if (accessToken.isBlank()) throw unavailable("飞书未返回应用访问令牌，请检查 LARK_APP_ID 与 LARK_APP_SECRET。");
        tokenExpiresAt = System.currentTimeMillis() + Math.max(60, response.path("expire").asLong(7200) - 60) * 1000L;
        return accessToken;
    }

    private JsonNode execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode payload = objectMapper.readTree(response.body());
            if (response.statusCode() >= 400 || payload.path("code").asInt(0) != 0) throw unavailable("飞书多维表格访问失败：" + payload.path("msg").asText("请检查飞书应用权限与授权表配置。"));
            return payload;
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw unavailable("无法读取飞书多维表格授权信息。"); }
    }

    private String json(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private ResponseStatusException unavailable(String message) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message); }
}
