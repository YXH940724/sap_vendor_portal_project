package com.yxh.sapvendorportal.sap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.config.PortalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SapODataClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesAsnDeliveryDateAsODataDateTime() throws Exception {
        SapODataClient client = new SapODataClient(new PortalProperties(), objectMapper);
        Method payloadMethod = SapODataClient.class.getDeclaredMethod("inboundDeliveryPayload", String.class, com.fasterxml.jackson.databind.JsonNode.class);
        payloadMethod.setAccessible(true);

        com.fasterxml.jackson.databind.node.ObjectNode payload = (com.fasterxml.jackson.databind.node.ObjectNode) payloadMethod.invoke(client, "133000006", objectMapper.readTree("""
                {"portalAsnNumber":"PASN-20260806000000-ABC12345","plannedDeliveryDate":"2026-08-06","items":[
                  {"sourcePurchaseOrder":"4500000010","quantity":12.50,"unit":"EA"}
                ]}
                """));

        assertThat(payload.path("DeliveryDate").asText()).isEqualTo("2026-08-06T00:00:00");
        assertThat(payload.path("DeliveryDocumentBySupplier").asText()).isEqualTo("PASN-20260806000000-ABC12345");
        assertThat(payload.path("to_DeliveryDocumentItem").path("results").get(0).path("ActualDeliveryQuantity").isTextual()).isTrue();
        assertThat(payload.path("to_DeliveryDocumentItem").path("results").get(0).path("ActualDeliveryQuantity").asText()).isEqualTo("12.5");
    }

    @Test
    void createsParkedInvoiceWithODataDecimalStrings() throws Exception {
        SapODataClient client = new SapODataClient(new PortalProperties(), objectMapper);
        Method payloadMethod = SapODataClient.class.getDeclaredMethod("supplierInvoicePayload", String.class, com.fasterxml.jackson.databind.JsonNode.class);
        payloadMethod.setAccessible(true);

        com.fasterxml.jackson.databind.node.ObjectNode payload = (com.fasterxml.jackson.databind.node.ObjectNode) payloadMethod.invoke(client, "133000006", objectMapper.readTree("""
                {"companyCode":"1000","documentDate":"2026-08-06","postingDate":"2026-08-06","taxDeterminationDate":"2026-08-06",
                 "invoiceReference":"INV-001","documentCurrency":"CNY","grossAmount":119.00,
                 "items":[{"amount":100.00,"quantity":10.00,"unit":"EA"}]}
                """));

        assertThat(payload.path("SupplierInvoiceStatus").asText()).isEqualTo("A");
        assertThat(payload.path("TaxDeterminationDate").asText()).isEqualTo("2026-08-06T00:00:00");
        assertThat(payload.path("InvoiceGrossAmount").isTextual()).isTrue();
        assertThat(payload.path("InvoiceGrossAmount").asText()).isEqualTo("119.0");
        assertThat(payload.path("to_SuplrInvcItemPurOrdRef").path("results").get(0).path("SupplierInvoiceItemAmount").isTextual()).isTrue();
        assertThat(payload.path("to_SuplrInvcItemPurOrdRef").path("results").get(0).path("QuantityInPurchaseOrderUnit").isTextual()).isTrue();
    }

    @Test
    void acceptsCreatableAsnEntitySetFromMetadata() {
        SapODataClient.verifyEntitySetMetadata("""
                <edmx:DataServices><Schema><EntityContainer>
                <EntitySet Name="A_InbDeliveryHeader" EntityType="API.A_InbDeliveryHeaderType" sap:creatable="true"/>
                </EntityContainer></Schema></edmx:DataServices>
                """, "A_InbDeliveryHeader");
    }

    @Test
    void rejectsAsnEntitySetMarkedNotCreatable() {
        assertThatThrownBy(() -> SapODataClient.verifyEntitySetMetadata("""
                <EntitySet Name="A_InbDeliveryHeader" sap:creatable="false"/>
                """, "A_InbDeliveryHeader"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason()).contains("不允许创建"));
    }

    @Test
    void createsShortSafeSapErrorSummary() {
        assertThat(SapODataClient.sapErrorMessage(400, "  字段 DeliveryDate 不存在\n"))
                .isEqualTo("SAP OData 请求失败（HTTP 400）：字段 DeliveryDate 不存在");
    }
}
