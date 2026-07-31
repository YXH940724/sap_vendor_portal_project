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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PortalController {
    private final PortalProperties properties;
    private final VendorScopeResolver scopeResolver;
    private final SapODataClient sapClient;
    private final Map<String, Resource> resources = Map.of(
            "suppliers", new Resource("businessPartner", "BusinessPartner", "BusinessPartner asc"),
            "purchaseOrders", new Resource("purchaseOrder", "PurchaseOrder", "LastChangeDateTime desc"),
            "asns", new Resource("asn", "InbDelivery", "LastChangeDateTime desc"),
            "materialDocuments", new Resource("materialDocument", "MaterialDocument", "LastChangeDateTime desc"),
            "invoices", new Resource("supplierInvoice", "SupplierInvoice", "LastChangeDateTime desc")
    );
    public PortalController(PortalProperties properties, VendorScopeResolver scopeResolver, SapODataClient sapClient) { this.properties = properties; this.scopeResolver = scopeResolver; this.sapClient = sapClient; }

    @GetMapping("/health") public Map<String, Object> health() { String issue = properties.validationIssue(); return Map.of("ok", true, "configured", issue == null, "issue", issue == null ? "" : issue); }
    @GetMapping("/session") public Map<String, String> session(HttpServletRequest request) { var scope = scopeResolver.resolve(request); return Map.of("vendorId", scope.vendorId(), "identitySource", scope.identitySource(), "storage", "stateless_portal"); }
    @GetMapping("/data/{resourceName}") public Map<String, Object> data(@PathVariable String resourceName, @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "30") int top, HttpServletRequest request) {
        requireConfigured(); Resource resource = resources.get(resourceName); if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知资源。");
        var scope = scopeResolver.resolve(request); List<JsonNode> records = sapClient.get(service(resource.serviceName()), scope.vendorId(), search, resource.searchField(), resource.orderBy(), top);
        Map<String, Object> response = new LinkedHashMap<>(); response.put("resource", resourceName); response.put("vendorId", scope.vendorId()); response.put("records", records); response.put("count", records.size()); response.put("retrievedAt", Instant.now().toString()); return response;
    }
    @PostMapping("/asns") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> createAsn(@RequestBody JsonNode input, HttpServletRequest request) { requireConfigured(); var scope = scopeResolver.resolve(request); return Map.of("vendorId", scope.vendorId(), "result", sapClient.createAsn(scope.vendorId(), input)); }
    private PortalProperties.Service service(String name) { return switch (name) { case "businessPartner" -> properties.getSap().getBusinessPartner(); case "purchaseOrder" -> properties.getSap().getPurchaseOrder(); case "asn" -> properties.getSap().getAsn(); case "materialDocument" -> properties.getSap().getMaterialDocument(); case "supplierInvoice" -> properties.getSap().getSupplierInvoice(); default -> throw new IllegalArgumentException("未知 SAP 服务。"); }; }
    private void requireConfigured() { String issue = properties.validationIssue(); if (issue != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, issue); }
    private record Resource(String serviceName, String searchField, String orderBy) { }
}
