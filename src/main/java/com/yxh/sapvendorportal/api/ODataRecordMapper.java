package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

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
            rows.add(normalize("purchaseOrders", row));
        }
        return rows;
    }

    private ObjectNode normalize(String resource, ObjectNode row) {
        switch (resource) {
            case "purchaseOrders" -> {
                alias(row, "MaterialDescription", "PurchaseOrderItemText", "MaterialName", "MaterialDescription");
                alias(row, "OrderQuantity", "OrderQuantity", "PurchaseOrderQuantity", "RequestedQuantity");
                alias(row, "PurchaseOrderQuantityUnit", "PurchaseOrderQuantityUnit", "OrderQuantityUnit", "BaseUnit", "UnitOfMeasure");
                alias(row, "DeliveryDate", "ScheduleLineDeliveryDate", "DeliveryDate", "RequestedDeliveryDate", "ConfirmedDeliveryDate", "StatDeliveryDate");
                alias(row, "PurchaseOrderStatus", "PurchaseOrderItemStatus", "PurchaseOrderStatus", "PurchasingDocumentStatus", "OverallStatus", "LifecycleStatus", "Status");
            }
            case "asns" -> {
                alias(row, "InbDelivery", "InbDelivery", "InboundDelivery", "DeliveryDocument");
                alias(row, "DeliveryDate", "PlannedDeliveryDate", "DeliveryDate", "ActualDeliveryDate");
                alias(row, "OverallStatus", "OverallStatus", "InbDeliveryStatus", "Status");
                alias(row, "MaterialDescription", "MaterialDescription", "ItemText", "MaterialName");
            }
            case "materialDocuments" -> {
                alias(row, "MaterialDocument", "MaterialDocument", "MaterialDocumentYear");
                alias(row, "MaterialDescription", "MaterialDescription", "MaterialDocumentItemText", "ItemText");
                alias(row, "QuantityInEntryUnit", "QuantityInEntryUnit", "Quantity", "EntryQuantity");
                alias(row, "EntryUnit", "EntryUnit", "QuantityUnit", "BaseUnit");
            }
            case "invoices" -> {
                alias(row, "SupplierInvoice", "SupplierInvoice", "SupplierInvoiceID", "InvoiceNumber");
                alias(row, "SupplierInvoiceStatus", "SupplierInvoiceStatus", "OverallStatus", "Status");
                alias(row, "InvoiceGrossAmount", "InvoiceGrossAmount", "GrossAmount", "InvoiceAmount");
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
}
