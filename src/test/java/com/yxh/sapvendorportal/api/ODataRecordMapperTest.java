package com.yxh.sapvendorportal.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ODataRecordMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ODataRecordMapper mapper = new ODataRecordMapper(objectMapper);

    @Test
    void flattensPurchaseOrderItemsAndNormalizesPortalFields() throws Exception {
        var header = objectMapper.readTree("""
                {"PurchaseOrder":"4500001234","Supplier":"1000000","_PurchaseOrderItem":[
                  {"PurchaseOrderItem":"00010","Material":"MAT-01","PurchaseOrderItemText":"精密轴承","OrderQuantity":500,"PurchaseOrderQuantityUnit":"PC","ScheduleLineDeliveryDate":"2026-08-12","OverallStatus":"待确认"}
                ]}
                """);

        var result = mapper.map("purchaseOrders", List.of(header));

        assertThat(result).hasSize(1);
        var row = result.getFirst();
        assertThat(row.path("PurchaseOrder").asText()).isEqualTo("4500001234");
        assertThat(row.path("MaterialDescription").asText()).isEqualTo("精密轴承");
        assertThat(row.path("DeliveryDate").asText()).isEqualTo("2026-08-12");
        assertThat(row.path("PurchaseOrderStatus").asText()).isEqualTo("待确认");
    }

    @Test
    void recognizesStandardItemDeliveryAndProcessingStatusFields() throws Exception {
        var item = objectMapper.readTree("""
                {"PurchaseOrder":"4500005678","PurchaseOrderItem":"00020","Material":"MAT-02",
                 "PurchaseOrderItemText":"阀门组件","OrderQuantity":12,"OrderQuantityUnit":"EA",
                 "StatDeliveryDate":"2026-09-18","PurchaseOrderItemStatus":"02"}
                """);

        var row = mapper.map("purchaseOrders", List.of(item)).getFirst();

        assertThat(row.path("DeliveryDate").asText()).isEqualTo("2026-09-18");
        assertThat(row.path("PurchaseOrderStatus").asText()).isEqualTo("02");
        assertThat(row.path("PurchaseOrderQuantityUnit").asText()).isEqualTo("EA");
    }

    @Test
    void flattensV4NavigationCollectionWrappedInValue() throws Exception {
        var header = objectMapper.readTree("""
                {"PurchaseOrder":"4500009999","_PurchaseOrderItem":{"value":[
                  {"PurchaseOrderItem":"00010","Material":"MAT-03","OrderQuantity":5,"StatDeliveryDate":"2026-10-01"}
                ]}}
                """);

        var result = mapper.map("purchaseOrders", List.of(header));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().path("PurchaseOrderItem").asText()).isEqualTo("00010");
        assertThat(result.getFirst().path("DeliveryDate").asText()).isEqualTo("2026-10-01");
    }

    @Test
    void flattensInvoiceHeaderUsingPurchaseOrderReferenceNavigation() throws Exception {
        var invoice = objectMapper.readTree("""
                {"SupplierInvoice":"5100000001","DocumentDate":"2026-08-01","InvoiceGrossAmount":"500.00",
                 "SupplierInvoiceStatus":"02","to_SuplrInvcItemPurOrdRef":{"results":[
                   {"SupplierInvoiceItem":"000001","PurchaseOrder":"4500001001","PurchaseOrderItem":"00010"}
                 ]}}
                """);

        var result = mapper.map("invoices", List.of(invoice));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().path("PurchaseOrder").asText()).isEqualTo("4500001001");
        assertThat(result.getFirst().path("InvoiceGrossAmount").asText()).isEqualTo("500.00");
    }

    @Test
    void normalizesInvoiceAndAsnItemTextAsMaterialDescription() throws Exception {
        var invoice = objectMapper.readTree("""
                {"SupplierInvoice":"5100000002","to_SuplrInvcItemPurOrdRef":{"results":[
                  {"PurchaseOrder":"4500001001","PurchaseOrderItem":"00010","SupplierInvoiceItemText":"轴承结算项目"}
                ]}}
                """);
        var asn = objectMapper.readTree("""
                {"DeliveryDocument":"1800000002","to_DeliveryDocumentItem":{"results":[
                  {"ReferenceSDDocument":"4500001001","ReferenceSDDocumentItem":"00010","ItemText":"轴承发运行"}
                ]}}
                """);

        assertThat(mapper.map("invoices", List.of(invoice)).getFirst().path("MaterialDescription").asText()).isEqualTo("轴承结算项目");
        assertThat(mapper.map("asns", List.of(asn)).getFirst().path("MaterialDescription").asText()).isEqualTo("轴承发运行");
    }

    @Test
    void normalizesInboundDeliveryStandardHeaderFields() throws Exception {
        var delivery = objectMapper.readTree("""
                {"DeliveryDocument":"180000001","DeliveryDate":"2026-08-08","OverallGoodsMovementStatus":"C"}
                """);

        var row = mapper.map("asns", List.of(delivery)).getFirst();

        assertThat(row.path("InbDelivery").asText()).isEqualTo("180000001");
        assertThat(row.path("OverallStatus").asText()).isEqualTo("C");
    }

    @Test
    void flattensInboundDeliveryItemsAndCarriesHeaderStatus() throws Exception {
        var delivery = objectMapper.readTree("""
                {"DeliveryDocument":"180000001","DeliveryDate":"/Date(1786233600000)/","OverallGoodsMovementStatus":"A",
                 "to_DeliveryDocumentItem":{"results":[
                   {"DeliveryDocumentItem":"000010","ReferenceSDDocument":"4500001001","ReferenceSDDocumentItem":"00010","Material":"MAT-01"}
                 ]}}
                """);

        var result = mapper.map("asns", List.of(delivery));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().path("PurchaseOrder").asText()).isEqualTo("4500001001");
        assertThat(result.getFirst().path("PurchaseOrderItem").asText()).isEqualTo("00010");
        assertThat(result.getFirst().path("OverallStatus").asText()).isEqualTo("A");
    }

    @Test
    void copiesMaterialDocumentPostingDateFromExpandedHeader() throws Exception {
        var item = objectMapper.readTree("""
                {"MaterialDocument":"5000000001","MaterialDocumentItem":"0001","PurchaseOrder":"4500001001",
                 "to_MaterialDocumentHeader":{"PostingDate":"/Date(1786233600000)/"}}
                """);

        var row = mapper.map("materialDocuments", List.of(item)).getFirst();

        assertThat(row.path("PostingDate").asText()).isEqualTo("/Date(1786233600000)/");
    }
}
