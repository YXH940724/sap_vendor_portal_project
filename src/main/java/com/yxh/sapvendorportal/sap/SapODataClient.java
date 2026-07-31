package com.yxh.sapvendorportal.sap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yxh.sapvendorportal.config.PortalProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class SapODataClient {
    private final PortalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
    public SapODataClient(PortalProperties properties, ObjectMapper objectMapper) { this.properties = properties; this.objectMapper = objectMapper; }

    public List<JsonNode> get(PortalProperties.Service service, String vendorId, String search, String searchField, String orderBy, int top) {
        URI uri = ODataUrlBuilder.scopedQuery(service, vendorId, search, searchField, orderBy, top);
        HttpRequest request = baseRequest(uri).header("Accept", "application/json").GET().build();
        JsonNode payload = execute(request);
        return records(payload);
    }

    public JsonNode createAsn(String vendorId, JsonNode input) {
        String path = properties.getSap().getAsnCreatePath();
        String field = properties.getSap().getAsnCreateVendorField();
        if (blank(path) || blank(field)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置 SAP_ASN_CREATE_PATH 和 SAP_ASN_CREATE_VENDOR_FIELD，不能创建 ASN。");
        String base = properties.getSap().getAsn().getUrl().replaceAll("/$", "");
        HttpResponse<String> csrf = send(baseRequest(URI.create(base)).header("X-CSRF-Token", "Fetch").GET().build());
        if (csrf.statusCode() >= 400) throw sapError(csrf);
        String csrfToken = csrf.headers().firstValue("x-csrf-token").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP 未返回 CSRF Token，不能创建 ASN。"));
        ObjectNode payload = input.deepCopy();
        payload.put(field, vendorId);
        HttpRequest request = baseRequest(URI.create(base + "/" + path.replaceFirst("^/", "")))
                .header("Accept", "application/json").header("Content-Type", "application/json").header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(write(payload), StandardCharsets.UTF_8)).build();
        return execute(request);
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        String authorization = "bearer".equals(properties.getSap().getAuthMode())
                ? "Bearer " + properties.getSap().getBearerToken()
                : "Basic " + Base64.getEncoder().encodeToString((properties.getSap().getUsername() + ":" + properties.getSap().getPassword()).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Authorization", authorization);
    }
    private JsonNode execute(HttpRequest request) { HttpResponse<String> response = send(request); if (response.statusCode() >= 400) throw sapError(response); try { return objectMapper.readTree(response.body()); } catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP 返回了无法解析的响应。", exception); } }
    private HttpResponse<String> send(HttpRequest request) { try { return httpClient.send(request, HttpResponse.BodyHandlers.ofString()); } catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 SAP OData 服务。", exception); } }
    private ResponseStatusException sapError(HttpResponse<String> response) { try { JsonNode error = objectMapper.readTree(response.body()).path("error"); String message = error.path("message").path("value").asText(error.path("message").asText("SAP OData 返回 HTTP " + response.statusCode())); return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message); } catch (Exception ignored) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP OData 返回 HTTP " + response.statusCode()); } }
    private List<JsonNode> records(JsonNode payload) { JsonNode v4 = payload.path("value"); if (v4.isArray()) { List<JsonNode> result = new ArrayList<>(); v4.forEach(result::add); return result; } JsonNode v2 = payload.path("d").path("results"); if (v2.isArray()) { List<JsonNode> result = new ArrayList<>(); v2.forEach(result::add); return result; } return List.of(); }
    private String write(JsonNode value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("无法生成 SAP 请求体。", exception); } }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
