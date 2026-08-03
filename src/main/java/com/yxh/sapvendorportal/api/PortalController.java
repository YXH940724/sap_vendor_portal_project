package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class PortalController {
    private static final Set<String> GOODS_RECEIPT_MOVEMENT_TYPES = Set.of("101", "102", "122", "123", "161", "162");
    private final PortalProperties properties;
    private final VendorScopeResolver scopeResolver;
    private final SapODataClient sapClient;
    private final ODataRecordMapper recordMapper;
    private final Map<String, Resource> resources = Map.of(
            "suppliers", new Resource("businessPartner", "BusinessPartner", "BusinessPartner asc"),
            "purchaseOrders", new Resource("purchaseOrder", "PurchaseOrder", "LastChangeDateTime desc"),
            "asns", new Resource("asn", "InbDelivery", "LastChangeDate desc"),
            "materialDocuments", new Resource("materialDocument", "MaterialDocument", "MaterialDocument desc"),
            "invoices", new Resource("supplierInvoice", "SupplierInvoice", "SupplierInvoice desc")
    );
    public PortalController(PortalProperties properties, VendorScopeResolver scopeResolver, SapODataClient sapClient, ODataRecordMapper recordMapper) { this.properties = properties; this.scopeResolver = scopeResolver; this.sapClient = sapClient; this.recordMapper = recordMapper; }

    @GetMapping("/health") public Map<String, Object> health() { String issue = properties.validationIssue(); return Map.of("ok", true, "configured", issue == null, "issue", issue == null ? "" : issue); }
    @GetMapping("/session") public Map<String, String> session(HttpServletRequest request) { var scope = scopeResolver.resolve(request); return Map.of("vendorId", scope.vendorId(), "identitySource", scope.identitySource(), "storage", "stateless_portal"); }
    @GetMapping("/dashboard") public Map<String, Object> dashboard(HttpServletRequest request) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        LoadResult ordersResult = safelyLoad("purchaseOrders", scope.vendorId(), "", 100, List.of());
        List<JsonNode> orders = ordersResult.records();
        List<String> purchaseOrders = purchaseOrderIds(orders);
        LoadResult asnsResult = safelyLoad("asns", scope.vendorId(), "", 100, purchaseOrders);
        LoadResult receiptsResult = safelyLoad("materialDocuments", scope.vendorId(), "", 100, purchaseOrders);
        LoadResult invoicesResult = safelyLoad("invoices", scope.vendorId(), "", 100, purchaseOrders);
        List<JsonNode> asns = asnsResult.records();
        List<JsonNode> receipts = receiptsResult.records();
        List<JsonNode> invoices = invoicesResult.records();
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
        result.put("dataIssues", issues(Map.of("采购订单", ordersResult, "ASN / 发运", asnsResult, "收货凭证", receiptsResult, "结算对账", invoicesResult)));
        result.put("retrievedAt", Instant.now().toString());
        return result;
    }
    @GetMapping("/data/{resourceName}") public Map<String, Object> data(@PathVariable String resourceName, @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "30") int top, HttpServletRequest request) {
        requireConfigured(); Resource resource = resources.get(resourceName); if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知资源。");
        var scope = scopeResolver.resolve(request); List<JsonNode> records = load(resourceName, scope.vendorId(), search, top, List.of());
        Map<String, Object> response = new LinkedHashMap<>(); response.put("resource", resourceName); response.put("vendorId", scope.vendorId()); response.put("records", records); response.put("count", records.size()); response.put("retrievedAt", Instant.now().toString()); return response;
    }
    @PostMapping("/asns") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> createAsn(@RequestBody JsonNode input, HttpServletRequest request) {
        requireConfigured(); var scope = scopeResolver.resolve(request); validateAsnSources(input, scope.vendorId());
        return Map.of("vendorId", scope.vendorId(), "result", sapClient.createAsn(scope.vendorId(), input));
    }
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
    private List<JsonNode> load(String resourceName, String vendorId, String search, int top, List<String> purchaseOrders) {
        Resource resource = resources.get(resourceName);
        if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知资源。");
        if ("purchaseOrders".equals(resourceName)) return loadPurchaseOrders(vendorId, top);
        PortalProperties.Service target = service(resource.serviceName());
        if ("asns".equals(resourceName)) {
            List<JsonNode> asns = recordMapper.map(resourceName, sapClient.get(target, vendorId, search, resource.searchField(), resource.orderBy(), top));
            return enrichWithPurchaseOrderItems(asns, vendorId, top);
        }
        if ("invoices".equals(resourceName)) {
            List<JsonNode> orderLines = loadPurchaseOrders(vendorId, top);
            List<String> scopedOrders = purchaseOrders.isEmpty() ? purchaseOrderIds(orderLines) : purchaseOrders;
            if (scopedOrders.isEmpty()) return List.of();
            List<JsonNode> invoices = recordMapper.map(resourceName, sapClient.get(target, vendorId, search, resource.searchField(), resource.orderBy(), top));
            return enrichWithPurchaseOrderItems(invoices.stream().filter(invoice -> scopedOrders.contains(invoice.path("PurchaseOrder").asText())).toList(), orderLines);
        }
        if ("purchase_order".equals(target.getScopeMode())) {
            List<JsonNode> orderLines = loadPurchaseOrders(vendorId, top);
            List<String> scopedOrders = purchaseOrders.isEmpty() ? purchaseOrderIds(orderLines) : purchaseOrders;
            if (scopedOrders.isEmpty()) return List.of();
            List<JsonNode> records = recordMapper.map(resourceName, sapClient.getByReferences(target, scopedOrders, target.getReferenceField(), resource.orderBy(), top));
            if ("materialDocuments".equals(resourceName)) {
                return enrichWithPurchaseOrderItems(records.stream().filter(this::isGoodsReceiptMovement).toList(), orderLines);
            }
            return records;
        }
        return recordMapper.map(resourceName, sapClient.get(target, vendorId, search, resource.searchField(), resource.orderBy(), top));
    }
    private List<JsonNode> loadPurchaseOrders(String vendorId, int top) {
        List<JsonNode> headers = sapClient.get(service("purchaseOrder"), vendorId, "", "PurchaseOrder", "LastChangeDateTime desc", top);
        List<String> ids = purchaseOrderIds(headers);
        if (ids.isEmpty()) return List.of();
        List<JsonNode> items = recordMapper.map("purchaseOrders", sapClient.getByReferences(purchaseOrderItemService(), ids, "PurchaseOrder", "PurchaseOrder asc", top));
        Map<String, JsonNode> headerByOrder = new LinkedHashMap<>();
        headers.forEach(header -> headerByOrder.put(header.path("PurchaseOrder").asText(), header));
        return items.stream().map(item -> enrichPurchaseOrderItem(item, headerByOrder.get(item.path("PurchaseOrder").asText()))).toList();
    }
    private PortalProperties.Service purchaseOrderItemService() {
        PortalProperties.Service itemService = new PortalProperties.Service();
        itemService.setUrl(properties.getSap().getPurchaseOrder().getUrl());
        itemService.setEntity("PurchaseOrderItem");
        itemService.setExpand("_PurchaseOrderScheduleLineTP");
        return itemService;
    }
    private JsonNode enrichPurchaseOrderItem(JsonNode item, JsonNode header) {
        if (header == null || !item.isObject()) return item;
        ObjectNode result = ((ObjectNode) item).deepCopy();
        for (String field : List.of("Supplier", "CompanyCode", "PurchasingOrganization", "PurchaseOrderDate", "DocumentCurrency")) {
            if (!result.has(field) && header.has(field)) result.set(field, header.get(field));
        }
        return result;
    }
    private List<JsonNode> enrichWithPurchaseOrderItems(List<JsonNode> records, String vendorId, int top) {
        return enrichWithPurchaseOrderItems(records, loadPurchaseOrders(vendorId, top));
    }
    private List<JsonNode> enrichWithPurchaseOrderItems(List<JsonNode> records, List<JsonNode> orderLines) {
        Map<String, JsonNode> linesByKey = new LinkedHashMap<>();
        orderLines.forEach(line -> linesByKey.put(purchaseOrderLineKey(line), line));
        return records.stream().map(record -> enrichWithPurchaseOrderItem(record, linesByKey.get(purchaseOrderLineKey(record)))).toList();
    }
    private JsonNode enrichWithPurchaseOrderItem(JsonNode record, JsonNode orderLine) {
        if (orderLine == null || !record.isObject()) return record;
        ObjectNode result = ((ObjectNode) record).deepCopy();
        for (String field : List.of("Material", "MaterialDescription", "PurchaseOrderQuantityUnit", "OrderQuantity")) {
            JsonNode source = orderLine.path(field);
            if ((!result.has(field) || result.path(field).asText().isBlank()) && !source.isMissingNode() && !source.isNull() && !source.asText().isBlank()) result.set(field, source);
        }
        return result;
    }
    private String purchaseOrderLineKey(JsonNode record) { return record.path("PurchaseOrder").asText().trim() + ":" + canonicalItemNumber(record.path("PurchaseOrderItem").asText()); }
    private String canonicalItemNumber(String value) {
        String item = value == null ? "" : value.trim();
        return item.replaceFirst("^0+(?!$)", "");
    }
    private boolean isGoodsReceiptMovement(JsonNode record) { return GOODS_RECEIPT_MOVEMENT_TYPES.contains(record.path("GoodsMovementType").asText()); }
    private LoadResult safelyLoad(String resourceName, String vendorId, String search, int top, List<String> purchaseOrders) {
        try { return new LoadResult(load(resourceName, vendorId, search, top, purchaseOrders), ""); }
        catch (ResponseStatusException exception) { return new LoadResult(List.of(), exception.getReason() == null ? "SAP 数据读取失败。" : exception.getReason()); }
    }
    private List<String> purchaseOrderIds(Collection<JsonNode> orders) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        orders.forEach(order -> { String value = order.path("PurchaseOrder").asText(); if (!value.isBlank()) values.add(value); });
        return new ArrayList<>(values);
    }
    private List<Map<String, String>> issues(Map<String, LoadResult> results) {
        List<Map<String, String>> result = new ArrayList<>();
        results.forEach((name, value) -> { if (!value.issue().isBlank()) result.add(Map.of("name", name, "message", value.issue())); });
        return result;
    }
    private void validateAsnSources(JsonNode input, String vendorId) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        input.path("sourcePurchaseOrders").forEach(value -> { if (!value.asText().isBlank()) requested.add(value.asText()); });
        input.path("items").forEach(item -> { String purchaseOrder = item.path("sourcePurchaseOrder").asText(); if (!purchaseOrder.isBlank()) requested.add(purchaseOrder); });
        if (requested.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个采购订单行后再创建 ASN。");
        List<String> allowed = purchaseOrderIds(loadPurchaseOrders(vendorId, 100));
        if (!allowed.containsAll(requested)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "所选采购订单不属于当前供应商，已拒绝创建 ASN。");
    }
    private String firstText(JsonNode record, String... fields) { for (String field : fields) if (record.hasNonNull(field)) return record.get(field).asText(); return null; }
    private record Resource(String serviceName, String searchField, String orderBy) { }
    private record LoadResult(List<JsonNode> records, String issue) { }
}
