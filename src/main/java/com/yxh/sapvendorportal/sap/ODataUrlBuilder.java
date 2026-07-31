package com.yxh.sapvendorportal.sap;

import com.yxh.sapvendorportal.config.PortalProperties;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class ODataUrlBuilder {
    private ODataUrlBuilder() { }
    public static URI scopedQuery(PortalProperties.Service service, String vendorId, String search, String searchField, String orderBy, int requestedTop) {
        if (blank(service.getUrl()) || blank(service.getEntity()) || blank(service.getSupplierField())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "该业务服务尚未配置实体集或供应商字段；为防止越权查询，已拒绝调用。");
        }
        String filter = service.getSupplierField() + " eq '" + escape(vendorId) + "'";
        if (!blank(search) && !blank(searchField)) filter += " and contains(" + searchField + ",'" + escape(search) + "')";
        int top = Math.min(Math.max(requestedTop, 1), 100);
        String base = service.getUrl().replaceAll("/$", "") + "/" + service.getEntity();
        String query = "$filter=" + encode(filter) + "&$top=" + top + "&$orderby=" + encode(orderBy);
        return URI.create(base + "?" + query);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String escape(String value) { return value.replace("'", "''"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
