package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.sap.SapODataClient;
import com.yxh.sapvendorportal.security.VendorScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PortalController {
    private final PortalProperties properties;
    private final VendorScopeResolver scopeResolver;
    private final SapODataClient sapClient;
    private final ODataRecordMapper recordMapper;
    private final Map<String, Resource> resources = Map.of(
            "suppliers", new Resource("businessPartner", "BusinessPartner", "BusinessPartner asc"),
            "purchaseOrders", new Resource("purchaseOrder", "PurchaseOrder", "LastChangeDateTime desc"),
            "asns", new Resource("asn", "InbDelivery", "LastChangeDateTime desc"),
            "materialDocuments", new Resource("materialDocument", "MaterialDocument", "LastChangeDateTime desc"),
            "invoices", new Resource("supplierInvoice", "SupplierInvoice", "LastChangeDateTime desc")
    );
    public PortalController(PortalProperties properties, VendorScopeResolver scopeResolver, SapODataClient sapClient, ODataRecordMapper recordMapper) { this.properties = properties; this.scopeResolver = scopeResolver; this.sapClient = sapClient; this.recordMapper = recordMapper; }

    @GetMapping("/health") public Map<String, Object> health() { String issue = properties.validationIssue(); return Map.of("ok", true, "configured", issue == null, "issue", issue == null ? "" : issue); }
    @GetMapping("/session") public Map<String, String> session(HttpServletRequest request) { var scope = scopeResolver.resolve(request); return Map.of("vendorId", scope.vendorId(), "identitySource", scope.identitySource(), "storage", "stateless_portal"); }
    @GetMapping("/dashboard") public Map<String, Object> dashboard(HttpServletRequest request) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        List<JsonNode> orders = recordMapper.map("purchaseOrders", sapClient.get(service("purchaseOrder"), scope.vendorId(), "", "PurchaseOrder", "LastChangeDateTime desc", 100));
        List<JsonNode> asns = recordMapper.map("asns", sapClient.get(service("asn"), scope.vendorId(), "", "InbDelivery", "LastChangeDateTime desc", 100));
        List<JsonNode> receipts = recordMapper.map("materialDocuments", sapClient.get(service("materialDocument"), scope.vendorId(), "", "MaterialDocument", "PostingDate desc", 100));
        List<JsonNode> invoices = recordMapper.map("invoices", sapClient.get(service("supplierInvoice"), scope.vendorId(), "", "SupplierInvoice", "LastChangeDateTime desc", 100));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vendorId", scope.vendorId());
        result.put("sampleLimit", 100);
        result.put("metrics", List.of(
                metric("采购订单", orders, "PO", "待确认订单与交期"),
                metric("发运通知", asns, "ASN", "发运与到货节奏"),
                metric("收货凭证", receipts, "GR", "已过账收货记录"),
                metric("待对账发票", invoices, "IV", "结算与发票状态")
        ));
        result.put("orderStatus", distribution(orders, "PurchaseOrderStatus", "OverallStatus", "Status"));
        result.put("asnStatus", distribution(asns, "OverallStatus", "InbDeliveryStatus", "Status"));
        result.put("retrievedAt", Instant.now().toString());
        return result;
    }
    @GetMapping("/data/{resourceName}") public Map<String, Object> data(@PathVariable String resourceName, @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "30") int top, HttpServletRequest request) {
        requireConfigured(); Resource resource = resources.get(resourceName); if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知资源。");
        var scope = scopeResolver.resolve(request); List<JsonNode> records = recordMapper.map(resourceName, sapClient.get(service(resource.serviceName()), scope.vendorId(), search, resource.searchField(), resource.orderBy(), top));
        Map<String, Object> response = new LinkedHashMap<>(); response.put("resource", resourceName); response.put("vendorId", scope.vendorId()); response.put("records", records); response.put("count", records.size()); response.put("retrievedAt", Instant.now().toString()); return response;
    }
    @PostMapping("/asns") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> createAsn(@RequestBody JsonNode input, HttpServletRequest request) { requireConfigured(); var scope = scopeResolver.resolve(request); return Map.of("vendorId", scope.vendorId(), "result", sapClient.createAsn(scope.vendorId(), input)); }
    private PortalProperties.Service service(String name) { return switch (name) { case "businessPartner" -> properties.getSap().getBusinessPartner(); case "purchaseOrder" -> properties.getSap().getPurchaseOrder(); case "asn" -> properties.getSap().getAsn(); case "materialDocument" -> properties.getSap().getMaterialDocument(); case "supplierInvoice" -> properties.getSap().getSupplierInvoice(); default -> throw new IllegalArgumentException("未知 SAP 服务。"); }; }
    private void requireConfigured() { String issue = properties.validationIssue(); if (issue != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, issue); }
    private Map<String, Object> metric(String label, Collection<JsonNode> records, String code, String hint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label); result.put("value", records.size()); result.put("code", code); result.put("hint", hint); return result;
    }
    private List<Map<String, Object>> distribution(Collection<JsonNode> records, String... fields) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (JsonNode record : records) {
            String value = firstText(record, fields);
            values.merge(value == null || value.isBlank() ? "未提供状态" : value, 1, Integer::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        values.entrySet().stream().limit(5).forEach(entry -> result.add(Map.of("label", entry.getKey(), "value", entry.getValue())));
        return result;
    }
    private String firstText(JsonNode record, String... fields) { for (String field : fields) if (record.hasNonNull(field)) return record.get(field).asText(); return null; }
    private record Resource(String serviceName, String searchField, String orderBy) { }
}
