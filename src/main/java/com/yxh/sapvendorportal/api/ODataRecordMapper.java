package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class ODataRecordMapper {
    private final ObjectMapper objectMapper;
    public ODataRecordMapper(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public List<JsonNode> map(String resource, List<JsonNode> source) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode record : source) {
            if ("purchaseOrders".equals(resource)) result.addAll(flattenPurchaseOrder(record));
            else if ("asns".equals(resource)) result.addAll(flattenAsn(record));
            else if ("invoices".equals(resource)) result.addAll(flattenInvoice(record));
            else result.add(normalize(resource, copy(record)));
        }
        return result;
    }

    private List<JsonNode> flattenPurchaseOrder(JsonNode header) {
        JsonNode items = firstArray(header, "_PurchaseOrderItem", "to_PurchaseOrderItem", "PurchaseOrderItem", "items", "Items");
        if (items == null) return List.of(normalize("purchaseOrders", copy(header)));
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode item : items) {
            ObjectNode row = copy(item);
            copyIfMissing(row, header, "PurchaseOrder", "Supplier", "CompanyCode", "PurchasingOrganization", "PurchaseOrderDate");
            JsonNode scheduleLines = firstArray(item, "_PurchaseOrderScheduleLineTP", "_PurchaseOrderScheduleLine", "to_PurchaseOrderScheduleLine");
            if (scheduleLines != null && !scheduleLines.isEmpty()) copyIfMissing(row, scheduleLines.get(0), "ScheduleLineDeliveryDate", "DeliveryDate", "StatDeliveryDate");
            rows.add(normalize("purchaseOrders", row));
        }
        return rows;
    }

    private List<JsonNode> flattenInvoice(JsonNode header) {
        JsonNode items = firstArray(header, "to_SuplrInvcItemPurOrdRef", "to_SupplierInvoiceItemPurOrdRef");
        if (items == null) return List.of(normalize("invoices", copy(header)));
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode item : items) {
            ObjectNode row = copy(item);
            copyIfMissing(row, header, "SupplierInvoice", "FiscalYear", "DocumentDate", "PostingDate", "InvoiceGrossAmount", "DocumentCurrency", "SupplierInvoiceStatus", "InvoicingParty", "CompanyCode");
            rows.add(normalize("invoices", row));
        }
        return rows;
    }

    private List<JsonNode> flattenAsn(JsonNode header) {
        JsonNode items = firstArray(header, "to_DeliveryDocumentItem", "to_InbDeliveryItem");
        if (items == null) return List.of(normalize("asns", copy(header)));
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode item : items) {
            ObjectNode row = copy(item);
            copyIfMissing(row, header, "DeliveryDocument", "Supplier", "DeliveryDate", "OverallGoodsMovementStatus", "OverallSDProcessStatus", "LastChangeDate", "DeliveryDocumentBySupplier");
            rows.add(normalize("asns", row));
        }
        return rows;
    }

    private ObjectNode normalize(String resource, ObjectNode row) {
        switch (resource) {
            case "purchaseOrders" -> {
                JsonNode scheduleLines = firstArray(row, "_PurchaseOrderScheduleLineTP", "_PurchaseOrderScheduleLine", "to_PurchaseOrderScheduleLine");
                if (scheduleLines != null && !scheduleLines.isEmpty()) copyIfMissing(row, scheduleLines.get(0), "ScheduleLineDeliveryDate", "DeliveryDate", "StatDeliveryDate");
                alias(row, "MaterialDescription", "PurchaseOrderItemText", "MaterialName", "MaterialDescription");
                alias(row, "OrderQuantity", "OrderQuantity", "PurchaseOrderQuantity", "RequestedQuantity");
                alias(row, "PurchaseOrderQuantityUnit", "PurchaseOrderQuantityUnit", "OrderQuantityUnit", "BaseUnit", "UnitOfMeasure");
                alias(row, "DeliveryDate", "ScheduleLineDeliveryDate", "DeliveryDate", "RequestedDeliveryDate", "ConfirmedDeliveryDate", "StatDeliveryDate");
                derivePurchaseOrderStatus(row);
                alias(row, "PurchaseOrderStatus", "PurchaseOrderItemStatus", "PurchaseOrderStatus", "PurchasingDocumentStatus", "OverallStatus", "LifecycleStatus", "Status");
            }
            case "asns" -> {
                alias(row, "InbDelivery", "InbDelivery", "InboundDelivery", "DeliveryDocument");
                alias(row, "PurchaseOrder", "PurchaseOrder", "ReferenceSDDocument");
                alias(row, "PurchaseOrderItem", "PurchaseOrderItem", "ReferenceSDDocumentItem");
                alias(row, "DeliveryDate", "PlannedDeliveryDate", "DeliveryDate", "ActualDeliveryDate");
                alias(row, "OverallStatus", "OverallGoodsMovementStatus", "OverallSDProcessStatus", "OverallStatus", "InbDeliveryStatus", "Status");
                alias(row, "MaterialDescription", "MaterialDescription", "DeliveryDocumentItemText", "ItemText", "MaterialName", "ProductDescription");
            }
            case "materialDocuments" -> {
                JsonNode header = row.path("to_MaterialDocumentHeader");
                if (header.isObject()) copyIfMissing(row, header, "PostingDate", "DocumentDate", "MaterialDocumentHeaderText");
                alias(row, "MaterialDocument", "MaterialDocument", "MaterialDocumentYear");
                alias(row, "PurchaseOrder", "PurchaseOrder", "PurchaseOrderNumber");
                alias(row, "MaterialDescription", "MaterialDescription", "MaterialDocumentItemText", "ItemText");
                alias(row, "QuantityInEntryUnit", "QuantityInEntryUnit", "Quantity", "EntryQuantity");
                alias(row, "EntryUnit", "EntryUnit", "QuantityUnit", "BaseUnit");
            }
            case "invoices" -> {
                alias(row, "SupplierInvoice", "SupplierInvoice", "SupplierInvoiceID", "InvoiceNumber");
                alias(row, "SupplierInvoiceStatus", "SupplierInvoiceStatus", "OverallStatus", "Status");
                alias(row, "InvoiceGrossAmount", "InvoiceGrossAmount", "GrossAmount", "InvoiceAmount");
                alias(row, "MaterialDescription", "MaterialDescription", "SupplierInvoiceItemText", "ItemText", "MaterialName");
            }
            case "suppliers" -> {
                alias(row, "SupplierName", "BusinessPartnerFullName", "OrganizationBPName1", "BusinessPartnerName", "SupplierName");
                alias(row, "Supplier", "BusinessPartner", "Supplier");
            }
            default -> { }
        }
        return row;
    }

    private ObjectNode copy(JsonNode node) { return node != null && node.isObject() ? ((ObjectNode) node).deepCopy() : objectMapper.createObjectNode(); }
    private JsonNode firstArray(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (candidate.isArray()) return candidate;
            if (candidate.path("value").isArray()) return candidate.path("value");
            if (candidate.path("results").isArray()) return candidate.path("results");
        }
        return null;
    }
    private void copyIfMissing(ObjectNode target, JsonNode source, String... names) { for (String name : names) if (!target.has(name) && source.has(name)) target.set(name, source.get(name)); }
    private void alias(ObjectNode row, String target, String... candidates) { if (row.hasNonNull(target) && !row.path(target).asText().isBlank()) return; for (String candidate : candidates) if (row.hasNonNull(candidate) && !row.path(candidate).asText().isBlank()) { row.set(target, row.get(candidate)); return; } }
    private void derivePurchaseOrderStatus(ObjectNode row) {
        if (!row.path("PurchasingDocumentDeletionCode").asText().isBlank()) {
            row.put("PurchaseOrderStatus", "已取消");
            return;
        }
        if (row.path("IsCompletelyDelivered").asBoolean(false)) {
            row.put("PurchaseOrderStatus", "已完成");
            return;
        }
        BigDecimal outstanding = firstDecimal(row, "StillToBeDeliveredQuantity", "OpenPurchaseOrderQuantity", "OpenQuantity");
        if (outstanding != null) {
            if (outstanding.signum() <= 0) {
                row.put("PurchaseOrderStatus", "已完成");
                return;
            }
            BigDecimal ordered = firstDecimal(row, "OrderQuantity", "PurchaseOrderQuantity", "RequestedQuantity");
            row.put("PurchaseOrderStatus", ordered != null && outstanding.compareTo(ordered) < 0 ? "部分收货" : "待交货");
            return;
        }
        if (row.hasNonNull("IsCompletelyDelivered")) row.put("PurchaseOrderStatus", "待交货");
    }
    private BigDecimal firstDecimal(ObjectNode row, String... names) {
        for (String name : names) {
            JsonNode value = row.path(name);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                try { return new BigDecimal(value.asText()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }
}
