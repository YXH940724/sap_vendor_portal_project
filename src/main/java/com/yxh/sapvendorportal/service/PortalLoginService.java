package com.yxh.sapvendorportal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.integration.lark.LarkBitableClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 基于飞书多维表格授权记录的账号登录与内存会话管理。 */
@Service
public class PortalLoginService {
    public static final String SESSION_COOKIE = "PORTAL_SESSION";
    private final PortalProperties properties;
    private final LarkBitableClient bitable;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public PortalLoginService(PortalProperties properties, LarkBitableClient bitable) { this.properties = properties; this.bitable = bitable; }

    public LoginResult login(String account, String password) {
        if (!configurationIssue().isBlank()) throw unauthorized(configurationIssue());
        if (blank(account) || blank(password)) throw unauthorized("请输入登录账号和密码。");
        Principal principal = null;
        for (JsonNode item : bitable.loginRecords()) {
            JsonNode fields = item.path("fields");
            if (!account.trim().equals(text(fields, "登录账号")) || !"启用".equals(text(fields, "状态")) || !effective(fields)) continue;
            String passwordHash = text(fields, "密码哈希");
            if (!passwordHash.startsWith("$2") || !passwordEncoder.matches(password, passwordHash)) continue;
            principal = principal(account.trim(), fields);
            break;
        }
        if (principal == null) throw unauthorized("账号、密码或授权状态无效。");
        purgeExpired();
        String sessionId = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String token = sessionId + "." + sign(sessionId);
        long expiresAt = System.currentTimeMillis() + Math.max(15, properties.getBitable().getSessionTtlMinutes()) * 60_000L;
        sessions.put(sessionId, new Session(principal, expiresAt));
        return new LoginResult(token, principal, expiresAt);
    }

    public Principal require(HttpServletRequest request) {
        String sessionId = verifiedSessionId(cookie(request, SESSION_COOKIE));
        Session session = sessions.get(sessionId);
        if (session == null || session.expiresAt < System.currentTimeMillis()) {
            if (session != null) sessions.values().remove(session);
            throw unauthorized("登录已失效，请重新登录。");
        }
        return session.principal;
    }

    public boolean authenticated(HttpServletRequest request) {
        try { require(request); return true; } catch (ResponseStatusException ignored) { return false; }
    }

    public void logout(HttpServletRequest request) { sessions.remove(verifiedSessionId(cookie(request, SESSION_COOKIE))); }
    public boolean isBitableMode() { return "lark_bitable".equals(properties.getAuthMode()); }
    public boolean isProxyMode() { return "proxy_hmac".equals(properties.getAuthMode()); }
    public String configurationIssue() {
        if (isProxyMode()) return "";
        if (!isBitableMode()) return "供应商浏览器登录仅支持飞书多维表格模式，请设置 PORTAL_AUTH_MODE=lark_bitable。";
        return properties.bitableLoginIssue();
    }

    private Principal principal(String account, JsonNode fields) {
        String vendorId = text(fields, "供应商编码");
        if (blank(vendorId)) throw unauthorized("该登录账号未维护供应商编码。");
        return new Principal(account, vendorId, text(fields, "供应商名称"), values(fields, "采购组织"), values(fields, "公司代码"), values(fields, "工厂"), values(fields, "权限"));
    }

    private boolean effective(JsonNode fields) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate start = date(fields.path("生效日期")); LocalDate end = date(fields.path("失效日期"));
        return (start == null || !today.isBefore(start)) && (end == null || !today.isAfter(end));
    }

    private LocalDate date(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) return null;
        try { if (value.isNumber() || value.asText().matches("\\d{10,13}")) return Instant.ofEpochMilli(value.asLong()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); return LocalDate.parse(value.asText().substring(0, 10)); }
        catch (Exception ignored) { return null; }
    }

    private Set<String> values(JsonNode fields, String name) {
        JsonNode value = fields.path(name); Set<String> result = new LinkedHashSet<>();
        if (value.isArray()) value.forEach(item -> add(result, item.asText())); else Arrays.stream(value.asText("").split(",")).forEach(item -> add(result, item));
        return Set.copyOf(result);
    }
    private void add(Set<String> values, String value) { if (!blank(value)) values.add(value.trim()); }
    private String text(JsonNode fields, String name) { return fields.path(name).asText("").trim(); }
    private String cookie(HttpServletRequest request, String name) { if (request.getCookies() == null) return ""; return Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName())).map(Cookie::getValue).findFirst().orElse(""); }
    private String verifiedSessionId(String token) {
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2 || blank(parts[0]) || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII))) return "";
        return parts[0];
    }
    private String sign(String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(properties.getBitable().getSessionSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法签发 Portal 会话。", exception); }
    }
    private void purgeExpired() { long now = System.currentTimeMillis(); sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private ResponseStatusException unauthorized(String message) { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message); }

    public record Principal(String account, String vendorId, String vendorName, Set<String> purchasingOrganizations, Set<String> companyCodes, Set<String> plants, Set<String> permissions) { }
    public record LoginResult(String token, Principal principal, long expiresAt) { }
    private record Session(Principal principal, long expiresAt) { }
}
