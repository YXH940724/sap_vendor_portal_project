package com.yxh.sapvendorportal.sap;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SapODataClientTest {
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
