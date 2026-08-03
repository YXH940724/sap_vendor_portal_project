package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.sap.SapODataClient;
import com.yxh.sapvendorportal.security.VendorScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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
    private static final Set<String> RETURN_MOVEMENT_TYPES = Set.of("102", "122", "161");
    private static final Set<String> RETURN_REVERSAL_MOVEMENT_TYPES = Set.of("162");
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
    @GetMapping("/reconciliation") public Map<String, Object> reconciliation(HttpServletRequest request) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        List<Map<String, Object>> records = reconciliationLines(scope.vendorId(), 100).stream().map(ReconciliationLine::view).toList();
        return Map.of("vendorId", scope.vendorId(), "records", records, "count", records.size(), "retrievedAt", Instant.now().toString());
    }
    @PostMapping("/asns") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> createAsn(@RequestBody JsonNode input, HttpServletRequest request) {
        requireConfigured(); var scope = scopeResolver.resolve(request); validateAsnSources(input, scope.vendorId());
        return Map.of("vendorId", scope.vendorId(), "result", sapClient.createAsn(scope.vendorId(), input));
    }
    @PostMapping("/invoices") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> createInvoice(@RequestBody JsonNode input, HttpServletRequest request) {
        requireConfigured();
        var scope = scopeResolver.resolve(request);
        ObjectNode validated = validateAndBuildInvoice(input, scope.vendorId());
        return Map.of("vendorId", scope.vendorId(), "result", sapClient.createSupplierInvoice(scope.vendorId(), validated));
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
        for (String field : List.of("Material", "MaterialDescription", "PurchaseOrderQuantityUnit", "OrderQuantity", "CompanyCode", "DocumentCurrency", "NetPriceAmount", "NetPriceQuantity", "TaxCode")) {
            JsonNode source = orderLine.path(field);
            if ((!result.has(field) || result.path(field).asText().isBlank()) && !source.isMissingNode() && !source.isNull() && !source.asText().isBlank()) result.set(field, source);
        }
        return result;
    }
    private List<ReconciliationLine> reconciliationLines(String vendorId, int top) {
        List<JsonNode> receipts = load("materialDocuments", vendorId, "", top, List.of());
        List<JsonNode> invoices = load("invoices", vendorId, "", top, List.of());
        Map<String, ReconciliationLine> linesByReceipt = new LinkedHashMap<>();
        for (JsonNode receipt : receipts) {
            ReconciliationLine line = reconciliationLine(receipt);
            if (!line.receiptKey().isBlank()) linesByReceipt.put(line.receiptKey(), line);
        }
        applyReturns(linesByReceipt);
        Map<String, BigDecimal> settledByReceipt = new LinkedHashMap<>();
        Map<String, BigDecimal> unsettledByPurchaseOrderLine = new LinkedHashMap<>();
        for (JsonNode invoice : invoices) {
            BigDecimal quantity = invoiceQuantity(invoice);
            if (quantity == null || quantity.signum() == 0) continue;
            String receiptKey = receiptReferenceKey(invoice);
            if (!receiptKey.isBlank() && linesByReceipt.containsKey(receiptKey)) settledByReceipt.merge(receiptKey, quantity, BigDecimal::add);
            else unsettledByPurchaseOrderLine.merge(purchaseOrderLineKey(invoice), quantity, BigDecimal::add);
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
                linesByReceipt.put(receiptKey, line.withSettledQuantity(exactSettled.max(BigDecimal.ZERO)));
            }
        }
        return new ArrayList<>(linesByReceipt.values());
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
        if (RETURN_MOVEMENT_TYPES.contains(firstText(receipt, "GoodsMovementType"))) quantity = quantity.negate();
        String entryUnit = firstText(receipt, "EntryUnit", "QuantityUnit", "BaseUnit");
        String purchaseOrderUnit = firstText(receipt, "PurchaseOrderQuantityUnit");
        boolean unitConsistent = entryUnit.isBlank() || purchaseOrderUnit.isBlank() || entryUnit.equalsIgnoreCase(purchaseOrderUnit);
        return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, document, year, item,
                firstText(receipt, "Material"), firstText(receipt, "MaterialDescription"), firstText(receipt, "PostingDate"),
                firstText(receipt, "GoodsMovementType"), entryUnit, purchaseOrderUnit, firstText(receipt, "CompanyCode"),
                firstText(receipt, "DocumentCurrency"), firstText(receipt, "TaxCode"), firstDecimal(receipt, "NetPriceAmount"),
                firstDecimal(receipt, "NetPriceQuantity"), quantity, BigDecimal.ZERO, unitConsistent);
    }
    private void applyReturns(Map<String, ReconciliationLine> linesByReceipt) {
        Map<String, List<ReconciliationLine>> linesByOrderLine = new LinkedHashMap<>();
        linesByReceipt.values().forEach(line -> linesByOrderLine.computeIfAbsent(line.purchaseOrderLineKey(), ignored -> new ArrayList<>()).add(line));
        for (List<ReconciliationLine> lines : linesByOrderLine.values()) {
            BigDecimal returned = lines.stream().filter(line -> RETURN_MOVEMENT_TYPES.contains(line.goodsMovementType())).map(line -> line.receivedQuantity().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reversed = lines.stream().filter(line -> RETURN_REVERSAL_MOVEMENT_TYPES.contains(line.goodsMovementType())).map(ReconciliationLine::receivedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal netReturn = returned.subtract(reversed).max(BigDecimal.ZERO);
            for (int index = lines.size() - 1; index >= 0 && netReturn.signum() > 0; index--) {
                ReconciliationLine line = lines.get(index);
                if (line.receivedQuantity().signum() <= 0 || RETURN_MOVEMENT_TYPES.contains(line.goodsMovementType()) || RETURN_REVERSAL_MOVEMENT_TYPES.contains(line.goodsMovementType())) continue;
                BigDecimal offset = line.receivedQuantity().min(netReturn);
                ReconciliationLine adjusted = line.withReceivedQuantity(line.receivedQuantity().subtract(offset));
                linesByReceipt.put(adjusted.receiptKey(), adjusted);
                netReturn = netReturn.subtract(offset);
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
        if (invoiceReference.isBlank() || documentDate.isBlank() || postingDate.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写供应商发票号、凭证日期和过账日期。");
        Map<String, ReconciliationLine> available = new LinkedHashMap<>();
        reconciliationLines(vendorId, 100).forEach(line -> available.put(line.receiptKey(), line));
        List<InvoiceSelection> selections = new ArrayList<>();
        Map<String, BigDecimal> requestedByReceipt = new LinkedHashMap<>();
        input.path("items").forEach(item -> {
            String receiptKey = item.path("receiptKey").asText().trim();
            BigDecimal quantity = firstDecimal(item, "quantity");
            BigDecimal amount = firstDecimal(item, "amount");
            if (receiptKey.isBlank() || quantity == null || quantity.signum() <= 0 || amount == null || amount.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每个发票行必须包含收货来源、结算数量和含税金额，且均大于零。");
            requestedByReceipt.merge(receiptKey, quantity, BigDecimal::add);
            selections.add(new InvoiceSelection(receiptKey, quantity, amount));
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
        result.put("invoiceReference", invoiceReference); result.put("documentDate", documentDate); result.put("postingDate", postingDate); result.put("companyCode", first.companyCode()); result.put("documentCurrency", first.documentCurrency());
        ArrayNode items = result.putArray("items");
        BigDecimal grossAmount = BigDecimal.ZERO;
        for (InvoiceSelection selection : selections) {
            ReconciliationLine source = available.get(selection.receiptKey());
            if (!first.companyCode().equals(source.companyCode()) || !first.documentCurrency().equals(source.documentCurrency())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次发票只能选择相同公司代码和币种的收货凭证行。");
            if (source.materialDocument().isBlank() || source.materialDocumentYear().isBlank() || source.materialDocumentItem().isBlank() || source.purchaseOrder().isBlank() || source.purchaseOrderItem().isBlank() || source.purchaseOrderUnit().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货来源缺少凭证、采购订单或单位字段，不能创建收货引用发票。");
            ObjectNode item = items.addObject();
            item.put("sourceMaterialDocument", source.materialDocument()); item.put("sourceMaterialDocumentYear", source.materialDocumentYear()); item.put("sourceMaterialDocumentItem", source.materialDocumentItem()); item.put("sourcePurchaseOrder", source.purchaseOrder()); item.put("sourcePurchaseOrderItem", source.purchaseOrderItem()); item.put("quantity", selection.quantity()); item.put("amount", selection.amount()); item.put("unit", source.purchaseOrderUnit());
            if (!source.taxCode().isBlank()) item.put("taxCode", source.taxCode());
            grossAmount = grossAmount.add(selection.amount());
        }
        result.put("grossAmount", grossAmount);
        return result;
    }
    private String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private String purchaseOrderLineKey(JsonNode record) { return record.path("PurchaseOrder").asText().trim() + ":" + canonicalItemNumber(record.path("PurchaseOrderItem").asText()); }
    private static String canonicalItemNumber(String value) {
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
    private String firstText(JsonNode record, String... fields) { for (String field : fields) if (record.hasNonNull(field)) return record.get(field).asText(); return ""; }
    private record ReconciliationLine(String receiptKey, String purchaseOrder, String purchaseOrderItem, String materialDocument, String materialDocumentYear, String materialDocumentItem, String material, String materialDescription, String postingDate, String goodsMovementType, String entryUnit, String purchaseOrderUnit, String companyCode, String documentCurrency, String taxCode, BigDecimal netPriceAmount, BigDecimal netPriceQuantity, BigDecimal receivedQuantity, BigDecimal settledQuantity, boolean unitConsistent) {
        ReconciliationLine withReceivedQuantity(BigDecimal value) { return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, materialDocument, materialDocumentYear, materialDocumentItem, material, materialDescription, postingDate, goodsMovementType, entryUnit, purchaseOrderUnit, companyCode, documentCurrency, taxCode, netPriceAmount, netPriceQuantity, value, settledQuantity, unitConsistent); }
        ReconciliationLine withSettledQuantity(BigDecimal value) { return new ReconciliationLine(receiptKey, purchaseOrder, purchaseOrderItem, materialDocument, materialDocumentYear, materialDocumentItem, material, materialDescription, postingDate, goodsMovementType, entryUnit, purchaseOrderUnit, companyCode, documentCurrency, taxCode, netPriceAmount, netPriceQuantity, receivedQuantity, value, unitConsistent); }
        String purchaseOrderLineKey() { return purchaseOrder + ":" + canonicalItemNumber(purchaseOrderItem); }
        boolean canSettle() { return receivedQuantity.signum() > 0 && unitConsistent && !RETURN_MOVEMENT_TYPES.contains(goodsMovementType) && !RETURN_REVERSAL_MOVEMENT_TYPES.contains(goodsMovementType); }
        BigDecimal remainingQuantity() { return canSettle() ? receivedQuantity.subtract(settledQuantity).max(BigDecimal.ZERO) : BigDecimal.ZERO; }
        String settlementStatus() { if (RETURN_MOVEMENT_TYPES.contains(goodsMovementType)) return "退货"; if (RETURN_REVERSAL_MOVEMENT_TYPES.contains(goodsMovementType)) return "退货冲销"; if (!unitConsistent) return "单位不一致"; return remainingQuantity().signum() > 0 ? "可结算" : "已结算"; }
        Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("receiptKey", receiptKey); result.put("purchaseOrder", purchaseOrder); result.put("purchaseOrderItem", purchaseOrderItem); result.put("materialDocument", materialDocument); result.put("materialDocumentYear", materialDocumentYear); result.put("materialDocumentItem", materialDocumentItem); result.put("material", material); result.put("materialDescription", materialDescription); result.put("postingDate", postingDate); result.put("goodsMovementType", goodsMovementType); result.put("unit", entryUnit); result.put("purchaseOrderUnit", purchaseOrderUnit); result.put("companyCode", companyCode); result.put("documentCurrency", documentCurrency); result.put("taxCode", taxCode); result.put("netPriceAmount", decimal(netPriceAmount)); result.put("netPriceQuantity", decimal(netPriceQuantity)); result.put("receivedQuantity", decimal(receivedQuantity)); result.put("settledQuantity", decimal(settledQuantity)); result.put("remainingQuantity", decimal(remainingQuantity())); result.put("settlementStatus", settlementStatus()); return result;
        }
        private String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    }
    private record InvoiceSelection(String receiptKey, BigDecimal quantity, BigDecimal amount) { }
    private record Resource(String serviceName, String searchField, String orderBy) { }
    private record LoadResult(List<JsonNode> records, String issue) { }
}
