package com.yxh.sapvendorportal.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.common.security.VendorScopeResolver;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.integration.sap.SapODataClient;
import com.yxh.sapvendorportal.mapper.ODataRecordMapper;
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

class PortalServiceImplTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dashboardUsesTheSameSettlementLogicAsReconciliationForItsFourthMetric() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500002001\"}"));
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500002001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"OrderQuantity\":4,\"PurchaseOrderQuantityUnit\":\"EA\",\"DeliveryDate\":\"2026-08-20\",\"DocumentCurrency\":\"CNY\"}"));
            if ("A_MaterialDocumentItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"MaterialDocument\":\"5000002001\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500002001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"PostingDate\":\"\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":4,\"EntryUnit\":\"EA\",\"to_MaterialDocumentHeader\":{\"PostingDate\":\"/Date(1787011200000+0800)/\"}}"));
            return List.of();
        });
        PortalServiceImpl service = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> dashboard = service.dashboard(mock(HttpServletRequest.class));
        Map<String, Object> reconciliation = service.reconciliation(mock(HttpServletRequest.class));

        @SuppressWarnings("unchecked") List<Map<String, Object>> metrics = (List<Map<String, Object>>) dashboard.get("metrics");
        Map<String, Object> settlementMetric = metrics.stream().filter(metric -> "IV".equals(metric.get("code"))).findFirst().orElseThrow();
        assertThat(settlementMetric).containsEntry("label", "可结算收货").containsEntry("value", reconciliation.get("count"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> purchaseMetrics = (List<Map<String, Object>>) dashboard.get("purchaseManagementMetrics");
        assertThat(purchaseMetrics).extracting(metric -> metric.get("label"))
                .containsExactly("供应商交付准时率", "采购订单履约率");
        assertThat(purchaseMetrics).allSatisfy(metric -> assertThat(metric.get("formula").toString()).contains("÷"));
        assertThat(purchaseMetrics.getFirst()).containsEntry("value", "100%").containsEntry("numerator", 1L).containsEntry("denominator", 1L);
    }

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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

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
    void treatsCompletelyDeliveredOrderAsHavingNoOpenDeliveryOrAvailableShippingQuantity() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            return "PurchaseOrder".equals(service.getEntity()) ? List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001010\"}")) : List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001010\",\"PurchaseOrderItem\":\"00010\",\"OrderQuantity\":20,\"StillToBeDeliveredQuantity\":12,\"IsCompletelyDelivered\":true}"));
            return List.of(objectMapper.readTree("{\"MaterialDocument\":\"5000000010\",\"PurchaseOrder\":\"4500001010\",\"PurchaseOrderItem\":\"00010\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":8}"));
        });
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        @SuppressWarnings("unchecked") List<com.fasterxml.jackson.databind.JsonNode> records = (List<com.fasterxml.jackson.databind.JsonNode>) controller.data("purchaseOrders", "", 30, mock(HttpServletRequest.class)).get("records");

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.path("PurchaseOrderStatus").asText()).isEqualTo("已完成");
            assertThat(row.path("OpenReceiptQuantity").asText()).isEqualTo("0");
            assertThat(row.path("AsnAvailableQuantity").asText()).isEqualTo("0");
        });
    }

    @Test
    void addsSupplierAndDeliveryAddressesToPurchaseOrderLinesForPrinting() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) return List.of(objectMapper.readTree("""
                    {"PurchaseOrder":"4500001020","Supplier":"133000006","_SupplierAddress":{"FullName":"上海示例供应商有限公司","StreetName":"供应商路","HouseNumber":"10号","CityName":"上海"}}
                    """));
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("""
                    {"PurchaseOrder":"4500001020","PurchaseOrderItem":"00010","Material":"MAT-01","_DeliveryAddress":{"StreetName":"采购方路","HouseNumber":"20号","CityName":"苏州"}}
                    """));
            return List.of();
        });
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        @SuppressWarnings("unchecked") List<JsonNode> records = (List<JsonNode>) controller.data("purchaseOrders", "", 30, mock(HttpServletRequest.class)).get("records");

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.path("SupplierName").asText()).isEqualTo("上海示例供应商有限公司");
            assertThat(row.path("SupplierAddressStreetName").asText()).isEqualTo("供应商路");
            assertThat(row.path("SupplierAddressCityName").asText()).isEqualTo("上海");
            assertThat(row.path("DeliveryAddressStreetName").asText()).isEqualTo("采购方路");
            assertThat(row.path("DeliveryAddressCityName").asText()).isEqualTo("苏州");
        });
    }

    @Test
    void rejectsAsnForReturnOrderLine() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            return "PurchaseOrder".equals(service.getEntity()) ? List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001011\"}")) : List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001011\",\"PurchaseOrderItem\":\"00010\",\"OrderQuantity\":5,\"IsReturnsItem\":true}"));
            return List.of();
        });
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        assertThatThrownBy(() -> controller.createAsn(objectMapper.readTree("{\"items\":[{\"sourcePurchaseOrder\":\"4500001011\",\"sourcePurchaseOrderItem\":\"00010\",\"quantity\":1}]}"), mock(HttpServletRequest.class)))
                .isInstanceOf(ResponseStatusException.class).satisfies(error -> assertThat(((ResponseStatusException) error).getReason()).contains("退货订单行不能创建 ASN"));
    }

    @Test
    void queriesOutboundDeliveryApiForReturnPurchaseOrderInsteadOfInboundAsnApi() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001099\"}"));
            if ("A_InbDeliveryHeader".equals(service.getEntity())) throw new AssertionError("退货订单不应查询内向交货单 API");
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001099\",\"PurchaseOrderItem\":\"00010\",\"IsReturnsItem\":true}"));
            if ("A_OutbDeliveryItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"DeliveryDocument\":\"8000000999\",\"DeliveryDocumentItem\":\"000010\",\"ReferenceSDDocument\":\"4500001099\",\"ReferenceSDDocumentItem\":\"00010\",\"Material\":\"RET-01\",\"ActualDeliveryQuantity\":2,\"DeliveryQuantityUnit\":\"EA\",\"OverallGoodsMovementStatus\":\"A\"}"));
            return List.of();
        });
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        @SuppressWarnings("unchecked") List<JsonNode> records = (List<JsonNode>) controller.data("asns", "", 30, mock(HttpServletRequest.class)).get("records");

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.path("DeliveryDocument").asText()).isEqualTo("8000000999");
            assertThat(row.path("PurchaseOrder").asText()).isEqualTo("4500001099");
            assertThat(row.path("DeliveryDirection").asText()).isEqualTo("外向送货单（退货）");
        });
    }

    @Test
    void addsSettlementDetailsFromSupplierCompanyApiToSupplierProfile() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("A_BusinessPartner".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"BusinessPartner\":\"133000006\",\"OrganizationBPName1\":\"测试供应商\"}"));
            if ("A_SupplierCompany".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"Supplier\":\"133000006\",\"CompanyCode\":\"1000\",\"CompanyCodeName\":\"示例公司\",\"Currency\":\"CNY\",\"PaymentTerms\":\"0001\",\"PaymentMethodsList\":\"T\"}"));
            return List.of();
        });
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        @SuppressWarnings("unchecked") List<com.fasterxml.jackson.databind.JsonNode> records = (List<com.fasterxml.jackson.databind.JsonNode>) controller.data("suppliers", "", 30, mock(HttpServletRequest.class)).get("records");

        assertThat(records).singleElement().satisfies(row -> {
            var company = row.path("SupplierCompanies").get(0);
            assertThat(company.path("CompanyCodeName").asText()).isEqualTo("示例公司");
            assertThat(company.path("Currency").asText()).isEqualTo("CNY");
            assertThat(company.path("PaymentTerms").asText()).isEqualTo("0001");
            assertThat(company.path("PaymentMethodsList").asText()).isEqualTo("T");
        });
    }

    @Test
    void loadsBanksAndRealContactsThroughBusinessPartnerRelationship() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("A_BusinessPartner".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"BusinessPartner\":\"133000006\",\"OrganizationBPName1\":\"测试供应商\",\"to_BusinessPartnerAddress\":{\"results\":[{\"to_EmailAddress\":{\"results\":[{\"EmailAddress\":\"supplier@example.com\"}]},\"to_PhoneNumber\":{\"results\":[{\"PhoneNumber\":\"02100000000\"}]}}]}}"));
            if ("A_Supplier".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"Supplier\":\"133000006\",\"TaxNumber5\":\"TAX-5\"}"));
            if ("A_BusinessPartnerBank".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"BusinessPartner\":\"133000006\",\"BankNumber\":\"104100006062\",\"BankName\":\"测试银行\",\"SWIFTCode\":\"TESTCNBJ\",\"IBAN\":\"CN00TEST\",\"BankAccount\":\"622200001234\"}"));
            if ("A_BusinessPartnerContact".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"BusinessPartnerCompany\":\"133000006\",\"BusinessPartnerPerson\":\"200000001\"}"));
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("BusinessPartner"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if (!"A_BusinessPartner".equals(service.getEntity())) return List.of();
            return List.of(objectMapper.readTree("""
                    {"BusinessPartner":"200000001","PersonFullName":"王联系人",
                     "to_BusinessPartnerAddress":{"results":[{"to_EmailAddress":{"results":[{"EmailAddress":"contact@example.com"}]},"to_PhoneNumber":{"results":[{"PhoneNumber":"13800000000"}]}}]}}
                    """));
        });
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        @SuppressWarnings("unchecked") List<com.fasterxml.jackson.databind.JsonNode> records = (List<com.fasterxml.jackson.databind.JsonNode>) controller.data("suppliers", "", 30, mock(HttpServletRequest.class)).get("records");

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.path("TaxNumber5").asText()).isEqualTo("TAX-5");
            assertThat(row.path("SupplierEmail").asText()).isEqualTo("supplier@example.com");
            assertThat(row.path("SupplierPhone").asText()).isEqualTo("02100000000");
            assertThat(row.path("SupplierBanks").get(0).path("BankNumber").asText()).isEqualTo("104100006062");
            assertThat(row.path("SupplierBanks").get(0).path("SWIFTCode").asText()).isEqualTo("TESTCNBJ");
            assertThat(row.path("SupplierBanks").get(0).path("IBAN").asText()).isEqualTo("CN00TEST");
            assertThat(row.path("SupplierBanks").get(0).path("BankAccount").asText()).isEqualTo("622200001234");
            assertThat(row.path("Contacts").get(0).path("ContactName").asText()).isEqualTo("王联系人");
            assertThat(row.path("Contacts").get(0).path("EmailAddress").asText()).isEqualTo("contact@example.com");
        });
    }

    @Test
    void excludesFreeOfChargeOrderReceiptsFromReconciliation() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001012\",\"CompanyCode\":\"1000\",\"DocumentCurrency\":\"CNY\"}"));
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001012\",\"PurchaseOrderItem\":\"00010\",\"PurchasingItemIsFreeOfCharge\":true,\"PurchaseOrderQuantityUnit\":\"EA\"}"));
            return List.of(objectMapper.readTree("{\"MaterialDocument\":\"5000000012\",\"MaterialDocumentYear\":\"2026\",\"MaterialDocumentItem\":\"0001\",\"PurchaseOrder\":\"4500001012\",\"PurchaseOrderItem\":\"00010\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":5,\"EntryUnit\":\"EA\"}"));
        });
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) controller.reconciliation(mock(HttpServletRequest.class)).get("records");

        assertThat(records).isEmpty();
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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

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
    void calculatesAvailableShippingQuantityFromOrderReceiptsAndUnclearedAsns() throws Exception {
        PortalProperties properties = configuredProperties();
        SapODataClient sapClient = mock(SapODataClient.class);
        VendorScopeResolver scopeResolver = mock(VendorScopeResolver.class);
        when(scopeResolver.resolve(any(HttpServletRequest.class))).thenReturn(new VendorScopeResolver.VendorScope("133000006", "test"));
        when(sapClient.get(any(), eq("133000006"), anyString(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrder".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\"}"));
            if ("A_InbDeliveryHeader".equals(service.getEntity())) return List.of(objectMapper.readTree("{\"InbDelivery\":\"1800000001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"ActualDeliveryQuantity\":15}"));
            return List.of();
        });
        when(sapClient.getByReferences(any(), anyList(), eq("PurchaseOrder"), anyString(), anyInt())).thenAnswer(invocation -> {
            PortalProperties.Service service = invocation.getArgument(0);
            if ("PurchaseOrderItem".equals(service.getEntity())) {
                return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"OrderQuantity\":20,\"PurchaseOrderQuantityUnit\":\"EA\"}"));
            }
            if ("POSubcontractingComponent".equals(service.getEntity())) {
                return List.of(objectMapper.readTree("{\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"ComponentMaterial\":\"COMP-01\",\"RequiredQuantity\":3,\"ComponentUnit\":\"EA\"}"));
            }
            return List.of(objectMapper.readTree("{\"MaterialDocument\":\"5000000001\",\"PurchaseOrder\":\"4500001001\",\"PurchaseOrderItem\":\"00010\",\"GoodsMovementType\":\"101\",\"QuantityInEntryUnit\":10}"));
        });
        when(sapClient.getByReferences(any(), anyList(), eq("Product"), anyString(), anyInt()))
                .thenReturn(List.of(objectMapper.readTree("{\"Product\":\"COMP-01\",\"ProductDescription\":\"外协组件物料描述\"}")));
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

        Map<String, Object> response = controller.data("purchaseOrders", "", 30, mock(HttpServletRequest.class));
        @SuppressWarnings("unchecked")
        List<JsonNode> records = (List<JsonNode>) response.get("records");

        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.path("ReceivedQuantity").asText()).isEqualTo("10");
            assertThat(row.path("CreatedAsnQuantity").asText()).isEqualTo("15");
            assertThat(row.path("UnclearedAsnQuantity").asText()).isEqualTo("5");
            assertThat(row.path("AsnAvailableQuantity").asText()).isEqualTo("5");
            assertThat(row.path("SubcontractingComponents").get(0).path("material").asText()).isEqualTo("COMP-01");
            assertThat(row.path("SubcontractingComponents").get(0).path("description").asText()).isEqualTo("外协组件物料描述");
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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));
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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));
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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));
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
        PortalServiceImpl controller = new PortalServiceImpl(properties, scopeResolver, sapClient, new ODataRecordMapper(objectMapper));

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
        properties.setAuthMode("lark_bitable");
        properties.getBitable().setAppId("cli_test"); properties.getBitable().setAppSecret("test-secret"); properties.getBitable().setAppToken("TaTest"); properties.getBitable().setTableId("tblTest"); properties.getBitable().setSessionSecret("test-session-secret");
        properties.getSap().setUsername("test");
        properties.getSap().setPassword("test");
        configureDirect(properties.getSap().getBusinessPartner(), "A_BusinessPartner");
        configureDirect(properties.getSap().getPurchaseOrder(), "PurchaseOrder");
        configureDirect(properties.getSap().getAsn(), "A_InbDeliveryHeader");
        configurePurchaseOrderScoped(properties.getSap().getOutboundDelivery(), "A_OutbDeliveryItem");
        configureReferenceScoped(properties.getSap().getMaterial(), "A_ProductDescription", "Product");
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

    private void configureReferenceScoped(PortalProperties.Service service, String entity, String referenceField) {
        service.setUrl("https://sap.example.test/odata");
        service.setEntity(entity);
        service.setScopeMode("reference");
        service.setReferenceField(referenceField);
    }
}
