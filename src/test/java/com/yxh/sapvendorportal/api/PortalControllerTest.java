package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.sap.SapODataClient;
import com.yxh.sapvendorportal.security.VendorScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fillsReceiptMaterialDescriptionFromMatchingPurchaseOrderLineWithDifferentItemPadding() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), eq(""), eq("PurchaseOrder"), anyString(), anyInt()))
                .thenReturn(List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\"}")));
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) {
                return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"PurchaseOrderItemText\":\"精密轴承\"}"));
            }
            return List.of(objectMapper.readTree("{\"MaterialDocument\":\"5000000001\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"101\"}"));
        });
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> response = controller.data("materialDocuments", "", 30, mock(HttpServletRequest.class));
        @SuppressWarnings("unchecked")
        List<JsonNode> records = (List<JsonNode>) response.get("records");

        assertThat(records).singleElement().extracting(row -> row.path("MaterialDescription").asText()).isEqualTo("精密轴承");
    }

    @Test
    void calculatesRemainingSettlementQuantityFromReceiptAndInvoiceLines() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) {
                return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"CompanyCode\":\"1000\",\"DocumentCurrency\":\"CNY\"}"));
            }
            if ("A_SupplierInvoice".equals(service.getEntity())) {
                return List.of(objectMapper.readTree("""
                        {"SupplierInvoice":"5100000001","InvoicingParty":"133000006","to_SuplrInvcItemPurOrdRef":{"results":[
                          {"PurchaseOrder":"4500001001","PurchaseOrderItem":"00010","QuantityInPurchaseOrderUnit":4}
                        ]}}
                        """));
            }
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) {
                return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"PurchaseOrderItemText\":\"精密轴承\",\"PurchaseOrderQuantityUnit\":\"EA\"}"));
            }
            return List.of(objectMapper.readTree("{\"MaterialDocument\":\"5000000001\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":10,\"EntryUnit\":\"EA\"}"));
        });
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> response = controller.reconciliation(mock(HttpServletRequest.class));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) response.get("records");

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.get("materialDescription")).isEqualTo("精密轴承");
            assertThat(row.get("receivedQuantity")).isEqualTo("10");
            assertThat(row.get("settledQuantity")).isEqualTo("4");
            assertThat(row.get("remainingQuantity")).isEqualTo("6");
            assertThat(row.get("settlementStatus")).isEqualTo("可结算");
        });
    }

    @Test
    void rejectsInvoiceQuantityAboveRealTimeRemainingReceiptQuantity() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = reconciliationClient();
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));
        JsonNode input = objectMapper.readTree("""
                {"invoiceReference":"SUP-INV-001","documentDate":"2026-08-03","postingDate":"2026-08-03","items":[
                  {"receiptKey":"5000000001:2026:1","quantity":7,"amount":70}
                ]}
                """);

        assertThatThrownBy(() -> controller.createInvoice(input, mock(HttpServletRequest.class)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason()).contains("可结算数量"));
    }

    @Test
    void appliesGoodsReceiptAndReturnReversalFormulasSeparately() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = reconciliationClient(true);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> response = controller.reconciliation(mock(HttpServletRequest.class));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) response.get("records");

        assertThat(records).filteredOn(row -> "5000000001".equals(row.get("materialDocument"))).singleElement().satisfies(row -> {
            assertThat(row.get("materialDocumentYear")).isEqualTo("2026");
            assertThat(row.get("receivedQuantity")).isEqualTo("6");
            assertThat(row.get("remainingQuantity")).isEqualTo("2");
            assertThat(row.get("settlementInvoices")).isEqualTo("5100000001/2026");
        });
        assertThat(records).filteredOn(row -> "161".equals(row.get("goodsMovementType"))).singleElement().satisfies(row -> {
            assertThat(row.get("settlementStatus")).isEqualTo("退货");
            assertThat(row.get("actualReturnQuantity")).isEqualTo("2");
        });
        assertThat(records).filteredOn(row -> "162".equals(row.get("goodsMovementType"))).singleElement().satisfies(row -> {
            assertThat(row.get("settlementStatus")).isEqualTo("退货冲销");
            assertThat(row.get("actualReturnQuantity")).isEqualTo("0");
        });
    }

    private SapODataClient reconciliationClient() throws Exception {
        return reconciliationClient(false);
    }

    private SapODataClient reconciliationClient(boolean includesReturnAndReversal) throws Exception {
        SapODataClient sapClient = mock(SapODataClient.class);
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"CompanyCode\":\"1000\",\"DocumentCurrency\":\"CNY\"}"));
            if ("A_SupplierInvoice".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"SupplierInvoice\":\"5100000001\",\"FiscalYear\":\"2026\",\"InvoicingParty\":\"133000006\",\"to_SuplrInvcItemPurOrdRef\":{\"results\":[{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"QuantityInPurchaseOrderUnit\":4}]}}"));
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"PurchaseOrderItemText\":\"精密轴承\",\"PurchaseOrderQuantityUnit\":\"EA\"}"));
            JsonNode receipt = objectMapper.readTree("{\"MaterialDocument\":\"5000000001\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":10,\"EntryUnit\":\"EA\"}");
            if (!includesReturnAndReversal) return List.of(receipt);
            return List.of(receipt,
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000004\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"102\",\"QuantityInEntryUnit\":2,\"EntryUnit\":\"EA\"}"),
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000005\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"122\",\"QuantityInEntryUnit\":3,\"EntryUnit\":\"EA\"}"),
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000006\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"123\",\"QuantityInEntryUnit\":1,\"EntryUnit\":\"EA\"}"),
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000002\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"161\",\"QuantityInEntryUnit\":3,\"EntryUnit\":\"EA\"}"),
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000003\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"162\",\"QuantityInEntryUnit\":1,\"EntryUnit\":\"EA\"}"));
        });
        return sapClient;
    }

    private PortalProperties configuredProperties() {
        PortalProperties properties = new PortalProperties();
        properties.setVendorId("133000006");
        properties.getSap().setUsername("test");
        properties.getSap().setPassword("test");
        configureDirect(properties.getSap().getBusinessPartner(), "A_BusinessPartner");
        configureDirect(properties.getSap().getPurchaseOrder(), "PurchaseOrder");
        configureDirect(properties.getSap().getAsn(), "A_InbDeliveryHeader");
        configurePurchaseOrderScoped(properties.getSap().getMaterialDocument(), "A_MaterialDocumentItem");
        configureDirect(properties.getSap().getSupplierInvoice(), "A_SupplierInvoice");
        return properties;
    }

    private void configureDirect(PortalProperties.Service service, String entity) {
        service.setUrl("https://sap.example.test/odata");
        service.setEntity(entity);
        service.setSupplierField("Supplier");
    }

    private void configurePurchaseOrderScoped(PortalProperties.Service service, String entity) {
        service.setUrl("https://sap.example.test/odata");
        service.setEntity(entity);
        service.setScopeMode("purchase_order");
        service.setReferenceField("PurchaseOrder");
    }
}
