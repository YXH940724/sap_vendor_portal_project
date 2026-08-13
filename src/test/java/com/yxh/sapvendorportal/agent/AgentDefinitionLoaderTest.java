package com.yxh.sapvendorportal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionLoaderTest {
    private final AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper());

    @Test
    void loadsEditableSkillAndMcpToolCatalogue() {
        assertThat(loader.systemPrompt()).contains("供应商协同 Agent").contains("当前登录供应商").contains("未清 ASN 数量").contains("收货凭证 API");
        assertThat(StreamSupport.stream(loader.toolDefinitions().spliterator(), false)
                .map(tool -> tool.path("function").path("name").asText()))
                .containsExactly("query_purchase_orders", "query_goods_receipts", "query_asn_status", "query_settlement_candidates", "prepare_asn_draft", "prepare_invoice_draft", "prepare_print_document");
    }

    @Test
    void limitsFallbackCreationGuidanceToTheRelevantDraftTool() {
        assertThat(SupplierCollaborationAgent.fallbackAnswer(List.of("query_purchase_orders"))).doesNotContain("创建 ASN").doesNotContain("创建预制发票");
        assertThat(SupplierCollaborationAgent.fallbackAnswer(List.of("prepare_asn_draft"))).contains("创建 ASN").doesNotContain("创建预制发票");
        assertThat(SupplierCollaborationAgent.fallbackAnswer(List.of("prepare_invoice_draft"))).contains("创建预制发票").doesNotContain("创建 ASN：");
        assertThat(SupplierCollaborationAgent.fallbackAnswer(List.of("prepare_asn_draft", "prepare_invoice_draft"))).contains("创建 ASN").contains("创建预制发票");
    }

    @Test
    void appendsEveryReturnedDocumentAndOnlyRelevantCreationSteps() {
        Map<String, Object> purchaseOrderData = Map.of("records", List.of(
                Map.of("PurchaseOrder", "4500000001", "PurchaseOrderItem", "00010", "Material", "MAT-01", "DeliveryDate", "2026-08-13", "OrderQuantity", "10", "PurchaseOrderQuantityUnit", "EA", "AsnAvailableQuantity", "6"),
                Map.of("PurchaseOrder", "4500000001", "PurchaseOrderItem", "00020", "Material", "MAT-02", "DeliveryDate", "2026-08-14", "OrderQuantity", "5", "PurchaseOrderQuantityUnit", "EA", "AsnAvailableQuantity", "5")
        ));
        Map<String, Object> asnDraftData = Map.of("records", List.of(
                Map.of("PurchaseOrder", "4500000001", "PurchaseOrderItem", "00010", "Material", "MAT-01", "DeliveryDate", "2026-08-13", "OrderQuantity", "10", "PurchaseOrderQuantityUnit", "EA", "AsnAvailableQuantity", "6")
        ), "nextSteps", List.of("业务协同 → 采购订单", "基于已选行创建 ASN"));

        assertThat(SupplierCollaborationAgent.executionContext("query_purchase_orders", purchaseOrderData)).contains("00010").contains("00020").doesNotContain("操作路径");
        assertThat(SupplierCollaborationAgent.executionContext("prepare_asn_draft", asnDraftData)).contains("操作路径").contains("创建 ASN");
    }
}
