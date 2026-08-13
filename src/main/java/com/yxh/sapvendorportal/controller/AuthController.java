package com.yxh.sapvendorportal.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yxh.sapvendorportal.service.PortalLoginService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** 供应商账号登录入口。密码只用于即时 BCrypt 校验，绝不回传或写入日志。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final PortalLoginService loginService;
    public AuthController(PortalLoginService loginService) { this.loginService = loginService; }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        if (!loginService.isBitableMode()) return Map.of("required", false, "authenticated", true);
        if (!loginService.authenticated(request)) return Map.of("required", true, "authenticated", false);
        var principal = loginService.require(request);
        return Map.of("required", true, "authenticated", true, "account", principal.account(), "vendorId", principal.vendorId(), "vendorName", principal.vendorName(), "permissions", principal.permissions());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody JsonNode input, HttpServletResponse response) {
        var result = loginService.login(input.path("account").asText(), input.path("password").asText());
        long ttlSeconds = Math.max(900, (result.expiresAt() - System.currentTimeMillis()) / 1000);
        ResponseCookie cookie = ResponseCookie.from(PortalLoginService.SESSION_COOKIE, result.token()).httpOnly(true).sameSite("Strict").secure(false).path("/").maxAge(Duration.ofSeconds(ttlSeconds)).build();
        response.addHeader("Set-Cookie", cookie.toString());
        return Map.of("authenticated", true, "account", result.principal().account(), "vendorId", result.principal().vendorId(), "vendorName", result.principal().vendorName(), "permissions", result.principal().permissions());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        loginService.logout(request);
        ResponseCookie cookie = ResponseCookie.from(PortalLoginService.SESSION_COOKIE, "").httpOnly(true).sameSite("Strict").secure(false).path("/").maxAge(Duration.ZERO).build();
        response.addHeader("Set-Cookie", cookie.toString());
        return Map.of("authenticated", false);
    }
}
