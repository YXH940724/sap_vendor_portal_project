package com.yxh.sapvendorportal.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yxh.sapvendorportal.common.cache.VendorDataCache;
import com.yxh.sapvendorportal.common.security.VendorScopeResolver;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.integration.sap.SapODataClient;
import com.yxh.sapvendorportal.mapper.ODataRecordMapper;
import com.yxh.sapvendorportal.service.PortalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class PortalServiceImpl implements PortalService {
    private static final DateTimeFormatter PORTAL_ASN_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final Set<String> GOODS_RECEIPT_MOVEMENT_TYPES = Set.of("101", "102", "122", "123", "161", "162");
    private static final Set<String> SETTLEMENT_RECEIPT_MOVEMENT_TYPES = Set.of("101");
    private static final Set<String> SETTLEMENT_REVERSAL_MOVEMENT_TYPES = Set.of("102", "122");
    private static final Set<String> SETTLEMENT_REVERSAL_CANCELLATION_MOVEMENT_TYPES = Set.of("123");
    private static final Set<String> RETURN_MOVEMENT_TYPES = Set.of("161");
    private static final Set<String> RETURN_REVERSAL_MOVEMENT_TYPES = Set.of("162");
    private final PortalProperties properties;
    private final VendorScopeResolver scopeResolver;
    private final SapODataClient sapClient;
    private final ODataRecordMapper recordMapper;
    private final VendorDataCache vendorDataCache;
    private final Map<String, Resource> resources = Map.of(
            "suppliers", new Resource("businessPartner", "BusinessPartner", "BusinessPartner asc"),
            "purchaseOrders", new Resource("purchaseOrder", "PurchaseOrder", "LastChangeDateTime desc"),
            "asns", new Resource("asn", "DeliveryDocument", "LastChangeDate desc"),
            "materialDocuments", new Resource("materialDocument", "MaterialDocument", "MaterialDocument desc"),
            "invoices", new Resource("supplierInvoice", "SupplierInvoice", "SupplierInvoice desc")
    );
    @Autowired
    public PortalServiceImpl(PortalProperties properties, VendorScopeResolver scopeResolver, SapODataClient sapClient, ODataRecordMapper recordMapper, VendorDataCache vendorDataCache) { this.properties = properties; this.scopeResolver = scopeResolver; this.sapClient = sapClient; this.recordMapper = recordMapper; this.vendorDataCache = vendorDataCache; }
    /** 保留简化构造器供单元测试和嵌入式调用使用。 */
    public PortalServiceImpl(PortalProperties properties, VendorScopeResolver scopeResolver, SapODataClient sapClient, ODataRecordMapper recordMapper) { this(properties, scopeResolver, sapClient, recordMapper, new VendorDataCache(properties)); }

    public Map<String, Object> health() { String issue = properties.validationIssue(); return Map.of("ok", true, "configured", issue == null, "issue", issue == null ? "" : issue); }
    public Map<String, String> session(HttpServletRequest request) { var scope = scopeResolver.resolve(request); return Map.of("vendorId", scope.vendorId(), "identitySource", scope.identitySource(), "storage", "lark_bitable".equals(scope.identitySource()) ? "signed_session" : "stateless_portal"); }
    public Map<String, Object> dashboard(HttpServletRequest request) { return dashboard(request, false); }
    public Map<String, Object> dashboard(HttpServletRequest request, boolean refresh) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        requirePermission(scope, "ORDER_READ");
        if (refresh) vendorDataCache.invalidateVendor(scope.vendorId());
        LoadResult ordersResult = safelyLoad("purchaseOrders", scope.vendorId(), "", 100, List.of());
        List<JsonNode> orders = ordersResult.records();
        List<String> purchaseOrders = purchaseOrderIds(orders);
        CompletableFuture<LoadResult> asnsFuture = CompletableFuture.supplyAsync(() -> safelyLoad("asns", scope.vendorId(), "", 100, purchaseOrders));
        CompletableFuture<LoadResult> receiptsFuture = CompletableFuture.supplyAsync(() -> safelyLoad("materialDocuments", scope.vendorId(), "", 100, purchaseOrders));
        CompletableFuture<LoadResult> invoicesFuture = CompletableFuture.supplyAsync(() -> safelyLoad("invoices", scope.vendorId(), "", 100, purchaseOrders));
        LoadResult asnsResult = asnsFuture.join();
        LoadResult receiptsResult = receiptsFuture.join();
        LoadResult invoicesResult = invoicesFuture.join();
        List<JsonNode> asns = asnsResult.records();
        List<JsonNode> receipts = receiptsResult.records();
        List<JsonNode> invoices = invoicesResult.records();
        List<ReconciliationLine> settlementLines = reconciliationLines(receipts, invoices);
        int settlementCandidateCount = (int) settlementLines.stream()
                .filter(line -> "可结算".equals(line.settlementStatus()))
                .filter(line -> line.remainingQuantity().signum() > 0)
                .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vendorId", scope.vendorId());
        result.put("sampleLimit", 100);
        result.put("metrics", List.of(
                metric("采购订单", orders, "PO", "待确认订单与交期"),
                metric("发运通知", asns, "ASN", "发运与到货节奏"),
                metric("收货凭证", receipts, "GR", "已过账收货记录"),
                metric("可结算收货", settlementCandidateCount, "IV", "可创建预制发票的收货行")
        ));
        result.put("orderStatus", distribution(orders, "PurchaseOrderStatus", "OverallStatus", "Status"));
        result.put("asnStatus", distribution(asns, "OverallStatus", "InbDeliveryStatus", "Status"));
        result.put("settlementStatus", settlementStatusDistribution(settlementLines));
        result.put("purchaseManagementMetrics", purchaseManagementMetrics(orders, receipts));
        result.put("dataIssues", issues(Map.of("采购订单", ordersResult, "ASN / 发运", asnsResult, "收货凭证", receiptsResult, "结算对账", invoicesResult)));
        result.put("retrievedAt", Instant.now().toString());
        return result;
    }
    public Map<String, Object> data(String resourceName, String search, int top, HttpServletRequest request) { return data(resourceName, search, top, request, false); }
    public Map<String, Object> data(String resourceName, String search, int top, HttpServletRequest request, boolean refresh) {
        requireConfigured(); Resource resource = resources.get(resourceName); if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知资源。");
        var scope = scopeResolver.resolve(request); requirePermission(scope, permissionFor(resourceName)); if (refresh) vendorDataCache.invalidateVendor(scope.vendorId()); List<JsonNode> records = load(resourceName, scope.vendorId(), search, top, List.of());
        Map<String, Object> response = new LinkedHashMap<>(); response.put("resource", resourceName); response.put("vendorId", scope.vendorId()); response.put("records", records); response.put("count", records.size()); response.put("retrievedAt", Instant.now().toString()); return response;
    }
    public Map<String, Object> reconciliation(HttpServletRequest request) { return reconciliation(request, false); }
    public Map<String, Object> reconciliation(HttpServletRequest request, boolean refresh) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        requirePermission(scope, "SETTLEMENT_READ");
        if (refresh) vendorDataCache.invalidateVendor(scope.vendorId());
        List<Map<String, Object>> records = reconciliationLines(scope.vendorId(), 100).stream()
                .filter(this::isReconciliationPoolLine)
                .map(ReconciliationLine::view).toList();
        return Map.of("vendorId", scope.vendorId(), "records", records, "count", records.size(), "retrievedAt", Instant.now().toString());
    }
    public Map<String, Object> createAsn(JsonNode input, HttpServletRequest request) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        requirePermission(scope, "ASN_CREATE");
        ObjectNode submission = input instanceof ObjectNode object ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        String portalAsnNumber = portalAsnNumber(submission);
        submission.put("portalAsnNumber", portalAsnNumber);
        validateAsnSources(submission, scope.vendorId());
        JsonNode sapResult = sapClient.createAsn(scope.vendorId(), submission);
        vendorDataCache.invalidateVendor(scope.vendorId());
        JsonNode delivery = sapResult.path("d").isObject() ? sapResult.path("d") : sapResult;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("vendorId", scope.vendorId());
        response.put("portalAsnNumber", portalAsnNumber);
        response.put("sapInboundDelivery", firstText(delivery, "DeliveryDocument", "InbDelivery"));
        response.put("result", sapResult);
        return response;
    }
    public Map<String, Object> createInvoice(JsonNode input, HttpServletRequest request) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        requirePermission(scope, "INVOICE_CREATE");
        ObjectNode validated = validateAndBuildInvoice(input, scope.vendorId());
        Map<String, Object> response = Map.of("vendorId", scope.vendorId(), "result", sapClient.createSupplierInvoice(scope.vendorId(), validated));
        vendorDataCache.invalidateVendor(scope.vendorId());
        return response;
    }
    public List<JsonNode> agentPurchaseOrders(String vendorId) { requireConfigured(); return load("purchaseOrders", vendorId, "", 100, List.of()); }
    public List<JsonNode> agentAsns(String vendorId) { requireConfigured(); return load("asns", vendorId, "", 100, List.of()); }
    /**
     * 查询收货凭证时仍以物料凭证 OData API 为事实来源；采购订单只用于供应商范围收敛和补全订单行字段。
     */
    public List<JsonNode> agentGoodsReceipts(String vendorId) { requireConfigured(); return load("materialDocuments", vendorId, "", 100, List.of()); }
    public List<Map<String, Object>> agentReconciliation(String vendorId) {
        requireConfigured();
        return reconciliationLines(vendorId, 100).stream().filter(line -> line.isSettlementCandidate() && line.receivedQuantity().signum() > 0).map(ReconciliationLine::view).toList();
    }
    private PortalProperties.Service service(String name) { return switch (name) { case "businessPartner" -> properties.getSap().getBusinessPartner(); case "purchaseOrder" -> properties.getSap().getPurchaseOrder(); case "asn" -> properties.getSap().getAsn(); case "outboundDelivery" -> properties.getSap().getOutboundDelivery(); case "material" -> properties.getSap().getMaterial(); case "materialDocument" -> properties.getSap().getMaterialDocument(); case "supplierInvoice" -> properties.getSap().getSupplierInvoice(); default -> throw new IllegalArgumentException("未知 SAP 服务。"); }; }
    private String permissionFor(String resourceName) { return switch (resourceName) { case "purchaseOrders" -> "ORDER_READ"; case "asns" -> "ASN_READ"; case "materialDocuments" -> "GOODS_RECEIPT_READ"; case "invoices" -> "SETTLEMENT_READ"; case "suppliers" -> "SUPPLIER_PROFILE_READ"; default -> "ORDER_READ"; }; }
    private void requirePermission(VendorScopeResolver.VendorScope scope, String permission) { if (!scope.allows(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号未获 " + permission + " 权限。"); }
    private void requireConfigured() { String issue = properties.validationIssue(); if (issue != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, issue); }
    private Map<String, Object> metric(String label, Collection<JsonNode> records, String code, String hint) {
        return metric(label, records.size(), code, hint);
    }
    private Map<String, Object> metric(String label, int value, String code, String hint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label); result.put("value", value); result.put("code", code); result.put("hint", hint); return result;
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
    private List<Map<String, Object>> settlementStatusDistribution(Collection<ReconciliationLine> lines) {
        Map<String, Integer> values = new LinkedHashMap<>();
        lines.stream().filter(this::isReconciliationPoolLine).forEach(line -> values.merge(line.settlementStatus(), 1, Integer::sum));
        return values.entrySet().stream().map(entry -> Map.<String, Object>of("label", entry.getKey(), "value", entry.getValue())).toList();
    }
    private List<Map<String, Object>> purchaseManagementMetrics(List<JsonNode> orders, List<JsonNode> receipts) {
        List<JsonNode> activeOrders = orders.stream().filter(this::isActiveOrderLine).toList();
        long fulfilled = activeOrders.stream().filter(this::isFulfilledOrderLine).count();
        Map<String, LocalDate> actualReceiptDates = latestReceiptDates(receipts);
        long deliveryEligible = activeOrders.stream()
                .filter(this::isFulfilledOrderLine)
                .filter(order -> parseBusinessDate(firstText(order, "DeliveryDate")) != null)
                .filter(order -> actualReceiptDates.containsKey(purchaseOrderLineKey(order)))
                .count();
        long onTime = activeOrders.stream()
                .filter(this::isFulfilledOrderLine)
                .filter(order -> {
                    LocalDate planned = parseBusinessDate(firstText(order, "DeliveryDate"));
                    LocalDate actual = actualReceiptDates.get(purchaseOrderLineKey(order));
                    return planned != null && actual != null && !actual.isAfter(planned);
                }).count();
        return List.of(
                rateMetric("供应商交付准时率", onTime, deliveryEligible,
                        "按时完成交付行 ÷ 已完成且具有交期与实际收货日期的订单行 × 100%",
                        "实际收货日期取 SAP 收货凭证（101）的过账日期，并与订单交期比较。"),
                rateMetric("采购订单履约率", fulfilled, activeOrders.size(),
                        "已完成有效订单行 ÷ 全部有效订单行 × 100%",
                        "有效订单行不含已取消行；已完成取完全交付标识或已收货数量达到订单数量。")
        );
    }
    private Map<String, Object> rateMetric(String label, long numerator, long denominator, String formula, String note) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("value", denominator == 0 ? "—" : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%");
        result.put("numerator", numerator);
        result.put("denominator", denominator);
        result.put("formula", formula);
        result.put("note", note);
        return result;
    }
    private Map<String, LocalDate> latestReceiptDates(Collection<JsonNode> receipts) {
        Map<String, LocalDate> dates = new LinkedHashMap<>();
        for (JsonNode receipt : receipts) {
            if (!"101".equals(firstNonBlankText(receipt, "GoodsMovementType"))) continue;
            LocalDate postingDate = receiptPostingDate(receipt);
            if (postingDate == null) continue;
            dates.merge(purchaseOrderLineKey(receipt), postingDate, (left, right) -> left.isAfter(right) ? left : right);
        }
        return dates;
    }
    private LocalDate receiptPostingDate(JsonNode receipt) {
        LocalDate date = parseBusinessDate(firstNonBlankText(receipt, "PostingDate", "DocumentDate"));
        if (date != null) return date;
        JsonNode header = firstObject(receipt.path("to_MaterialDocumentHeader"));
        return header == null ? null : parseBusinessDate(firstNonBlankText(header, "PostingDate", "DocumentDate"));
    }
    private LocalDate parseBusinessDate(String rawDate) {
        if (rawDate == null || rawDate.length() < 10) return null;
        try { return LocalDate.parse(rawDate.substring(0, 10)); }
        catch (Exception ignored) { return null; }
    }
    private boolean isActiveOrderLine(JsonNode order) {
        return firstText(order, "PurchasingDocumentDeletionCode").isBlank() && !normalizedStatus(firstText(order, "PurchaseOrderStatus")).contains("已取消");
    }
    private boolean isFulfilledOrderLine(JsonNode order) {
        if (isCompletelyDelivered(order)) return true;
        BigDecimal ordered = firstDecimal(order, "OrderQuantity", "PurchaseOrderQuantity", "RequestedQuantity");
        BigDecimal received = firstDecimal(order, "ReceivedQuantity");
        return ordered != null && ordered.signum() > 0 && received != null && received.compareTo(ordered) >= 0;
    }
    private String normalizedStatus(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }
    private List<JsonNode> load(String resourceName, String vendorId, String search, int top, List<String> purchaseOrders) {
        String references = purchaseOrders == null || purchaseOrders.isEmpty() ? "" : String.join(",", purchaseOrders.stream().sorted().toList());
        String variant = "top=" + top + ";search=" + (search == null ? "" : search) + ";orders=" + references;
        return vendorDataCache.get(resourceName, vendorId, variant, () -> loadUncached(resourceName, vendorId, search, top, purchaseOrders));
    }
    private List<JsonNode> loadUncached(String resourceName, String vendorId, String search, int top, List<String> purchaseOrders) {
        Resource resource = resources.get(resourceName);
        if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知资源。");
        if ("purchaseOrders".equals(resourceName)) return loadPurchaseOrders(vendorId, top);
        if ("suppliers".equals(resourceName)) return loadSupplierProfile(vendorId, search, top);
        PortalProperties.Service target = service(resource.serviceName());
        if ("asns".equals(resourceName)) {
            return loadDeliveryDocuments(vendorId, search, top, loadPurchaseOrders(vendorId, top));
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
        return vendorDataCache.get("purchaseOrders", vendorId, "top=" + top, () -> loadPurchaseOrdersUncached(vendorId, top));
    }
    private List<JsonNode> loadPurchaseOrdersUncached(String vendorId, int top) {
        List<JsonNode> headers = sapClient.get(service("purchaseOrder"), vendorId, "", "PurchaseOrder", "LastChangeDateTime desc", top);
        List<String> ids = purchaseOrderIds(headers);
        if (ids.isEmpty()) return List.of();
        List<JsonNode> items = recordMapper.map("purchaseOrders", sapClient.getByReferences(purchaseOrderItemService(), ids, "PurchaseOrder", "PurchaseOrder asc", top));
        items = enrichWithSubcontractingComponents(items, ids, top);
        Map<String, JsonNode> headerByOrder = new LinkedHashMap<>();
        headers.forEach(header -> headerByOrder.put(header.path("PurchaseOrder").asText(), header));
        List<JsonNode> orderLines = items.stream().map(item -> enrichPurchaseOrderItem(item, headerByOrder.get(item.path("PurchaseOrder").asText()))).toList();
        return enrichWithReceiptProgress(enrichWithDeliveryDocuments(orderLines, vendorId, top), ids, top);
    }
    private PortalProperties.Service purchaseOrderItemService() {
        PortalProperties.Service itemService = new PortalProperties.Service();
        itemService.setUrl(properties.getSap().getPurchaseOrder().getUrl());
        itemService.setEntity("PurchaseOrderItem");
        itemService.setExpand("_PurchaseOrderScheduleLineTP,_DeliveryAddress");
        return itemService;
    }
    private List<JsonNode> enrichWithSubcontractingComponents(List<JsonNode> orderLines, List<String> purchaseOrders, int top) {
        if (orderLines.isEmpty() || purchaseOrders.isEmpty()) return orderLines;
        PortalProperties.Service componentService = new PortalProperties.Service();
        componentService.setUrl(properties.getSap().getPurchaseOrder().getUrl()); componentService.setEntity("POSubcontractingComponent"); componentService.setReferenceField("PurchaseOrder");
        List<JsonNode> components;
        try { components = recordMapper.map("subcontractingComponents", sapClient.getByReferences(componentService, purchaseOrders, "PurchaseOrder", "PurchaseOrder asc", top)); }
        catch (ResponseStatusException ignored) { return orderLines; }
        components = enrichSubcontractingComponentDescriptions(components, top);
        Map<String, ArrayNode> componentsByOrderLine = new LinkedHashMap<>();
        for (JsonNode component : components) {
            String key = purchaseOrderLineKey(component);
            if (!":".equals(key)) componentsByOrderLine.computeIfAbsent(key, ignored -> JsonNodeFactory.instance.arrayNode()).add(component);
        }
        return orderLines.stream().map(line -> {
            if (!line.isObject()) return line;
            ObjectNode result = ((ObjectNode) line).deepCopy();
            ArrayNode lineComponents = componentsByOrderLine.get(purchaseOrderLineKey(line));
            if (lineComponents != null) result.set("SubcontractingComponents", lineComponents.deepCopy());
            return result;
        }).toList();
    }
    private List<JsonNode> enrichSubcontractingComponentDescriptions(List<JsonNode> components, int top) {
        List<String> materialIds = components.stream().filter(JsonNode::isObject)
                .filter(component -> firstText(component, "description").isBlank())
                .map(component -> firstText(component, "material")).filter(value -> !value.isBlank()).distinct().toList();
        if (materialIds.isEmpty()) return components;
        List<JsonNode> materialRecords;
        try {
            PortalProperties.Service materialService = service("material");
            materialRecords = sapClient.getByReferences(materialService, materialIds, materialService.getReferenceField(), "Product asc", top);
        } catch (ResponseStatusException ignored) { return components; }
        if (materialRecords == null || materialRecords.isEmpty()) return components;
        Map<String, String> descriptionsByMaterial = new LinkedHashMap<>();
        for (JsonNode material : materialRecords) {
            String code = firstText(material, "Product", "Material");
            String description = firstText(material, "ProductDescription", "MaterialDescription", "Description");
            if (!code.isBlank() && !description.isBlank()) descriptionsByMaterial.putIfAbsent(code, description);
        }
        if (descriptionsByMaterial.isEmpty()) return components;
        return components.stream().map(component -> {
            if (!component.isObject() || !firstText(component, "description").isBlank()) return component;
            String description = descriptionsByMaterial.get(firstText(component, "material"));
            if (description == null) return component;
            ObjectNode result = ((ObjectNode) component).deepCopy();
            result.put("description", description);
            return result;
        }).toList();
    }
    private List<JsonNode> loadSupplierProfile(String vendorId, String search, int top) {
        return vendorDataCache.get("supplierProfile", vendorId, "top=" + top + ";search=" + (search == null ? "" : search), () -> loadSupplierProfileUncached(vendorId, search, top));
    }
    private List<JsonNode> loadSupplierProfileUncached(String vendorId, String search, int top) {
        PortalProperties.Service businessPartner = service("businessPartner");
        PortalProperties.Service supplierMaster = new PortalProperties.Service();
        supplierMaster.setUrl(businessPartner.getUrl()); supplierMaster.setEntity("A_Supplier"); supplierMaster.setSupplierField("Supplier");
        PortalProperties.Service supplierCompany = new PortalProperties.Service();
        supplierCompany.setUrl(businessPartner.getUrl()); supplierCompany.setEntity("A_SupplierCompany"); supplierCompany.setSupplierField("Supplier");
        PortalProperties.Service supplierBank = new PortalProperties.Service();
        supplierBank.setUrl(businessPartner.getUrl()); supplierBank.setEntity("A_BusinessPartnerBank"); supplierBank.setSupplierField("BusinessPartner");
        PortalProperties.Service contactRelationship = new PortalProperties.Service();
        contactRelationship.setUrl(businessPartner.getUrl()); contactRelationship.setEntity("A_BusinessPartnerContact"); contactRelationship.setSupplierField("BusinessPartnerCompany");
        CompletableFuture<List<JsonNode>> partnersFuture = CompletableFuture.supplyAsync(() -> recordMapper.map("suppliers", sapClient.get(businessPartner, vendorId, search, "BusinessPartner", "BusinessPartner asc", top)));
        CompletableFuture<List<JsonNode>> supplierMastersFuture = CompletableFuture.supplyAsync(() -> sapClient.get(supplierMaster, vendorId, "", "Supplier", "Supplier asc", top));
        CompletableFuture<List<JsonNode>> companiesFuture = CompletableFuture.supplyAsync(() -> recordMapper.map("supplierCompanies", sapClient.get(supplierCompany, vendorId, "", "Supplier", "CompanyCode asc", top)));
        CompletableFuture<List<JsonNode>> banksFuture = CompletableFuture.supplyAsync(() -> recordMapper.map("supplierBanks", sapClient.get(supplierBank, vendorId, "", "BusinessPartner", "BankIdentification asc", top)));
        CompletableFuture<List<JsonNode>> relationshipsFuture = CompletableFuture.supplyAsync(() -> recordMapper.map("businessPartnerContacts", sapClient.get(contactRelationship, vendorId, "", "BusinessPartnerPerson", "BusinessPartnerPerson asc", top)));
        List<JsonNode> partners = partnersFuture.join();
        List<JsonNode> supplierMasters = supplierMastersFuture.join();
        List<JsonNode> companies = companiesFuture.join();
        List<JsonNode> banks = banksFuture.join();
        List<JsonNode> relationships = relationshipsFuture.join();
        List<String> contactIds = relationships.stream().map(contact -> firstText(contact, "ContactPerson", "BusinessPartnerPerson")).filter(id -> !id.isBlank()).distinct().toList();
        PortalProperties.Service contactPerson = new PortalProperties.Service();
        contactPerson.setUrl(businessPartner.getUrl()); contactPerson.setEntity("A_BusinessPartner"); contactPerson.setExpand("to_BusinessPartnerAddress,to_BusinessPartnerAddress/to_EmailAddress,to_BusinessPartnerAddress/to_PhoneNumber");
        List<JsonNode> people = contactIds.isEmpty() ? List.of() : recordMapper.map("businessPartnerPersons", sapClient.getByReferences(contactPerson, contactIds, "BusinessPartner", "BusinessPartner asc", top));
        Map<String, ArrayNode> companiesBySupplier = new LinkedHashMap<>();
        for (JsonNode company : companies) {
            String supplier = firstText(company, "Supplier", "BusinessPartner");
            if (!supplier.isBlank()) companiesBySupplier.computeIfAbsent(supplier, ignored -> JsonNodeFactory.instance.arrayNode()).add(company);
        }
        Map<String, JsonNode> peopleById = new LinkedHashMap<>();
        people.forEach(person -> peopleById.put(firstText(person, "BusinessPartner", "ContactPerson"), person));
        Map<String, JsonNode> supplierMasterBySupplier = new LinkedHashMap<>();
        supplierMasters.forEach(supplier -> supplierMasterBySupplier.put(firstText(supplier, "Supplier", "BusinessPartner"), supplier));
        ArrayNode contacts = JsonNodeFactory.instance.arrayNode();
        for (JsonNode relationship : relationships) {
            String personId = firstText(relationship, "ContactPerson", "BusinessPartnerPerson");
            JsonNode person = peopleById.get(personId);
            if (person == null) continue;
            ObjectNode contact = contacts.addObject();
            contact.put("ContactPerson", personId);
            copyText(contact, person, "ContactName", "ContactName");
            copyText(contact, person, "EmailAddress", "EmailAddress");
            copyText(contact, person, "PhoneNumber", "PhoneNumber");
            copyText(contact, person, "PhoneNumberExtension", "PhoneNumberExtension");
        }
        return partners.stream().map(partner -> {
            if (!partner.isObject()) return partner;
            ObjectNode result = ((ObjectNode) partner).deepCopy();
            JsonNode supplierMasterRecord = supplierMasterBySupplier.get(firstText(result, "Supplier", "BusinessPartner"));
            if (supplierMasterRecord != null && !firstText(supplierMasterRecord, "TaxNumber5").isBlank()) result.put("TaxNumber5", firstText(supplierMasterRecord, "TaxNumber5"));
            ArrayNode supplierCompanies = companiesBySupplier.get(firstText(result, "Supplier", "BusinessPartner"));
            if (supplierCompanies != null) result.set("SupplierCompanies", supplierCompanies.deepCopy());
            ArrayNode supplierBanks = JsonNodeFactory.instance.arrayNode();
            banks.stream().filter(bank -> vendorId.equals(firstText(bank, "BusinessPartner", "Supplier"))).forEach(supplierBanks::add);
            if (!supplierBanks.isEmpty()) result.set("SupplierBanks", supplierBanks);
            if (!contacts.isEmpty()) result.set("Contacts", contacts.deepCopy());
            return result;
        }).toList();
    }
    private JsonNode enrichPurchaseOrderItem(JsonNode item, JsonNode header) {
        if (!item.isObject()) return item;
        ObjectNode result = ((ObjectNode) item).deepCopy();
        if (header != null) {
            for (String field : List.of("Supplier", "SupplierName", "CompanyCode", "PurchasingOrganization", "PurchaseOrderDate", "DocumentCurrency")) {
                if (!result.has(field) && header.has(field)) result.set(field, header.get(field));
            }
            copyAddress(result, header, "_SupplierAddress", "SupplierAddress");
            copySupplierName(result, header);
        }
        copyAddress(result, result, "_DeliveryAddress", "DeliveryAddress");
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
        for (String field : List.of("Material", "MaterialDescription", "PurchaseOrderQuantityUnit", "OrderQuantity", "CompanyCode", "DocumentCurrency", "SupplierName", "NetPriceAmount", "NetPriceQuantity", "TaxCode", "InbDelivery", "PurchasingItemIsFreeOfCharge", "PurchaseOrderItemCategory", "IsReturnsItem", "ReturnsItem", "ReturnsIndicator", "IsCompletelyDelivered", "OrderType", "SupplierAddressStreetName", "SupplierAddressHouseNumber", "SupplierAddressBuilding", "SupplierAddressFloor", "SupplierAddressRoomNumber", "SupplierAddressPostalCode", "SupplierAddressCityName", "SupplierAddressRegion", "SupplierAddressCountry", "DeliveryAddressStreetName", "DeliveryAddressHouseNumber", "DeliveryAddressBuilding", "DeliveryAddressFloor", "DeliveryAddressRoomNumber", "DeliveryAddressPostalCode", "DeliveryAddressCityName", "DeliveryAddressRegion", "DeliveryAddressCountry")) {
            JsonNode source = orderLine.path(field);
            if ((!result.has(field) || result.path(field).asText().isBlank()) && !source.isMissingNode() && !source.isNull() && !source.asText().isBlank()) result.set(field, source);
        }
        return result;
    }
    private void copyAddress(ObjectNode target, JsonNode source, String navigationProperty, String prefix) {
        JsonNode address = firstObject(source.path(navigationProperty));
        if (address == null) return;
        for (String field : List.of("StreetName", "HouseNumber", "Building", "Floor", "RoomNumber", "PostalCode", "CityName", "Region", "Country")) {
            String value = firstText(address, field);
            if (!value.isBlank() && (!target.has(prefix + field) || target.path(prefix + field).asText().isBlank())) target.put(prefix + field, value);
        }
    }
    private void copySupplierName(ObjectNode target, JsonNode source) {
        if (target.hasNonNull("SupplierName") && !target.path("SupplierName").asText().isBlank()) return;
        JsonNode address = firstObject(source.path("_SupplierAddress"));
        if (address == null) return;
        String supplierName = firstText(address, "FullName", "OrganizationName1", "OrganizationName", "BusinessPartnerFullName", "AddressFullName", "Name");
        if (!supplierName.isBlank()) target.put("SupplierName", supplierName);
    }
    private JsonNode firstObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isObject()) {
            if (node.path("value").isArray() && !node.path("value").isEmpty()) return node.path("value").get(0);
            if (node.path("results").isArray() && !node.path("results").isEmpty()) return node.path("results").get(0);
            return node;
        }
        return node.isArray() && !node.isEmpty() ? node.get(0) : null;
    }
    private List<JsonNode> loadDeliveryDocuments(String vendorId, String search, int top, List<JsonNode> orderLines) {
        String orders = String.join(",", purchaseOrderIds(orderLines).stream().sorted().toList());
        return vendorDataCache.get("deliveryDocuments", vendorId, "top=" + top + ";search=" + (search == null ? "" : search) + ";orders=" + orders, () -> loadDeliveryDocumentsUncached(vendorId, search, top, orderLines));
    }
    private List<JsonNode> loadDeliveryDocumentsUncached(String vendorId, String search, int top, List<JsonNode> orderLines) {
        boolean hasStandardOrders = orderLines.stream().anyMatch(line -> !isReturnPurchaseOrder(line));
        List<JsonNode> inbound;
        if (!hasStandardOrders) inbound = List.of();
        else try { inbound = recordMapper.map("asns", sapClient.get(service("asn"), vendorId, search, "DeliveryDocument", "LastChangeDate desc", top)); }
        catch (ResponseStatusException ignored) { inbound = List.of(); }
        List<JsonNode> enrichedInbound = enrichWithPurchaseOrderItems(inbound, orderLines).stream().filter(line -> !isReturnPurchaseOrder(line)).toList();
        List<String> returnOrders = orderLines.stream().filter(this::isReturnPurchaseOrder).map(line -> firstText(line, "PurchaseOrder")).filter(value -> !value.isBlank()).distinct().toList();
        if (returnOrders.isEmpty()) return enrichedInbound;
        List<JsonNode> outbound;
        try {
            PortalProperties.Service target = service("outboundDelivery");
            outbound = recordMapper.map("outboundDeliveries", sapClient.getByReferences(target, returnOrders, target.getReferenceField(), "DeliveryDocument desc", top));
        } catch (ResponseStatusException ignored) { outbound = List.of(); }
        List<JsonNode> enrichedOutbound = enrichWithPurchaseOrderItems(outbound, orderLines).stream().filter(this::isReturnPurchaseOrder).toList();
        List<JsonNode> records = new ArrayList<>(enrichedInbound);
        records.addAll(enrichedOutbound);
        return records;
    }
    private List<JsonNode> enrichWithDeliveryDocuments(List<JsonNode> orderLines, String vendorId, int top) {
        if (orderLines.isEmpty()) return orderLines;
        List<JsonNode> deliveries = loadDeliveryDocuments(vendorId, "", top, orderLines);
        Map<String, String> deliveryByOrderLine = new LinkedHashMap<>();
        Map<String, BigDecimal> asnQuantityByOrderLine = new LinkedHashMap<>();
        for (JsonNode deliveryRecord : deliveries) {
            String key = purchaseOrderLineKey(deliveryRecord);
            String delivery = firstText(deliveryRecord, "DeliveryDocument", "InbDelivery");
            if (!":".equals(key) && !delivery.isBlank()) deliveryByOrderLine.putIfAbsent(key, delivery);
            BigDecimal quantity = firstDecimal(deliveryRecord, "ActualDeliveryQuantity", "DeliveryQuantity", "ActualQuantity");
            if (!":".equals(key) && quantity != null) asnQuantityByOrderLine.merge(key, quantity, BigDecimal::add);
        }
        return orderLines.stream().map(line -> {
            if (!line.isObject()) return line;
            ObjectNode result = ((ObjectNode) line).deepCopy();
            String delivery = deliveryByOrderLine.get(purchaseOrderLineKey(line));
            if (delivery != null && (result.path("InbDelivery").asText().isBlank())) result.put("InbDelivery", delivery);
            if (delivery != null && (result.path("DeliveryDocument").asText().isBlank())) result.put("DeliveryDocument", delivery);
            String key = purchaseOrderLineKey(line);
            result.put("HasAsn", deliveryByOrderLine.containsKey(key));
            result.put("CreatedAsnQuantity", decimal(asnQuantityByOrderLine.getOrDefault(key, BigDecimal.ZERO)));
            return result;
        }).toList();
    }
    private List<JsonNode> enrichWithReceiptProgress(List<JsonNode> orderLines, List<String> purchaseOrders, int top) {
        if (orderLines.isEmpty() || purchaseOrders.isEmpty()) return orderLines;
        List<JsonNode> receiptMovements;
        try {
            PortalProperties.Service materialDocumentService = service("materialDocument");
            receiptMovements = recordMapper.map("materialDocuments", sapClient.getByReferences(materialDocumentService, purchaseOrders, materialDocumentService.getReferenceField(), "MaterialDocument desc", top));
        } catch (ResponseStatusException ignored) { return orderLines; }
        Map<String, BigDecimal> receivedByOrderLine = new LinkedHashMap<>();
        Map<String, Boolean> returnOrderByOrderLine = new LinkedHashMap<>();
        orderLines.forEach(line -> returnOrderByOrderLine.put(purchaseOrderLineKey(line), isReturnPurchaseOrder(line)));
        for (JsonNode receipt : receiptMovements) {
            if (!isGoodsReceiptMovement(receipt)) continue;
            BigDecimal quantity = firstDecimal(receipt, "QuantityInEntryUnit", "Quantity", "EntryQuantity");
            if (quantity == null) continue;
            String key = purchaseOrderLineKey(receipt);
            String movementType = firstText(receipt, "GoodsMovementType");
            BigDecimal sign = Boolean.TRUE.equals(returnOrderByOrderLine.get(key))
                    ? switch (movementType) { case "161" -> BigDecimal.ONE; case "162" -> BigDecimal.ONE.negate(); default -> BigDecimal.ZERO; }
                    : switch (movementType) { case "101", "123" -> BigDecimal.ONE; case "102", "122" -> BigDecimal.ONE.negate(); default -> BigDecimal.ZERO; };
            if (sign.signum() != 0) receivedByOrderLine.merge(key, quantity.multiply(sign), BigDecimal::add);
        }
        return orderLines.stream().map(line -> {
            if (!line.isObject()) return line;
            ObjectNode result = ((ObjectNode) line).deepCopy();
            BigDecimal received = receivedByOrderLine.getOrDefault(purchaseOrderLineKey(line), BigDecimal.ZERO).max(BigDecimal.ZERO);
            BigDecimal ordered = firstDecimal(line, "OrderQuantity", "PurchaseOrderQuantity", "RequestedQuantity");
            result.put("ReceivedQuantity", decimal(received));
            if (ordered != null) {
                boolean completelyDelivered = isCompletelyDelivered(line);
                BigDecimal open = completelyDelivered ? BigDecimal.ZERO : ordered.subtract(received).max(BigDecimal.ZERO);
                result.put("OpenReceiptQuantity", decimal(open));
                BigDecimal createdAsnQuantity = firstDecimal(result, "CreatedAsnQuantity");
                BigDecimal unclearedAsnQuantity = (createdAsnQuantity == null ? BigDecimal.ZERO : createdAsnQuantity)
                        .subtract(received)
                        .max(BigDecimal.ZERO);
                // 可发运量 = 订单数量 - 已收货数量 - 未清 ASN 数量。
                // 同一订单行上，收货会优先结清已创建的 ASN；因此未清 ASN 以 ASN 累计数量扣除净收货数量计算。
                BigDecimal asnAvailableQuantity = completelyDelivered ? BigDecimal.ZERO : ordered.subtract(received).subtract(unclearedAsnQuantity).max(BigDecimal.ZERO);
                result.put("UnclearedAsnQuantity", decimal(unclearedAsnQuantity));
                result.put("AsnAvailableQuantity", decimal(asnAvailableQuantity));
                if (completelyDelivered) result.put("PurchaseOrderStatus", "已完成");
                else if (result.path("PurchasingDocumentDeletionCode").asText().isBlank()) {
                    if (received.signum() > 0 && received.compareTo(ordered) >= 0) result.put("PurchaseOrderStatus", "已完成");
                    else if (received.signum() > 0) result.put("PurchaseOrderStatus", "部分收货");
                }
            }
            return result;
        }).toList();
    }
    private List<ReconciliationLine> reconciliationLines(String vendorId, int top) {
        List<JsonNode> receipts = load("materialDocuments", vendorId, "", top, List.of());
        List<JsonNode> invoices = load("invoices", vendorId, "", top, List.of());
        return reconciliationLines(receipts, invoices);
    }
    private List<ReconciliationLine> reconciliationLines(List<JsonNode> receipts, List<JsonNode> invoices) {
        Map<String, ReconciliationLine> linesByReceipt = new LinkedHashMap<>();
        for (JsonNode receipt : receipts) {
            ReconciliationLine line = reconciliationLine(receipt);
            if (!line.receiptKey().isBlank()) linesByReceipt.put(line.receiptKey(), line);
        }
        applySettlementReversals(linesByReceipt);
        applyReturnBalances(linesByReceipt);
        Map<String, BigDecimal> settledByReceipt = new LinkedHashMap<>();
        Map<String, BigDecimal> unsettledByPurchaseOrderLine = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> invoiceReferencesByPurchaseOrderLine = new LinkedHashMap<>();
        for (JsonNode invoice : invoices) {
            BigDecimal quantity = invoiceQuantity(invoice);
            if (quantity == null || quantity.signum() == 0) continue;
            String purchaseOrderLineKey = purchaseOrderLineKey(invoice);
            String invoiceReference = invoiceReference(invoice);
            if (!invoiceReference.isBlank()) invoiceReferencesByPurchaseOrderLine.computeIfAbsent(purchaseOrderLineKey, ignored -> new LinkedHashSet<>()).add(invoiceReference);
            String receiptKey = receiptReferenceKey(invoice);
            if (!receiptKey.isBlank() && linesByReceipt.containsKey(receiptKey)) settledByReceipt.merge(receiptKey, quantity, BigDecimal::add);
            else unsettledByPurchaseOrderLine.merge(purchaseOrderLineKey, quantity, BigDecimal::add);
        }
        Map<String, List<String>> receiptKeysByPurchaseOrderLine = new LinkedHashMap<>();
        linesByReceipt.values().forEach(line -> receiptKeysByPurchaseOrderLine.computeIfAbsent(line.purchaseOrderLineKey(), ignored -> new ArrayList<>()).add(line.receiptKey()));
        for (List<String> receiptKeys : receiptKeysByPurchaseOrderLine.values()) {
            BigDecimal unallocated = unsettledByPurchaseOrderLine.getOrDefault(linesByReceipt.get(receiptKeys.getFirst()).purchaseOrderLineKey(), BigDecimal.ZERO);
            for (String receiptKey : receiptKeys) {
                ReconciliationLine line = linesByReceipt.get(receiptKey);
                BigDecimal exactSettled = settledByReceipt.getOrDefault(receiptKey, BigDecimal.ZERO);
                if (line.canSettle() && exactSettled.signum() == 0 && unallocated.signum() > 0) {
                    BigDecimal allocated = line.receivedQuantity().min(unallocated);
                    exactSettled = allocated;
                    unallocated = unallocated.subtract(allocated);
                }
                String settlementInvoices = String.join(", ", invoiceReferencesByPurchaseOrderLine.getOrDefault(line.purchaseOrderLineKey(), new LinkedHashSet<>()));
                linesByReceipt.put(receiptKey, line.withSettledQuantity(exactSettled.max(BigDecimal.ZERO)).withSettlementInvoices(settlementInvoices));
            }
        }
        return new ArrayList<>(linesByReceipt.values());
    }
    private boolean isReconciliationPoolLine(ReconciliationLine line) {
        return line.isSettlementCandidate() && line.receivedQuantity().signum() > 0;
    }
    private ReconciliationLine reconciliationLine(JsonNode receipt) {
        String document = firstText(receipt, "MaterialDocument");
        String year = firstText(receipt, "MaterialDocumentYear");
        String item = firstText(receipt, "MaterialDocumentItem");
        String purchaseOrder = firstText(receipt, "PurchaseOrder");
        String purchaseOrderItem = firstText(receipt, "PurchaseOrderItem");
        String receiptKey = document + ":" + year + ":" + canonicalItemNumber(item);
        BigDecimal quantity = firstDecimal(receipt, "QuantityInEntryUnit", "Quantity", "EntryQuantity");
        if (quantity == null) quantity = BigDecimal.ZERO;
        String entryUnit = firstText(receipt, "EntryUnit", "QuantityUnit", "BaseUnit");
        String purchaseOrderUnit = firstText(receipt, "PurchaseOrderQuantityUnit");
        boolean unitConsistent = entryUnit.isBlank() || purchaseOrderUnit.isBlank() || entryUnit.equalsIgnoreCase(purchaseOrderUnit);
        return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, document, year, item,
                firstText(receipt, "Material"), firstText(receipt, "MaterialDescription"), firstText(receipt, "PostingDate"),
                firstText(receipt, "GoodsMovementType"), entryUnit, purchaseOrderUnit, firstText(receipt, "CompanyCode"),
                firstText(receipt, "DocumentCurrency"), firstText(receipt, "TaxCode"), firstDecimal(receipt, "NetPriceAmount"),
                firstDecimal(receipt, "NetPriceQuantity"), quantity, BigDecimal.ZERO, BigDecimal.ZERO, "", unitConsistent, isFreePurchaseOrder(receipt));
    }
    private void applySettlementReversals(Map<String, ReconciliationLine> linesByReceipt) {
        Map<String, List<ReconciliationLine>> linesByOrderLine = new LinkedHashMap<>();
        linesByReceipt.values().forEach(line -> linesByOrderLine.computeIfAbsent(line.purchaseOrderLineKey(), ignored -> new ArrayList<>()).add(line));
        for (List<ReconciliationLine> lines : linesByOrderLine.values()) {
            BigDecimal reversed = lines.stream().filter(line -> SETTLEMENT_REVERSAL_MOVEMENT_TYPES.contains(line.goodsMovementType())).map(ReconciliationLine::receivedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reversalCanceled = lines.stream().filter(line -> SETTLEMENT_REVERSAL_CANCELLATION_MOVEMENT_TYPES.contains(line.goodsMovementType())).map(ReconciliationLine::receivedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal netReversal = reversed.subtract(reversalCanceled).max(BigDecimal.ZERO);
            for (int index = lines.size() - 1; index >= 0 && netReversal.signum() > 0; index--) {
                ReconciliationLine line = lines.get(index);
                if (line.receivedQuantity().signum() <= 0 || !SETTLEMENT_RECEIPT_MOVEMENT_TYPES.contains(line.goodsMovementType())) continue;
                BigDecimal offset = line.receivedQuantity().min(netReversal);
                ReconciliationLine adjusted = line.withReceivedQuantity(line.receivedQuantity().subtract(offset));
                linesByReceipt.put(adjusted.receiptKey(), adjusted);
                netReversal = netReversal.subtract(offset);
            }
        }
    }
    private void applyReturnBalances(Map<String, ReconciliationLine> linesByReceipt) {
        Map<String, List<ReconciliationLine>> linesByOrderLine = new LinkedHashMap<>();
        linesByReceipt.values().forEach(line -> linesByOrderLine.computeIfAbsent(line.purchaseOrderLineKey(), ignored -> new ArrayList<>()).add(line));
        for (List<ReconciliationLine> lines : linesByOrderLine.values()) {
            BigDecimal returned = lines.stream().filter(line -> RETURN_MOVEMENT_TYPES.contains(line.goodsMovementType())).map(ReconciliationLine::receivedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reversalCanceled = lines.stream().filter(line -> RETURN_REVERSAL_MOVEMENT_TYPES.contains(line.goodsMovementType())).map(ReconciliationLine::receivedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remainingReturn = returned.subtract(reversalCanceled).max(BigDecimal.ZERO);
            for (ReconciliationLine line : lines) {
                if (!RETURN_MOVEMENT_TYPES.contains(line.goodsMovementType())) continue;
                BigDecimal actualReturn = line.receivedQuantity().min(remainingReturn);
                linesByReceipt.put(line.receiptKey(), line.withActualReturnQuantity(actualReturn));
                remainingReturn = remainingReturn.subtract(actualReturn);
            }
        }
    }
    private BigDecimal invoiceQuantity(JsonNode invoice) {
        BigDecimal quantity = firstDecimal(invoice, "QuantityInPurchaseOrderUnit", "SupplierInvoiceItemQuantity", "Quantity", "QuantityInEntryUnit");
        if (quantity == null) return null;
        return invoice.path("SupplierInvoiceIsCreditMemo").asBoolean(false) ? quantity.negate() : quantity;
    }
    private String receiptReferenceKey(JsonNode invoice) {
        String document = firstText(invoice, "ReferenceDocument", "MaterialDocument", "GoodsReceiptDocument");
        if (document.isBlank()) return "";
        return document + ":" + firstText(invoice, "ReferenceDocumentYear", "ReferenceDocumentFiscalYear", "MaterialDocumentYear") + ":" + canonicalItemNumber(firstText(invoice, "ReferenceDocumentItem", "MaterialDocumentItem", "GoodsReceiptDocumentItem"));
    }
    private String invoiceReference(JsonNode invoice) {
        String number = firstText(invoice, "SupplierInvoice", "SupplierInvoiceID", "InvoiceNumber");
        if (number.isBlank()) return "";
        String year = firstText(invoice, "FiscalYear");
        return year.isBlank() ? number : number + "/" + year;
    }
    private BigDecimal firstDecimal(JsonNode record, String... fields) {
        for (String field : fields) {
            String value = record.path(field).asText();
            if (!value.isBlank()) try { return new BigDecimal(value); } catch (NumberFormatException ignored) { }
        }
        return null;
    }
    private ObjectNode validateAndBuildInvoice(JsonNode input, String vendorId) {
        String invoiceReference = input.path("invoiceReference").asText().trim();
        String documentDate = input.path("documentDate").asText().trim();
        String postingDate = input.path("postingDate").asText().trim();
        String taxDeterminationDate = input.path("taxDeterminationDate").asText().trim();
        if (taxDeterminationDate.isBlank()) taxDeterminationDate = documentDate;
        if (invoiceReference.isBlank() || documentDate.isBlank() || postingDate.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写供应商发票号、凭证日期和过账日期。");
        Map<String, ReconciliationLine> available = new LinkedHashMap<>();
        reconciliationLines(vendorId, 100).forEach(line -> available.put(line.receiptKey(), line));
        BigDecimal grossAmount = firstDecimal(input, "grossAmount");
        BigDecimal netAmount = firstDecimal(input, "netAmount");
        BigDecimal taxAmount = firstDecimal(input, "taxAmount");
        String headerText = input.path("headerText").asText().trim();
        if (grossAmount == null || grossAmount.signum() <= 0 || netAmount == null || netAmount.signum() < 0 || taxAmount == null || taxAmount.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写不小于零的本次不含税金额、税额，以及大于零的本次含税金额。 ");
        List<InvoiceSelection> selections = new ArrayList<>();
        Map<String, BigDecimal> requestedByReceipt = new LinkedHashMap<>();
        input.path("items").forEach(item -> {
            String receiptKey = item.path("receiptKey").asText().trim();
            BigDecimal quantity = firstDecimal(item, "quantity");
            if (receiptKey.isBlank() || quantity == null || quantity.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每个发票行必须包含收货来源和大于零的结算数量。");
            requestedByReceipt.merge(receiptKey, quantity, BigDecimal::add);
            selections.add(new InvoiceSelection(receiptKey, quantity));
        });
        if (selections.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一条可结算的收货凭证行。");
        for (Map.Entry<String, BigDecimal> entry : requestedByReceipt.entrySet()) {
            ReconciliationLine source = available.get(entry.getKey());
            if (source == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "所选收货凭证不属于当前供应商或已不可结算。");
            if (!source.canSettle() || source.remainingQuantity().signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货凭证 " + source.materialDocument() + " 当前不可结算：" + source.settlementStatus() + "。");
            if (entry.getValue().compareTo(source.remainingQuantity()) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货凭证 " + source.materialDocument() + " 的提交数量超过实时可结算数量 " + decimal(source.remainingQuantity()) + "。");
        }
        ReconciliationLine first = available.get(selections.getFirst().receiptKey());
        if (first.companyCode().isBlank() || first.documentCurrency().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货来源缺少公司代码或币种，不能创建发票。");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("invoiceReference", invoiceReference); result.put("documentDate", documentDate); result.put("postingDate", postingDate); result.put("taxDeterminationDate", taxDeterminationDate); result.put("companyCode", first.companyCode()); result.put("documentCurrency", first.documentCurrency());
        ArrayNode items = result.putArray("items");
        List<BigDecimal> sapNetAmounts = new ArrayList<>();
        for (InvoiceSelection selection : selections) {
            ReconciliationLine source = available.get(selection.receiptKey());
            if (!first.companyCode().equals(source.companyCode()) || !first.documentCurrency().equals(source.documentCurrency())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次发票只能选择相同公司代码和币种的收货凭证行。");
            if (source.materialDocument().isBlank() || source.materialDocumentYear().isBlank() || source.materialDocumentItem().isBlank() || source.purchaseOrder().isBlank() || source.purchaseOrderItem().isBlank() || source.purchaseOrderUnit().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货来源缺少凭证、采购订单或单位，不能创建收货引用发票。");
            sapNetAmounts.add(source.expectedInvoiceNetAmount(selection.quantity()));
        }
        BigDecimal sapNetAmount = sapNetAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> adjustedNetAmounts = allocateAdjustedNetAmounts(sapNetAmounts, netAmount.setScale(2, RoundingMode.HALF_UP), sapNetAmount);
        BigDecimal expectedGrossAmount = netAmount.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
        if (grossAmount.setScale(2, RoundingMode.HALF_UP).compareTo(expectedGrossAmount) != 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本次含税金额必须等于本次不含税金额加税额。预计含税金额为 " + expectedGrossAmount.toPlainString() + "。");
        for (int index = 0; index < selections.size(); index++) {
            InvoiceSelection selection = selections.get(index);
            ReconciliationLine source = available.get(selection.receiptKey());
            BigDecimal lineAmount = adjustedNetAmounts.get(index);
            ObjectNode item = items.addObject();
            item.put("sourceMaterialDocument", source.materialDocument()); item.put("sourceMaterialDocumentYear", source.materialDocumentYear()); item.put("sourceMaterialDocumentItem", source.materialDocumentItem()); item.put("sourcePurchaseOrder", source.purchaseOrder()); item.put("sourcePurchaseOrderItem", source.purchaseOrderItem()); item.put("quantity", selection.quantity()); item.put("amount", lineAmount); item.put("unit", source.purchaseOrderUnit());
            if (!source.taxCode().isBlank()) item.put("taxCode", source.taxCode());
        }
        result.put("sapNetAmount", sapNetAmount); result.put("netAmount", netAmount.setScale(2, RoundingMode.HALF_UP)); result.put("taxAmount", taxAmount.setScale(2, RoundingMode.HALF_UP)); result.put("grossAmount", expectedGrossAmount); if (!headerText.isBlank()) result.put("headerText", headerText);
        return result;
    }
    private List<BigDecimal> allocateAdjustedNetAmounts(List<BigDecimal> sapAmounts, BigDecimal targetNetAmount, BigDecimal sapNetAmount) {
        if (sapAmounts.isEmpty()) return List.of();
        if (targetNetAmount.compareTo(sapNetAmount) == 0) return sapAmounts.stream().map(amount -> amount.setScale(2, RoundingMode.HALF_UP)).toList();
        if (sapNetAmount.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SAP 不含税金额合计为零，不能按金额占比调整。 ");
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal remaining = targetNetAmount;
        for (int index = 0; index < sapAmounts.size(); index++) {
            BigDecimal adjusted = index == sapAmounts.size() - 1 ? remaining : sapAmounts.get(index).multiply(targetNetAmount).divide(sapNetAmount, 2, RoundingMode.HALF_UP);
            result.add(adjusted);
            remaining = remaining.subtract(adjusted);
        }
        return result;
    }
    private String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private String purchaseOrderLineKey(JsonNode record) { return record.path("PurchaseOrder").asText().trim() + ":" + canonicalItemNumber(record.path("PurchaseOrderItem").asText()); }
    private static String canonicalItemNumber(String value) {
        String item = value == null ? "" : value.trim();
        return item.replaceFirst("^0+(?!$)", "");
    }
    private boolean isGoodsReceiptMovement(JsonNode record) { return GOODS_RECEIPT_MOVEMENT_TYPES.contains(record.path("GoodsMovementType").asText()); }
    private boolean isReturnPurchaseOrder(JsonNode line) {
        for (String field : List.of("ReturnsItem", "IsReturnsItem", "ReturnsIndicator")) {
            JsonNode value = line.path(field);
            if (value.asBoolean(false) || "X".equalsIgnoreCase(value.asText()) || "true".equalsIgnoreCase(value.asText())) return true;
        }
        return false;
    }
    private boolean isFreePurchaseOrder(JsonNode line) { return booleanField(line, "PurchasingItemIsFreeOfCharge"); }
    private boolean isCompletelyDelivered(JsonNode line) { return booleanField(line, "IsCompletelyDelivered"); }
    private boolean booleanField(JsonNode record, String... fields) { for (String field : fields) { JsonNode value = record.path(field); if (value.asBoolean(false) || "X".equalsIgnoreCase(value.asText()) || "true".equalsIgnoreCase(value.asText())) return true; } return false; }
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
        input.path("items").forEach(item -> {
            String purchaseOrder = item.path("sourcePurchaseOrder").asText();
            String purchaseOrderItem = item.path("sourcePurchaseOrderItem").asText();
            if (!purchaseOrder.isBlank()) requested.add(purchaseOrder);
            BigDecimal quantity = firstDecimal(item, "quantity");
            if (purchaseOrder.isBlank() || purchaseOrderItem.isBlank() || quantity == null || quantity.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每个 ASN 行必须包含订单、行号和大于零的发运数量。");
        });
        if (requested.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个采购订单行后再创建 ASN。");
        List<JsonNode> orderLines = loadPurchaseOrders(vendorId, 100);
        List<String> allowed = purchaseOrderIds(orderLines);
        if (!allowed.containsAll(requested)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "所选采购订单不属于当前供应商，已拒绝创建 ASN。");
        Map<String, JsonNode> orderLinesByKey = new LinkedHashMap<>();
        orderLines.forEach(line -> orderLinesByKey.put(purchaseOrderLineKey(line), line));
        input.path("items").forEach(item -> {
            String requestedLineKey = item.path("sourcePurchaseOrder").asText().trim() + ":" + canonicalItemNumber(item.path("sourcePurchaseOrderItem").asText());
            JsonNode source = orderLinesByKey.get(requestedLineKey);
            if (source == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "所选订单行不属于当前供应商，已拒绝创建 ASN。");
            if (isReturnPurchaseOrder(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退货订单行不能创建 ASN。");
            if (isCompletelyDelivered(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单行已标记完全交付，不能创建 ASN。");
            BigDecimal available = firstDecimal(source, "AsnAvailableQuantity");
            BigDecimal requestedQuantity = firstDecimal(item, "quantity");
            if (available != null && requestedQuantity != null && requestedQuantity.compareTo(available) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ASN 发运数量超过订单行实时可发运量 " + decimal(available) + "。");
        });
    }
    private String portalAsnNumber(JsonNode input) {
        String supplied = input.path("portalAsnNumber").asText().trim();
        if (!supplied.isBlank()) {
            if (!supplied.matches("[A-Za-z0-9_-]{1,35}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Portal ASN 单号仅支持字母、数字、连字符和下划线，且不超过 35 位。");
            return supplied;
        }
        return "PASN-" + PORTAL_ASN_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    private void copyText(ObjectNode target, JsonNode source, String sourceField, String targetField) { String value = source.path(sourceField).asText(); if (!value.isBlank()) target.put(targetField, value); }
    private String firstText(JsonNode record, String... fields) { for (String field : fields) if (record.hasNonNull(field)) return record.get(field).asText(); return ""; }
    private String firstNonBlankText(JsonNode record, String... fields) {
        for (String field : fields) {
            if (!record.hasNonNull(field)) continue;
            String value = record.get(field).asText().trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }
    private record ReconciliationLine(String receiptKey, String purchaseOrder, String purchaseOrderItem, String materialDocument, String materialDocumentYear, String materialDocumentItem, String material, String materialDescription, String postingDate, String goodsMovementType, String entryUnit, String purchaseOrderUnit, String companyCode, String documentCurrency, String taxCode, BigDecimal netPriceAmount, BigDecimal netPriceQuantity, BigDecimal receivedQuantity, BigDecimal settledQuantity, BigDecimal actualReturnQuantity, String settlementInvoices, boolean unitConsistent, boolean freeOfCharge) {
        ReconciliationLine withReceivedQuantity(BigDecimal value) { return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, materialDocument, materialDocumentYear, materialDocumentItem, material, materialDescription, postingDate, goodsMovementType, entryUnit, purchaseOrderUnit, companyCode, documentCurrency, taxCode, netPriceAmount, netPriceQuantity, value, settledQuantity, actualReturnQuantity, settlementInvoices, unitConsistent, freeOfCharge); }
        ReconciliationLine withSettledQuantity(BigDecimal value) { return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, materialDocument, materialDocumentYear, materialDocumentItem, material, materialDescription, postingDate, goodsMovementType, entryUnit, purchaseOrderUnit, companyCode, documentCurrency, taxCode, netPriceAmount, netPriceQuantity, receivedQuantity, value, actualReturnQuantity, settlementInvoices, unitConsistent, freeOfCharge); }
        ReconciliationLine withActualReturnQuantity(BigDecimal value) { return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, materialDocument, materialDocumentYear, materialDocumentItem, material, materialDescription, postingDate, goodsMovementType, entryUnit, purchaseOrderUnit, companyCode, documentCurrency, taxCode, netPriceAmount, netPriceQuantity, receivedQuantity, settledQuantity, value, settlementInvoices, unitConsistent, freeOfCharge); }
        ReconciliationLine withSettlementInvoices(String value) { return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, materialDocument, materialDocumentYear, materialDocumentItem, material, materialDescription, postingDate, goodsMovementType, entryUnit, purchaseOrderUnit, companyCode, documentCurrency, taxCode, netPriceAmount, netPriceQuantity, receivedQuantity, settledQuantity, actualReturnQuantity, value, unitConsistent, freeOfCharge); }
        String purchaseOrderLineKey() { return purchaseOrder + ":" + canonicalItemNumber(purchaseOrderItem); }
        boolean canSettle() { return !freeOfCharge && receivedQuantity.signum() > 0 && unitConsistent && SETTLEMENT_RECEIPT_MOVEMENT_TYPES.contains(goodsMovementType); }
        boolean isSettlementCandidate() { return !freeOfCharge && unitConsistent && SETTLEMENT_RECEIPT_MOVEMENT_TYPES.contains(goodsMovementType); }
        BigDecimal expectedInvoiceNetAmount(BigDecimal quantity) {
            if (netPriceAmount == null || netPriceQuantity == null || netPriceQuantity.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货来源缺少 SAP 净价或价格单位，不能校验发票金额。");
            return quantity.multiply(netPriceAmount).divide(netPriceQuantity, 2, RoundingMode.HALF_UP);
        }
        BigDecimal remainingQuantity() { return canSettle() ? receivedQuantity.subtract(settledQuantity).max(BigDecimal.ZERO) : BigDecimal.ZERO; }
        String settlementStatus() { if (freeOfCharge) return "免费订单（无需结算）"; if (RETURN_MOVEMENT_TYPES.contains(goodsMovementType)) return "退货"; if (RETURN_REVERSAL_MOVEMENT_TYPES.contains(goodsMovementType)) return "退货冲销"; if ("102".equals(goodsMovementType)) return "收货冲销"; if ("122".equals(goodsMovementType)) return "部分冲销"; if ("123".equals(goodsMovementType)) return "部分冲销冲销"; if (!unitConsistent) return "单位不一致"; return remainingQuantity().signum() > 0 ? "可结算" : "已结算"; }
        Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("receiptKey", receiptKey); result.put("purchaseOrder", purchaseOrder); result.put("purchaseOrderItem", purchaseOrderItem); result.put("materialDocument", materialDocument); result.put("materialDocumentYear", materialDocumentYear); result.put("materialDocumentItem", materialDocumentItem); result.put("material", material); result.put("materialDescription", materialDescription); result.put("postingDate", postingDate); result.put("goodsMovementType", goodsMovementType); result.put("unit", entryUnit); result.put("purchaseOrderUnit", purchaseOrderUnit); result.put("companyCode", companyCode); result.put("documentCurrency", documentCurrency); result.put("taxCode", taxCode); result.put("netPriceAmount", decimal(netPriceAmount)); result.put("netPriceQuantity", decimal(netPriceQuantity)); result.put("receivedQuantity", decimal(receivedQuantity)); result.put("settledQuantity", decimal(settledQuantity)); result.put("actualReturnQuantity", decimal(actualReturnQuantity)); result.put("remainingQuantity", decimal(remainingQuantity())); result.put("settlementInvoices", settlementInvoices); result.put("settlementStatus", settlementStatus()); return result;
        }
        private String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    }
    private record InvoiceSelection(String receiptKey, BigDecimal quantity) { }
    private record Resource(String serviceName, String searchField, String orderBy) { }
    private record LoadResult(List<JsonNode> records, String issue) { }
}
