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
                case "query_goods_receipts" -> queryGoodsReceipts(arguments, vendorId);
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
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "SAP 采购订单 API"), "查询到 " + records.size() + " 条采购订单行" + documentSummary(records, "PurchaseOrder", "PurchaseOrderItem"));
    }

    private ToolExecution queryGoodsReceipts(JsonNode input, String vendorId) {
        String keyword = normalized(input.path("keyword").asText());
        List<Map<String, Object>> records = portal.agentGoodsReceipts(vendorId).stream()
                .filter(row -> matches(row, keyword, "MaterialDocument", "PurchaseOrder", "Material", "MaterialDescription"))
                .limit(15).map(this::receiptView).toList();
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "SAP 收货凭证 API"), "查询到 " + records.size() + " 条收货凭证" + documentSummary(records, "MaterialDocument", "MaterialDocumentItem"));
    }

    private ToolExecution queryAsnStatus(JsonNode input, String vendorId) {
        String keyword = normalized(input.path("keyword").asText());
        List<Map<String, Object>> records = portal.agentAsns(vendorId).stream()
                .filter(row -> matches(row, keyword, "InbDelivery", "DeliveryDocument", "PurchaseOrder", "Material", "MaterialDescription"))
                .limit(15).map(row -> compact(row, List.of("InbDelivery", "PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription", "DeliveryDate", "OverallStatus", "ActualDeliveryQuantity", "DeliveryQuantityUnit", "DeliveryDocumentBySupplier"))).toList();
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "SAP ASN API"), "查询到 " + records.size() + " 条 ASN / 发运记录" + documentSummary(records, "InbDelivery", "PurchaseOrder"));
    }

    private ToolExecution querySettlement(JsonNode input, String vendorId) {
        String keyword = normalized(input.path("keyword").asText());
        String status = normalized(input.path("status").asText());
        List<Map<String, Object>> records = portal.agentReconciliation(vendorId).stream()
                .filter(row -> matches(row, keyword, "materialDocument", "purchaseOrder", "material", "materialDescription"))
                .filter(row -> status.isBlank() || normalized(String.valueOf(row.get("settlementStatus"))).contains(status))
                .limit(15).map(this::settlementView).toList();
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "SAP 收货凭证、发票与采购订单 API 组合计算"), "查询到 " + records.size() + " 条收货结算行" + documentSummary(records, "materialDocument", "purchaseOrder"));
    }

    private ToolExecution prepareAsnDraft(JsonNode input, String vendorId) {
        String purchaseOrder = normalized(input.path("purchaseOrder").asText());
        if (purchaseOrder.isBlank()) return new ToolExecution(Map.of("error", "请先提供采购订单号。"), "缺少采购订单号，无法生成 ASN 草稿");
        List<Map<String, Object>> lines = portal.agentPurchaseOrders(vendorId).stream()
                .filter(row -> purchaseOrder.equals(normalized(row.path("PurchaseOrder").asText())))
                .filter(row -> decimal(row.path("AsnAvailableQuantity").asText()) > 0)
                .limit(20).map(this::orderView).toList();
        if (lines.isEmpty()) return new ToolExecution(Map.of("purchaseOrder", purchaseOrder, "records", List.of(), "warning", "未找到可发运订单行；请核对订单归属、已收货数量及未清 ASN 数量。"), "该订单暂无可发运行");
        return new ToolExecution(Map.of(
                "purchaseOrder", purchaseOrder,
                "records", lines,
                "nextSteps", List.of(
                        "进入“采购订单”页面，以采购订单 " + purchaseOrder + " 筛选对应订单行。",
                        "核对物料、工厂、交期、订单数量、已收货数量、未清 ASN 数量和可发运量；只勾选本次实际发运的行。",
                        "点击“基于已选行创建 ASN”，填写或核对供应商发运单号、计划到货日期、运输参考号和每行发运数量；数量不得超过可发运量。",
                        "提交前再次确认订单行、物料和数量。Portal 会以 SAP 实时订单、ASN 与收货数据重新校验，并在通过 CSRF 与实体可创建性校验后创建 SAP 内向交货单。"
                )), "已生成 " + lines.size() + " 行 ASN 草稿依据" + documentSummary(lines, "PurchaseOrder", "PurchaseOrderItem"));
    }

    private ToolExecution prepareInvoiceDraft(JsonNode input, String vendorId) {
        String purchaseOrder = normalized(input.path("purchaseOrder").asText());
        List<Map<String, Object>> records = portal.agentReconciliation(vendorId).stream()
                .filter(row -> "可结算".equals(String.valueOf(row.get("settlementStatus"))))
                .filter(row -> purchaseOrder.isBlank() || purchaseOrder.equals(normalized(String.valueOf(row.get("purchaseOrder")))))
                .filter(row -> decimal(String.valueOf(row.get("remainingQuantity"))) > 0)
                .limit(20).map(this::settlementView).toList();
        return new ToolExecution(Map.of(
                "purchaseOrder", purchaseOrder,
                "records", records,
                "nextSteps", List.of(
                        "进入“结算对账”页面，按采购订单或收货凭证筛选“可结算”行，并确认收货凭证、采购订单、物料、已结算数量与剩余可结算数量。",
                        "勾选同一公司代码和币种下需要本次结算的收货行；逐行填写本次结算数量，不能超过 SAP 实时剩余可结算数量。",
                        "点击“基于已选行创建发票”，填写供应商发票号、凭证日期、过账日期、税务确定日期、抬头不含税金额和税额。",
                        "确认含税金额等于不含税金额加税额。Portal 会按 SAP 订单净价分摊行金额，并在提交时重新校验收货、发票、订单、数量、金额、税码、币种和公司代码后创建 SAP 预制发票。"
                )), "查询到 " + records.size() + " 条可用于预制发票草稿的收货行" + documentSummary(records, "materialDocument", "purchaseOrder"));
    }

    private Map<String, Object> orderView(JsonNode row) {
        return compact(row, List.of("PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription", "Plant", "OrderQuantity", "ReceivedQuantity", "OpenReceiptQuantity", "CreatedAsnQuantity", "UnclearedAsnQuantity", "AsnAvailableQuantity", "PurchaseOrderQuantityUnit", "DeliveryDate", "PurchaseOrderStatus"));
    }

    private Map<String, Object> receiptView(JsonNode row) {
        return compact(row, List.of("MaterialDocument", "MaterialDocumentYear", "MaterialDocumentItem", "PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription", "PostingDate", "GoodsMovementType", "QuantityInEntryUnit", "EntryUnit", "ReceiptStatus"));
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

    private String documentSummary(Collection<Map<String, Object>> records, String primaryField, String secondaryField) {
        String documents = records.stream().limit(3).map(record -> {
            String primary = String.valueOf(record.getOrDefault(primaryField, "")).trim();
            String secondary = String.valueOf(record.getOrDefault(secondaryField, "")).trim();
            if (primary.isBlank()) return "";
            return secondary.isBlank() ? primary : primary + "/" + secondary;
        }).filter(value -> !value.isBlank()).reduce((left, right) -> left + "、" + right).orElse("");
        return documents.isBlank() ? "" : "；涉及单据 " + documents;
    }

    private ArrayNode toolDefinitions() {
        return definitions.toolDefinitions();
    }

    private String displayToolName(String name) {
        return switch (name) {
            case "query_purchase_orders" -> "采购订单查询";
            case "query_goods_receipts" -> "收货凭证查询";
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
