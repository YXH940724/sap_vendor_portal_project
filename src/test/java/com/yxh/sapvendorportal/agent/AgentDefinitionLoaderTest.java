package com.yxh.sapvendorportal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yxh.sapvendorportal.common.security.VendorScopeResolver;
import com.yxh.sapvendorportal.config.PortalProperties;
import com.yxh.sapvendorportal.service.PortalService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDefinitionLoaderTest {
    private final AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper());

    @Test
    void loadsEditableSkillAndMcpToolCatalogue() {
        assertThat(loader.systemPrompt()).contains("供应商协同 Agent").contains("当前登录供应商").contains("未清 ASN 数量").contains("收货凭证 API");
        assertThat(StreamSupport.stream(loader.toolDefinitions().spliterator(), false)
                .map(tool -> tool.path("function").path("name").asText()))
                .containsExactly("query_today_todos", "query_pending_orders", "query_asn_creatable", "query_settlement_receipts", "query_purchase_orders", "query_goods_receipts", "query_asn_status", "query_settlement_candidates", "prepare_asn_draft", "prepare_invoice_draft", "prepare_print_document");
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

    @Test
    void routesSingleTopicStandardRequestsWithoutWaitingForToolSelectionModelCall() {
        var todoRoute = SupplierCollaborationAgent.directRoute("今天我优先处理什么？");
        var pendingRoute = SupplierCollaborationAgent.directRoute("查询今日待交订单");
        var asnCreatableRoute = SupplierCollaborationAgent.directRoute("查询今日可创建 ASN 的订单行");
        var settlementReceiptRoute = SupplierCollaborationAgent.directRoute("查询今日可结算收货凭证");
        var orderRoute = SupplierCollaborationAgent.directRoute("查询采购订单 4500000001 的交期和可发运量");
        var receiptRoute = SupplierCollaborationAgent.directRoute("查询订单 4500000001 的收货凭证");
        var asnRoute = SupplierCollaborationAgent.directRoute("查询 ASN 送货单 4500000001");
        var invoiceRoute = SupplierCollaborationAgent.directRoute("查询采购订单 4500000001 的可结算数量");
        var asnDraftRoute = SupplierCollaborationAgent.directRoute("准备按采购订单 4500000001 创建 ASN");
        var invoiceDraftRoute = SupplierCollaborationAgent.directRoute("准备创建采购订单 4500000001 的预制发票");

        assertThat(todoRoute.toolName()).isEqualTo("query_today_todos");
        assertThat(pendingRoute.toolName()).isEqualTo("query_pending_orders");
        assertThat(asnCreatableRoute.toolName()).isEqualTo("query_asn_creatable");
        assertThat(settlementReceiptRoute.toolName()).isEqualTo("query_settlement_receipts");
        assertThat(orderRoute.toolName()).isEqualTo("query_purchase_orders");
        assertThat(orderRoute.keyword()).isEqualTo("4500000001");
        assertThat(receiptRoute.toolName()).isEqualTo("query_goods_receipts");
        assertThat(asnRoute.toolName()).isEqualTo("query_asn_status");
        assertThat(invoiceRoute.toolName()).isEqualTo("query_settlement_candidates");
        assertThat(invoiceRoute.status()).isEqualTo("可结算");
        assertThat(asnDraftRoute.toolName()).isEqualTo("prepare_asn_draft");
        assertThat(asnDraftRoute.purchaseOrder()).isEqualTo("4500000001");
        assertThat(invoiceDraftRoute.toolName()).isEqualTo("prepare_invoice_draft");
    }

    @Test
    void keepsMultiTopicRequestsForModelReasoning() {
        assertThat(SupplierCollaborationAgent.directRoute("查询采购订单 4500000001 的 ASN 状态和可结算数量")).isNull();
        assertThat(SupplierCollaborationAgent.directRoute("创建 ASN 和预制发票")).isNull();
    }

    @Test
    void buildsTodayTodosWithThreeCategoriesAndActionPaths() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PortalService portal = mock(PortalService.class);
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString();
        when(portal.agentPurchaseOrders("13300006")).thenReturn(List.of(
                objectMapper.readTree("{\"PurchaseOrder\":\"4500000001\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-01\",\"DeliveryDate\":\"" + tomorrow + "\",\"OrderQuantity\":\"10\",\"PurchaseOrderQuantityUnit\":\"EA\",\"AsnAvailableQuantity\":\"5\",\"SupplierConfirmationControlKey\":\"0004\",\"PurchaseOrderStatus\":\"待交货\"}"),
                objectMapper.readTree("{\"PurchaseOrder\":\"4500000002\",\"PurchaseOrderItem\":\"00010\",\"Material\":\"MAT-02\",\"DeliveryDate\":\"" + tomorrow + "\",\"OrderQuantity\":\"3\",\"PurchaseOrderQuantityUnit\":\"EA\",\"AsnAvailableQuantity\":\"3\",\"SupplierConfirmationControlKey\":\"0004\",\"IsReturnsItem\":true,\"PurchaseOrderStatus\":\"待交货\"}")
        ));
        when(portal.agentReconciliation("13300006")).thenReturn(List.of(Map.of(
                "materialDocument", "5000000001", "materialDocumentYear", "2026", "purchaseOrder", "4500000001", "purchaseOrderItem", "00010", "material", "MAT-01", "remainingQuantity", "5", "unit", "EA", "settlementStatus", "可结算"
        )));
        SupplierCollaborationAgent agent = new SupplierCollaborationAgent(
                new DeepSeekChatClient(new PortalProperties(), objectMapper), portal, objectMapper, new AgentDefinitionLoader(objectMapper));

        Map<String, Object> result = agent.chat(new VendorScopeResolver.VendorScope("13300006", "test"), objectMapper.readTree("{\"message\":\"今天我优先处理什么？\"}"));
        Map<String, Object> pendingResult = agent.chat(new VendorScopeResolver.VendorScope("13300006", "test"), objectMapper.readTree("{\"message\":\"查询今日待交订单\"}"));
        Map<String, Object> asnResult = agent.chat(new VendorScopeResolver.VendorScope("13300006", "test"), objectMapper.readTree("{\"message\":\"查询今日可创建 ASN 的订单行\"}"));
        Map<String, Object> settlementResult = agent.chat(new VendorScopeResolver.VendorScope("13300006", "test"), objectMapper.readTree("{\"message\":\"查询今日可结算收货凭证\"}"));

        assertThat(result.get("content").toString())
                .contains("待交订单（2 条）")
                .contains("可创建 ASN（1 条）")
                .contains("可结算收货（1 条）")
                .contains("确认控制码为 0004")
                .contains("业务协同 → 结算对账");
        assertThat(pendingResult.get("content").toString()).contains("待交订单（2 条");
        assertThat(asnResult.get("content").toString()).contains("可创建 ASN（1 条").doesNotContain("4500000002");
        assertThat(settlementResult.get("content").toString()).contains("可结算收货（1 条）");
    }
}
