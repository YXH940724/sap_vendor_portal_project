package com.yxh.sapvendorportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.integration.lark.LarkBitableClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalLoginServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsSessionOnlyForEnabledAccountWithMatchingBcryptPassword() throws Exception {
        PortalProperties properties = bitableProperties();
        LarkBitableClient bitable = mock(LarkBitableClient.class);
        String hash = new BCryptPasswordEncoder().encode("Portal!2026");
        when(bitable.loginRecords()).thenReturn(objectMapper.readTree("""
                [{"fields":{"登录账号":"vendor.operator","密码哈希":"%s","供应商编码":"13300006","供应商名称":"示例供应商","权限":["ORDER_READ","ASN_CREATE","AI_QUERY"],"状态":"启用"}}]
                """.formatted(hash)));
        PortalLoginService service = new PortalLoginService(properties, bitable);

        var result = service.login("vendor.operator", "Portal!2026");

        assertThat(result.token()).isNotBlank();
        assertThat(result.principal().vendorId()).isEqualTo("13300006");
        assertThat(result.principal().permissions()).contains("ASN_CREATE", "AI_QUERY");
    }

    @Test
    void rejectsWrongPasswordWithoutDisclosingWhichFieldFailed() throws Exception {
        PortalProperties properties = bitableProperties();
        LarkBitableClient bitable = mock(LarkBitableClient.class);
        when(bitable.loginRecords()).thenReturn(objectMapper.readTree("""
                [{"fields":{"登录账号":"vendor.operator","密码哈希":"$2a$10$0UJQ8jRbiBK2DCt2omHqQeVeLHrsEH4FL75VAhDVwuXZTFm6g0RPe","供应商编码":"13300006","状态":"启用"}}]
                """));
        PortalLoginService service = new PortalLoginService(properties, bitable);

        assertThatThrownBy(() -> service.login("vendor.operator", "incorrect"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("账号、密码或授权状态无效");
    }

    @Test
    void rejectsRetiredSingleVendorModeWithActionableConfigurationMessage() {
        PortalProperties properties = new PortalProperties();
        properties.setAuthMode("single_vendor");
        PortalLoginService service = new PortalLoginService(properties, mock(LarkBitableClient.class));

        assertThat(service.configurationIssue()).contains("PORTAL_AUTH_MODE=lark_bitable");
        assertThatThrownBy(() -> service.login("vendor.operator", "Portal!2026"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PORTAL_AUTH_MODE=lark_bitable");
    }

    private PortalProperties bitableProperties() {
        PortalProperties properties = new PortalProperties();
        properties.setAuthMode("lark_bitable");
        properties.getBitable().setAppId("cli_demo"); properties.getBitable().setAppSecret("secret"); properties.getBitable().setAppToken("TaDemo"); properties.getBitable().setTableId("tblDemo"); properties.getBitable().setSessionSecret("session-secret");
        return properties;
    }
}
