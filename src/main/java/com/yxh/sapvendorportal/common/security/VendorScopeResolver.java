package com.yxh.sapvendorportal.common.security;

import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.service.PortalLoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class VendorScopeResolver {
    private final PortalProperties properties;
    private final PortalLoginService loginService;
    public VendorScopeResolver(PortalProperties properties, PortalLoginService loginService) { this.properties = properties; this.loginService = loginService; }

    public VendorScope resolve(HttpServletRequest request) {
        if ("single_vendor".equals(properties.getAuthMode())) return new VendorScope(properties.getVendorId(), "server_environment");
        if ("lark_bitable".equals(properties.getAuthMode())) {
            PortalLoginService.Principal principal = loginService.require(request);
            return new VendorScope(principal.vendorId(), "lark_bitable", principal.account(), principal.vendorName(), principal.purchasingOrganizations(), principal.companyCodes(), principal.plants(), principal.permissions());
        }
        String vendorId = request.getHeader("X-Portal-Vendor-Id");
        String timestamp = request.getHeader("X-Portal-Timestamp");
        String signature = request.getHeader("X-Portal-Signature");
        if (blank(vendorId) || blank(timestamp) || blank(signature)) throw unauthorized("身份代理未提供供应商范围签名。");
        try {
            long millis = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() - millis) > 300_000L) throw unauthorized("身份代理签名已过期。");
            byte[] expected = hmac(vendorId + "." + timestamp);
            byte[] actual = hex(signature);
            if (!MessageDigest.isEqual(expected, actual)) throw unauthorized("身份代理签名无效。");
            return new VendorScope(vendorId, "signed_identity_proxy");
        } catch (NumberFormatException exception) {
            throw unauthorized("身份代理时间戳格式无效。");
        }
    }

    private byte[] hmac(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getIdentityHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) { throw new IllegalStateException("无法校验身份代理签名。", exception); }
    }
    private byte[] hex(String value) {
        if (!value.matches("[0-9a-fA-F]{64}")) throw unauthorized("身份代理签名格式无效。");
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private ResponseStatusException unauthorized(String message) { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    public record VendorScope(String vendorId, String identitySource, String account, String vendorName, Set<String> purchasingOrganizations, Set<String> companyCodes, Set<String> plants, Set<String> permissions) {
        public VendorScope(String vendorId, String identitySource) { this(vendorId, identitySource, "", "", Set.of(), Set.of(), Set.of(), Set.of()); }
        public boolean allows(String permission) { return !"lark_bitable".equals(identitySource) || permissions.contains(permission); }
    }
}
