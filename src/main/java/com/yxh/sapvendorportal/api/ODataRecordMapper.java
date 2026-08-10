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
            copyIfMissing(row, header, "PurchaseOrder", "Supplier", "CompanyCode", "PurchasingOrganization", "PurchaseOrderDate", "DocumentCurrency");
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
            copyIfMissing(row, header, "SupplierInvoice", "FiscalYear", "DocumentDate", "PostingDate", "InvoiceGrossAmount", "DocumentCurrency", "SupplierInvoiceStatus", "InvoicingParty", "CompanyCode", "SupplierInvoiceIsCreditMemo");
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
            copyIfMissing(row, header, "DeliveryDocument", "Supplier", "DeliveryDate", "PlannedDeliveryDate", "ActualDeliveryDate", "OverallGoodsMovementStatus", "OverallSDProcessStatus", "LastChangeDate", "DeliveryDocumentBySupplier", "BillOfLading", "TransportReference", "ReceivingPlant", "ShippingPoint");
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
                deriveOrderType(row);
                normalizeSubcontractingComponents(row);
            }
            case "asns" -> {
                alias(row, "InbDelivery", "InbDelivery", "InboundDelivery", "DeliveryDocument");
                alias(row, "PurchaseOrder", "PurchaseOrder", "ReferenceSDDocument");
                alias(row, "PurchaseOrderItem", "PurchaseOrderItem", "ReferenceSDDocumentItem");
                alias(row, "DeliveryDate", "PlannedDeliveryDate", "DeliveryDate", "ActualDeliveryDate");
                alias(row, "OverallStatus", "OverallGoodsMovementStatus", "OverallSDProcessStatus", "OverallStatus", "InbDeliveryStatus", "Status");
                alias(row, "MaterialDescription", "MaterialDescription", "DeliveryDocumentItemText", "ItemText", "MaterialName", "ProductDescription");
                alias(row, "ActualDeliveryQuantity", "ActualDeliveryQuantity", "DeliveryQuantity", "ActualQuantity");
                alias(row, "DeliveryQuantityUnit", "DeliveryQuantityUnit", "ActualDeliveryQuantityUnit", "BaseUnit");
                alias(row, "TransportReference", "TransportReference", "BillOfLading");
            }
            case "materialDocuments" -> {
                JsonNode header = row.path("to_MaterialDocumentHeader");
                if (header.isObject()) copyIfMissing(row, header, "PostingDate", "DocumentDate", "MaterialDocumentHeaderText");
                alias(row, "MaterialDocument", "MaterialDocument");
                alias(row, "MaterialDocumentYear", "MaterialDocumentYear", "Year");
                alias(row, "PurchaseOrder", "PurchaseOrder", "PurchaseOrderNumber");
                alias(row, "InbDelivery", "InbDelivery", "DeliveryDocument", "InboundDelivery", "ReferenceDocument");
                alias(row, "MaterialDescription", "MaterialDescription", "MaterialDocumentItemText", "ItemText");
                alias(row, "QuantityInEntryUnit", "QuantityInEntryUnit", "Quantity", "EntryQuantity");
                alias(row, "EntryUnit", "EntryUnit", "QuantityUnit", "BaseUnit");
                deriveReceiptStatus(row);
            }
            case "invoices" -> {
                alias(row, "SupplierInvoice", "SupplierInvoice", "SupplierInvoiceID", "InvoiceNumber");
                alias(row, "SupplierInvoiceStatus", "SupplierInvoiceStatus", "OverallStatus", "Status");
                alias(row, "InvoiceGrossAmount", "InvoiceGrossAmount", "GrossAmount", "InvoiceAmount");
                alias(row, "MaterialDescription", "MaterialDescription", "SupplierInvoiceItemText", "ItemText", "MaterialName");
                alias(row, "QuantityInPurchaseOrderUnit", "QuantityInPurchaseOrderUnit", "SupplierInvoiceItemQuantity", "Quantity", "QuantityInEntryUnit");
                alias(row, "ReferenceDocument", "ReferenceDocument", "MaterialDocument", "GoodsReceiptDocument");
                alias(row, "ReferenceDocumentYear", "ReferenceDocumentYear", "ReferenceDocumentFiscalYear", "MaterialDocumentYear");
                alias(row, "ReferenceDocumentItem", "ReferenceDocumentItem", "MaterialDocumentItem", "GoodsReceiptDocumentItem");
            }
            case "suppliers" -> {
                alias(row, "SupplierName", "BusinessPartnerFullName", "OrganizationBPName1", "BusinessPartnerName", "SupplierName");
                alias(row, "Supplier", "BusinessPartner", "Supplier");
                JsonNode address = firstObject(row, "to_BusinessPartnerAddress");
                if (address != null) {
                    copyIfMissing(row, address, "Country", "Region", "CityName", "PostalCode", "StreetName", "HouseNumber", "Building", "Floor", "RoomNumber", "CareOfName");
                    JsonNode email = firstObject(address, "to_EmailAddress");
                    if (email != null) copyIfMissing(row, email, "EmailAddress");
                    JsonNode phone = firstObject(address, "to_PhoneNumber");
                    if (phone != null) copyIfMissing(row, phone, "PhoneNumber", "PhoneNumberExtension");
                    JsonNode fax = firstObject(address, "to_FaxNumber");
                    if (fax != null) copyIfMissing(row, fax, "FaxNumber");
                }
                // 基础主数据中的邮箱、电话属于供应商本体，避免与联系人资料混淆。
                alias(row, "SupplierEmail", "EmailAddress");
                alias(row, "SupplierPhone", "PhoneNumber");
                alias(row, "SupplierPhoneExtension", "PhoneNumberExtension");
                JsonNode bank = firstObject(row, "to_BusinessPartnerBank");
                if (bank != null) copyIfMissing(row, bank, "BankAccountName", "BankCountryKey", "BankKey", "BankAccount", "IBAN", "BankControlKey", "BankIdentification");
                alias(row, "ContactName", "ContactPerson", "ContactPersonFullName", "PersonFullName", "CareOfName");
                alias(row, "ContactDepartment", "Department", "ContactDepartment");
            }
            case "supplierCompanies" -> {
                alias(row, "Supplier", "Supplier", "BusinessPartner");
                alias(row, "Currency", "Currency", "PaymentCurrency", "PaymentCurrencyCode", "CompanyCodeCurrency", "DocumentCurrency");
                alias(row, "PaymentMethod", "PaymentMethod", "PaymentMethodsList");
                alias(row, "PaymentTerms", "PaymentTerms", "PaymentTermsCode");
                alias(row, "CompanyCodeName", "CompanyCodeName", "CompanyName");
                alias(row, "SupplierPaymentIsBlocked", "SupplierPaymentIsBlocked", "PaymentIsBlockedForSupplier", "PaymentIsBlocked");
            }
            case "supplierBanks" -> {
                alias(row, "BusinessPartner", "BusinessPartner", "Supplier");
                alias(row, "BankNumber", "BankNumber", "BankKey");
                alias(row, "BankName", "BankName");
                alias(row, "SWIFTCode", "SWIFTCode", "SwiftCode");
                alias(row, "IBAN", "IBAN");
            }
            case "businessPartnerContacts" -> {
                alias(row, "BusinessPartnerCompany", "BusinessPartnerCompany", "BusinessPartner");
                alias(row, "ContactPerson", "BusinessPartnerPerson", "ContactPerson");
            }
            case "businessPartnerPersons" -> {
                alias(row, "ContactName", "PersonFullName", "BusinessPartnerFullName", "FullName", "FirstName");
                alias(row, "ContactPerson", "BusinessPartner");
                JsonNode address = firstObject(row, "to_BusinessPartnerAddress");
                if (address != null) {
                    JsonNode email = firstObject(address, "to_EmailAddress");
                    if (email != null) copyIfMissing(row, email, "EmailAddress");
                    JsonNode phone = firstObject(address, "to_PhoneNumber");
                    if (phone != null) copyIfMissing(row, phone, "PhoneNumber", "PhoneNumberExtension");
                }
            }
            case "subcontractingComponents" -> normalizeSubcontractingComponent(row);
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
    private JsonNode firstObject(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (candidate.isArray() && !candidate.isEmpty() && candidate.get(0).isObject()) return candidate.get(0);
            if (candidate.path("value").isArray() && !candidate.path("value").isEmpty() && candidate.path("value").get(0).isObject()) return candidate.path("value").get(0);
            if (candidate.path("results").isArray() && !candidate.path("results").isEmpty() && candidate.path("results").get(0).isObject()) return candidate.path("results").get(0);
            if (candidate.isObject()) return candidate;
        }
        return null;
    }
    private void copyIfMissing(ObjectNode target, JsonNode source, String... names) { for (String name : names) if (!target.has(name) && source.has(name)) target.set(name, source.get(name)); }
    private void copyFirst(ObjectNode target, JsonNode source, String targetName, String... candidates) { for (String candidate : candidates) if (source.hasNonNull(candidate) && !source.path(candidate).asText().isBlank()) { target.set(targetName, source.get(candidate)); return; } }
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
    private void deriveReceiptStatus(ObjectNode row) {
        String movementType = row.path("GoodsMovementType").asText();
        String status = switch (movementType) {
            case "101" -> "已收货";
            case "102" -> "收货冲销";
            case "122" -> "部分冲销";
            case "123" -> "部分冲销冲销";
            case "161" -> "退货";
            case "162" -> "退货冲销";
            default -> "其他移动";
        };
        row.put("ReceiptStatus", status);
    }
    private void deriveOrderType(ObjectNode row) {
        List<String> types = new ArrayList<>();
        if (booleanValue(row, "PurchasingItemIsFreeOfCharge")) types.add("免费订单");
        if ("3".equals(row.path("PurchaseOrderItemCategory").asText().trim())) types.add("外协订单");
        if (booleanValue(row, "IsReturnsItem", "ReturnsItem", "ReturnsIndicator")) types.add("退货订单");
        if (booleanValue(row, "IsCompletelyDelivered")) types.add("已完成订单");
        row.put("OrderType", types.isEmpty() ? "标准订单" : String.join(" · ", types));
    }
    private void normalizeSubcontractingComponents(ObjectNode row) {
        JsonNode components = firstArray(row, "POSubcontractingComponent", "_PurOrdItemComponent", "to_PurOrdItemComponent", "_PurchaseOrderItemComponent", "to_PurchaseOrderItemComponent");
        if (components == null || components.isEmpty()) return;
        var normalized = objectMapper.createArrayNode();
        for (JsonNode component : components) {
            if (!component.isObject()) continue;
            ObjectNode item = objectMapper.createObjectNode();
            copyFirst(item, component, "material", "Material", "ComponentMaterial", "ReservationItem");
            copyFirst(item, component, "description", "MaterialDescription", "ComponentDescription", "PurchaseOrderItemText", "ItemText");
            copyFirst(item, component, "quantity", "RequiredQuantity", "ComponentQuantity", "Quantity", "EntryQuantity");
            copyFirst(item, component, "unit", "PurchaseOrderQuantityUnit", "ComponentUnit", "QuantityUnit", "EntryUnit", "UnitOfMeasure");
            copyFirst(item, component, "plant", "Plant", "ProductionPlant");
            normalized.add(item);
        }
        if (!normalized.isEmpty()) row.set("SubcontractingComponents", normalized);
    }
    private void normalizeSubcontractingComponent(ObjectNode row) {
        copyFirst(row, row, "material", "Material", "ComponentMaterial", "ReservationItem");
        copyFirst(row, row, "description", "MaterialDescription", "ComponentDescription", "PurchaseOrderItemText", "ItemText");
        copyFirst(row, row, "quantity", "RequiredQuantity", "ComponentQuantity", "Quantity", "EntryQuantity");
        copyFirst(row, row, "unit", "PurchaseOrderQuantityUnit", "ComponentUnit", "QuantityUnit", "EntryUnit", "UnitOfMeasure");
        copyFirst(row, row, "plant", "Plant", "ProductionPlant");
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
    private boolean booleanValue(ObjectNode row, String... names) { for (String name : names) { JsonNode value = row.path(name); if (value.asBoolean(false) || "X".equalsIgnoreCase(value.asText()) || "true".equalsIgnoreCase(value.asText())) return true; } return false; }
}
