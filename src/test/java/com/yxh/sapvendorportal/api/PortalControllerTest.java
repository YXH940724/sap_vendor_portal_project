package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.sap.SapODataClient;
import com.yxh.sapvendorportal.security.VendorScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    private PortalProperties configuredProperties() {
        PortalProperties properties = new PortalProperties();
        properties.setVendorId("133000006");
        properties.getSap().setUsername("test");
        properties.getSap().setPassword("test");
        configureDirect(properties.getSap().getBusinessPartner());
        configureDirect(properties.getSap().getPurchaseOrder());
        configureDirect(properties.getSap().getAsn());
        configurePurchaseOrderScoped(properties.getSap().getMaterialDocument());
        configurePurchaseOrderScoped(properties.getSap().getSupplierInvoice());
        return properties;
    }

    private void configureDirect(PortalProperties.Service service) {
        service.setUrl("https://sap.example.test/odata");
        service.setEntity("Entity");
        service.setSupplierField("Supplier");
    }

    private void configurePurchaseOrderScoped(PortalProperties.Service service) {
        service.setUrl("https://sap.example.test/odata");
        service.setEntity("Entity");
        service.setScopeMode("purchase_order");
        service.setReferenceField("PurchaseOrder");
    }
}
