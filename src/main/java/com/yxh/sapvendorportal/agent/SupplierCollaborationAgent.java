package com.yxh.sapvendorportal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yxh.sapvendorportal.api.PortalController;
import com.yxh.sapvendorportal.security.VendorScopeResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SupplierCollaborationAgent {
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_TOOL_ROUNDS = 3;
    private final DeepSeekChatClient deepSeek;
    private final PortalController portal;
    private final ObjectMapper objectMapper;
    private final AgentDefinitionLoader definitions;

    public SupplierCollaborationAgent(DeepSeekChatClient deepSeek, PortalController portal, ObjectMapper objectMapper, AgentDefinitionLoader definitions) {
        this.deepSeek = deepSeek;
        this.portal = portal;
        this.objectMapper = objectMapper;
        this.definitions = definitions;
    }

    public Map<String, Object> status() {
        return Map.of("enabled", deepSeek.configured(), "issue", deepSeek.configurationIssue(), "provider", "DeepSeek AI", "agent", "供应商协同 Agent");
    }

    public Map<String, Object> chat(VendorScopeResolver.VendorScope scope, JsonNode input) {
        String question = safeText(input.path("message").asText(), 2000);
        if (question.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入需要协同处理的问题。");
        ArrayNode messages = initialMessages(input.path("history"), question);
        ArrayNode tools = toolDefinitions();
        List<Map<String, String>> toolSummaries = new ArrayList<>();
        String answer = "";
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            DeepSeekChatClient.Completion completion = deepSeek.complete(messages, tools);
            messages.add(completion.assistantMessage());
            if (completion.toolCalls().isEmpty()) {
                answer = completion.content();
                break;
            }
            for (DeepSeekChatClient.ToolCall call : completion.toolCalls()) {
                ToolExecution execution = executeTool(call.name(), call.arguments(), scope.vendorId());
                toolSummaries.add(Map.of("name", displayToolName(call.name()), "summary", execution.summary()));
                ObjectNode toolResult = JsonNodeFactory.instance.objectNode();
                toolResult.put("role", "tool");
                toolResult.put("tool_call_id", call.id());
                toolResult.put("content", write(execution.data()));
                messages.add(toolResult);
            }
        }
        if (answer.isBlank()) answer = "已完成实时数据校验。请根据下方结果继续操作；涉及创建 ASN 或预制发票时，请在 Portal 表单中确认后提交。";
        return Map.of(
                "content", answer,
                "tools", toolSummaries,
                "vendorScope", "当前登录供应商",
                "retrievedAt", Instant.now().toString()
        );
    }

    private ArrayNode initialMessages(JsonNode history, String question) {
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", definitions.systemPrompt());
        int count = 0;
        for (JsonNode message : history) {
            if (count++ >= MAX_HISTORY_MESSAGES) break;
            String role = message.path("role").asText();
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = safeText(message.path("content").asText(), 1600);
            if (!content.isBlank()) messages.addObject().put("role", role).put("content", content);
        }
        messages.addObject().put("role", "user").put("content", question);
        return messages;
    }

    private ToolExecution executeTool(String name, String rawArguments, String vendorId) {
        try {
            JsonNode arguments = objectMapper.readTree(rawArguments);
            return switch (name) {
                case "query_purchase_orders" -> queryPurchaseOrders(arguments, vendorId);
                case "query_asn_status" -> queryAsnStatus(arguments, vendorId);
                case "query_settlement_candidates" -> querySettlement(arguments, vendorId);
                case "prepare_asn_draft" -> prepareAsnDraft(arguments, vendorId);
                case "prepare_invoice_draft" -> prepareInvoiceDraft(arguments, vendorId);
                default -> new ToolExecution(Map.of("error", "不支持的工具：" + name), "未执行未知工具");
            };
        } catch (Exception exception) {
            String message = exception instanceof ResponseStatusException response && response.getReason() != null ? response.getReason() : "业务数据读取失败。";
            return new ToolExecution(Map.of("error", message), "工具调用失败：" + message);
        }
    }

    private ToolExecution queryPurchaseOrders(JsonNode input, String vendorId) {
        String keyword = normalized(input.path("keyword").asText());
        String status = normalized(input.path("status").asText());
        List<Map<String, Object>> records = portal.agentPurchaseOrders(vendorId).stream()
                .filter(row -> matches(row, keyword, "PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription"))
                .filter(row -> status.isBlank() || normalized(row.path("PurchaseOrderStatus").asText()).contains(status))
                .limit(15).map(this::orderView).toList();
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "SAP 实时采购订单"), "查询到 " + records.size() + " 条采购订单行");
    }

    private ToolExecution queryAsnStatus(JsonNode input, String vendorId) {
        String keyword = normalized(input.path("keyword").asText());
        List<Map<String, Object>> records = portal.agentAsns(vendorId).stream()
                .filter(row -> matches(row, keyword, "InbDelivery", "DeliveryDocument", "PurchaseOrder", "Material", "MaterialDescription"))
                .limit(15).map(row -> compact(row, List.of("InbDelivery", "PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription", "DeliveryDate", "OverallStatus", "ActualDeliveryQuantity", "DeliveryQuantityUnit", "DeliveryDocumentBySupplier"))).toList();
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "SAP 实时 ASN"), "查询到 " + records.size() + " 条 ASN / 发运记录");
    }

    private ToolExecution querySettlement(JsonNode input, String vendorId) {
        String keyword = normalized(input.path("keyword").asText());
        String status = normalized(input.path("status").asText());
        List<Map<String, Object>> records = portal.agentReconciliation(vendorId).stream()
                .filter(row -> matches(row, keyword, "materialDocument", "purchaseOrder", "material", "materialDescription"))
                .filter(row -> status.isBlank() || normalized(String.valueOf(row.get("settlementStatus"))).contains(status))
                .limit(15).map(this::settlementView).toList();
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "SAP 实时收货与结算"), "查询到 " + records.size() + " 条收货结算行");
    }

    private ToolExecution prepareAsnDraft(JsonNode input, String vendorId) {
        String purchaseOrder = normalized(input.path("purchaseOrder").asText());
        if (purchaseOrder.isBlank()) return new ToolExecution(Map.of("error", "请先提供采购订单号。"), "缺少采购订单号，无法生成 ASN 草稿");
        List<Map<String, Object>> lines = portal.agentPurchaseOrders(vendorId).stream()
                .filter(row -> purchaseOrder.equals(normalized(row.path("PurchaseOrder").asText())))
                .filter(row -> decimal(row.path("AsnAvailableQuantity").asText()) > 0)
                .limit(20).map(this::orderView).toList();
        if (lines.isEmpty()) return new ToolExecution(Map.of("purchaseOrder", purchaseOrder, "records", List.of(), "warning", "未找到可发运订单行；请核对订单归属、已创建 ASN 或收货数量。"), "该订单暂无可发运行");
        return new ToolExecution(Map.of("purchaseOrder", purchaseOrder, "records", lines, "nextStep", "请进入采购订单页，选择对应订单行后创建 ASN；Portal 将在提交前二次校验。"), "已生成 " + lines.size() + " 行 ASN 草稿依据");
    }

    private ToolExecution prepareInvoiceDraft(JsonNode input, String vendorId) {
        String purchaseOrder = normalized(input.path("purchaseOrder").asText());
        List<Map<String, Object>> records = portal.agentReconciliation(vendorId).stream()
                .filter(row -> "可结算".equals(String.valueOf(row.get("settlementStatus"))))
                .filter(row -> purchaseOrder.isBlank() || purchaseOrder.equals(normalized(String.valueOf(row.get("purchaseOrder")))))
                .filter(row -> decimal(String.valueOf(row.get("remainingQuantity"))) > 0)
                .limit(20).map(this::settlementView).toList();
        return new ToolExecution(Map.of("purchaseOrder", purchaseOrder, "records", records, "nextStep", "请进入结算对账页，选择收货行后创建预制发票；Portal 会校验数量、金额、税额和税务确定日期。"), "查询到 " + records.size() + " 条可用于预制发票草稿的收货行");
    }

    private Map<String, Object> orderView(JsonNode row) {
        return compact(row, List.of("PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription", "Plant", "OrderQuantity", "ReceivedQuantity", "OpenReceiptQuantity", "AsnAvailableQuantity", "PurchaseOrderQuantityUnit", "DeliveryDate", "PurchaseOrderStatus"));
    }

    private Map<String, Object> settlementView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("materialDocument", "materialDocumentYear", "materialDocumentItem", "purchaseOrder", "purchaseOrderItem", "material", "materialDescription", "postingDate", "receivedQuantity", "settledQuantity", "remainingQuantity", "unit", "settlementStatus", "settlementInvoices")) result.put(key, row.getOrDefault(key, ""));
        return result;
    }

    private Map<String, Object> compact(JsonNode row, Collection<String> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) result.put(field, row.path(field).asText(""));
        return result;
    }

    private boolean matches(JsonNode row, String keyword, String... fields) {
        if (keyword.isBlank()) return true;
        for (String field : fields) if (normalized(row.path(field).asText()).contains(keyword)) return true;
        return false;
    }

    private boolean matches(Map<String, Object> row, String keyword, String... fields) {
        if (keyword.isBlank()) return true;
        for (String field : fields) if (normalized(String.valueOf(row.getOrDefault(field, ""))).contains(keyword)) return true;
        return false;
    }

    private ArrayNode toolDefinitions() {
        return definitions.toolDefinitions();
    }

    private String displayToolName(String name) {
        return switch (name) {
            case "query_purchase_orders" -> "采购订单查询";
            case "query_asn_status" -> "ASN / 发运查询";
            case "query_settlement_candidates" -> "收货结算查询";
            case "prepare_asn_draft" -> "ASN 草稿校验";
            case "prepare_invoice_draft" -> "预制发票草稿校验";
            default -> name;
        };
    }

    private String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private String safeText(String value, int max) { String text = value == null ? "" : value.trim(); return text.length() > max ? text.substring(0, max) : text; }
    private int decimal(String value) { try { return new java.math.BigDecimal(value).signum(); } catch (Exception ignored) { return 0; } }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { return "{\"error\":\"工具结果序列化失败\"}"; } }
    private record ToolExecution(Map<String, Object> data, String summary) { }
}
