package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.sap.SapODataClient;
import com.yxh.sapvendorportal.security.VendorScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsPortalAsnNumberAndSapInboundDeliveryAfterCreation() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            return "PurchaseOrder".equals(service.getEntity()) ? List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\"}")) : List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            return "PurchaseOrderItem".equals(service.getEntity()) ? List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\"}")) : List.of();
        });
        when(sapClient.createAsn(eq("133000006"), any())).thenReturn(objectMapper.readTree("{\"d\":{\"DeliveryDocument\":\"1800000999\"}}"));
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> response = controller.createAsn(objectMapper.readTree("""
                {"portalAsnNumber":"PASN-20260806000000-ABC12345","items":[
                  {"sourcePurchaseOrder":"4500001001","sourcePurchaseOrderItem":"00010","quantity":2}
                ]}
                """), mock(HttpServletRequest.class));

        assertThat(response.get("portalAsnNumber")).isEqualTo("PASN-20260806000000-ABC12345");
        assertThat(response.get("sapInboundDelivery")).isEqualTo("1800000999");
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(sapClient).createAsn(eq("133000006"), request.capture());
        assertThat(request.getValue().path("portalAsnNumber").asText()).isEqualTo("PASN-20260806000000-ABC12345");
    }

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

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.path("MaterialDescription").asText()).isEqualTo("精密轴承");
            assertThat(row.path("ReceiptStatus").asText()).isEqualTo("已收货");
        });
    }

    @Test
    void aggregatesGoodsReceiptQuantityIntoPurchaseOrderProgress() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\"}"));
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"PurchaseOrderItemText\":\"精密轴承\",\"Plant\":\"1710\",\"OrderQuantity\":20,\"PurchaseOrderQuantityUnit\":\"EA\",\"PurchaseOrderStatus\":\"02\"}"));
            return List.of(
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000001\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":10}"),
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000002\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"123\",\"QuantityInEntryUnit\":2}"),
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000003\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"102\",\"QuantityInEntryUnit\":3}"),
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000004\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"122\",\"QuantityInEntryUnit\":1}"));
        });
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> response = controller.data("purchaseOrders", "", 30, mock(HttpServletRequest.class));
        @SuppressWarnings("unchecked")
        List<JsonNode> records = (List<JsonNode>) response.get("records");

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.path("Plant").asText()).isEqualTo("1710");
            assertThat(row.path("ReceivedQuantity").asText()).isEqualTo("8");
            assertThat(row.path("OpenReceiptQuantity").asText()).isEqualTo("12");
            assertThat(row.path("PurchaseOrderStatus").asText()).isEqualTo("部分收货");
        });
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
                {"invoiceReference":"SUP-INV-001","documentDate":"2026-08-03","postingDate":"2026-08-03","netAmount":70,"grossAmount":77,"taxAmount":7,"items":[
                  {"receiptKey":"5000000001:2026:1","quantity":7}
                ]}
                """);

        assertThatThrownBy(() -> controller.createInvoice(input, mock(HttpServletRequest.class)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason()).contains("可结算数量"));
    }

    @Test
    void rejectsInvoiceGrossAmountWhenItDoesNotEqualSapLineAmountPlusTax() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = reconciliationClient();
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));
        JsonNode input = objectMapper.readTree("""
                {"invoiceReference":"SUP-INV-002","documentDate":"2026-08-03","postingDate":"2026-08-03",
                 "netAmount":60,"grossAmount":80,"taxAmount":10,"headerText":"八月收货结算","items":[
                  {"receiptKey":"5000000001:2026:1","quantity":6}
                ]}
                """);

        assertThatThrownBy(() -> controller.createInvoice(input, mock(HttpServletRequest.class)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason()).contains("含税金额"));
    }

    @Test
    void createsInvoiceWhenSapPurchaseOrderDoesNotReturnTaxCode() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = reconciliationClient();
        when(sapClient.createSupplierInvoice(anyString(), any())).thenReturn(objectMapper.createObjectNode());
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));
        JsonNode input = objectMapper.readTree("""
                {"invoiceReference":"SUP-INV-003","documentDate":"2026-08-03","postingDate":"2026-08-03",
                 "netAmount":60,"taxAmount":0,"grossAmount":60,"items":[
                  {"receiptKey":"5000000001:2026:1","quantity":6}
                ]}
                """);

        Map<String, Object> response = controller.createInvoice(input, mock(HttpServletRequest.class));
        assertThat(response).containsKey("result");
    }

    @Test
    void hidesFullyReversedGoodsReceiptFromReconciliation() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = reconciliationClient(true);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        PortalController controller = new PortalController(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> response = controller.reconciliation(mock(HttpServletRequest.class));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) response.get("records");

        assertThat(records).isEmpty();
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
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"PurchaseOrderItemText\":\"精密轴承\",\"PurchaseOrderQuantityUnit\":\"EA\",\"NetPriceAmount\":10,\"NetPriceQuantity\":1}"));
            JsonNode receipt = objectMapper.readTree("{\"MaterialDocument\":\"5000000001\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":10,\"EntryUnit\":\"EA\"}");
            if (!includesReturnAndReversal) return List.of(receipt);
            return List.of(receipt,
                    objectMapper.readTree("{\"MaterialDocument\":\"5000000004\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"000010\",\"GoodsMovementType\":\"102\",\"QuantityInEntryUnit\":8,\"EntryUnit\":\"EA\"}"),
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
