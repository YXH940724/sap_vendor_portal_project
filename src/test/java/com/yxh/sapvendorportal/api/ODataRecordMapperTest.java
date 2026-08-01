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
}
