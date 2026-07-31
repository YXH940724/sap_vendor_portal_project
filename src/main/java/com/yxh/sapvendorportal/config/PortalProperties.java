package com.yxh.sapvendorportal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portal")
public class PortalProperties {
    private String authMode = "single_vendor";
    private String vendorId;
    private String identityHmacSecret;
    private final Sap sap = new Sap();

    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public String getVendorId() { return vendorId; }
    public void setVendorId(String vendorId) { this.vendorId = vendorId; }
    public String getIdentityHmacSecret() { return identityHmacSecret; }
    public void setIdentityHmacSecret(String identityHmacSecret) { this.identityHmacSecret = identityHmacSecret; }
    public Sap getSap() { return sap; }

    public String validationIssue() {
        if (!"single_vendor".equals(authMode) && !"proxy_hmac".equals(authMode)) return "PORTAL_AUTH_MODE 仅支持 single_vendor 或 proxy_hmac。";
        if ("single_vendor".equals(authMode) && blank(vendorId)) return "尚未配置 PORTAL_VENDOR_ID。";
        if ("proxy_hmac".equals(authMode) && blank(identityHmacSecret)) return "尚未配置 PORTAL_IDENTITY_HMAC_SECRET。";
        if (!"basic".equals(sap.authMode) && !"bearer".equals(sap.authMode)) return "SAP_AUTH_MODE 仅支持 basic 或 bearer。";
        if ("basic".equals(sap.authMode) && (blank(sap.username) || blank(sap.password))) return "尚未配置 SAP_USERNAME 或 SAP_PASSWORD。";
        if ("bearer".equals(sap.authMode) && blank(sap.bearerToken)) return "尚未配置 SAP_BEARER_TOKEN。";
        for (Service service : new Service[]{sap.businessPartner, sap.purchaseOrder, sap.asn, sap.materialDocument, sap.supplierInvoice}) {
            if (blank(service.url)) return "尚未配置 SAP OData 服务地址。";
            if (blank(service.entity) || blank(service.supplierField)) return "尚未配置 OData 实体集或供应商隔离字段；为防止越权查询，已拒绝调用。";
        }
        return null;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public static class Sap {
        private String authMode = "basic";
        private String username;
        private String password;
        private String bearerToken;
        private final Service businessPartner = new Service();
        private final Service purchaseOrder = new Service();
        private final Service asn = new Service();
        private final Service materialDocument = new Service();
        private final Service supplierInvoice = new Service();
        private String asnCreatePath;
        private String asnCreateVendorField;
        public String getAuthMode() { return authMode; } public void setAuthMode(String value) { authMode = value; }
        public String getUsername() { return username; } public void setUsername(String value) { username = value; }
        public String getPassword() { return password; } public void setPassword(String value) { password = value; }
        public String getBearerToken() { return bearerToken; } public void setBearerToken(String value) { bearerToken = value; }
        public Service getBusinessPartner() { return businessPartner; } public Service getPurchaseOrder() { return purchaseOrder; }
        public Service getAsn() { return asn; } public Service getMaterialDocument() { return materialDocument; }
        public Service getSupplierInvoice() { return supplierInvoice; }
        public String getAsnCreatePath() { return asnCreatePath; } public void setAsnCreatePath(String value) { asnCreatePath = value; }
        public String getAsnCreateVendorField() { return asnCreateVendorField; } public void setAsnCreateVendorField(String value) { asnCreateVendorField = value; }
    }

    public static class Service {
        private String url;
        private String entity;
        private String supplierField;
        public String getUrl() { return url; } public void setUrl(String value) { url = value; }
        public String getEntity() { return entity; } public void setEntity(String value) { entity = value; }
        public String getSupplierField() { return supplierField; } public void setSupplierField(String value) { supplierField = value; }
    }
}
