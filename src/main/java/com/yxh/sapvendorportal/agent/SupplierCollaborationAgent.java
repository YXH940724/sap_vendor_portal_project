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
        List<String> executedToolNames = new ArrayList<>();
        List<String> executionContexts = new ArrayList<>();
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
                executedToolNames.add(call.name());
                toolSummaries.add(Map.of("name", displayToolName(call.name()), "summary", execution.summary()));
                String context = executionContext(call.name(), execution.data());
                if (!context.isBlank()) executionContexts.add(context);
                ObjectNode toolResult = JsonNodeFactory.instance.objectNode();
                toolResult.put("role", "tool");
                toolResult.put("tool_call_id", call.id());
                toolResult.put("content", write(execution.data()));
                messages.add(toolResult);
            }
        }
        if (answer.isBlank()) answer = fallbackAnswer(executedToolNames);
        if (!executionContexts.isEmpty()) answer = answer + "\n\n" + String.join("\n\n", executionContexts);
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

    static String fallbackAnswer(Collection<String> toolNames) {
        boolean asnDraft = toolNames.contains("prepare_asn_draft");
        boolean invoiceDraft = toolNames.contains("prepare_invoice_draft");
        if (asnDraft && invoiceDraft) return "已完成实时数据校验。创建 ASN：业务协同 → 采购订单 → 筛选并勾选订单行 → 基于已选行创建 ASN → 核对表单并提交。创建预制发票：业务协同 → 结算对账 → 筛选并勾选可结算收货行 → 基于已选行创建发票 → 核对表单并提交。";
        if (asnDraft) return "已完成 ASN 可发运行校验。创建 ASN：业务协同 → 采购订单 → 筛选并勾选订单行 → 基于已选行创建 ASN → 核对表单并提交。";
        if (invoiceDraft) return "已完成可结算收货行校验。创建预制发票：业务协同 → 结算对账 → 筛选并勾选可结算收货行 → 基于已选行创建发票 → 核对表单并提交。";
        return "已完成实时数据校验。请根据上述单据结果继续处理。";
    }

    @SuppressWarnings("unchecked")
    static String executionContext(String toolName, Map<String, Object> data) {
        Object recordsValue = data.get("records");
        if (!(recordsValue instanceof List<?> sourceRecords) || sourceRecords.isEmpty()) {
            Object notice = data.containsKey("error") ? data.get("error") : data.getOrDefault("warning", "");
            String error = notice == null ? "" : String.valueOf(notice).trim();
            return error.isBlank() ? "" : "结果说明：" + error;
        }
        List<Map<String, Object>> records = sourceRecords.stream().filter(Map.class::isInstance).map(record -> (Map<String, Object>) record).toList();
        if (records.isEmpty()) return "";
        String title = switch (toolName) {
            case "query_purchase_orders", "prepare_asn_draft" -> "采购订单明细";
            case "query_goods_receipts" -> "收货凭证明细";
            case "query_asn_status" -> "ASN / 送货单明细";
            case "query_settlement_candidates", "prepare_invoice_draft" -> "收货结算明细";
            case "prepare_print_document" -> "打印单据明细";
            default -> "查询单据明细";
        };
        StringBuilder result = new StringBuilder(title).append("（").append(records.size()).append(" 条）：");
        for (Map<String, Object> record : records) result.append("\n- ").append(contextRecord(toolName, record));
        Object stepsValue = data.get("nextSteps");
        if (stepsValue instanceof List<?> steps && !steps.isEmpty() && ("prepare_asn_draft".equals(toolName) || "prepare_invoice_draft".equals(toolName))) {
            result.append("\n操作路径：");
            int index = 1;
            for (Object step : steps) result.append("\n").append(index++).append(". ").append(String.valueOf(step));
        }
        return result.toString();
    }

    private static String contextRecord(String toolName, Map<String, Object> record) {
        return switch (toolName) {
            case "query_purchase_orders", "prepare_asn_draft" -> "采购订单 " + field(record, "PurchaseOrder") + " / 行 " + field(record, "PurchaseOrderItem") + "：物料 " + field(record, "Material") + "，交期 " + field(record, "DeliveryDate") + "，订单数量 " + field(record, "OrderQuantity") + " " + field(record, "PurchaseOrderQuantityUnit") + "，可发运 " + field(record, "AsnAvailableQuantity");
            case "query_goods_receipts" -> "收货凭证 " + field(record, "MaterialDocument") + " / 年度 " + field(record, "MaterialDocumentYear") + " / 项目 " + field(record, "MaterialDocumentItem") + "：采购订单 " + field(record, "PurchaseOrder") + "，物料 " + field(record, "Material") + "，移动类型 " + field(record, "GoodsMovementType") + "，数量 " + field(record, "QuantityInEntryUnit") + " " + field(record, "EntryUnit");
            case "query_asn_status" -> "送货单 " + field(record, "DeliveryDocument") + "：" + field(record, "DeliveryDirection") + "，采购订单 " + field(record, "PurchaseOrder") + " / 行 " + field(record, "PurchaseOrderItem") + "，物料 " + field(record, "Material") + "，发运数量 " + field(record, "ActualDeliveryQuantity") + " " + field(record, "DeliveryQuantityUnit") + "，状态 " + field(record, "OverallStatus");
            case "query_settlement_candidates", "prepare_invoice_draft" -> "收货凭证 " + field(record, "materialDocument") + " / 年度 " + field(record, "materialDocumentYear") + "：采购订单 " + field(record, "purchaseOrder") + " / 行 " + field(record, "purchaseOrderItem") + "，物料 " + field(record, "material") + "，可结算 " + field(record, "remainingQuantity") + " " + field(record, "unit") + "，状态 " + field(record, "settlementStatus");
            default -> "单据 " + field(record, "PurchaseOrder", "DeliveryDocument", "materialDocument") + "：物料 " + field(record, "Material", "material") + "，状态 " + field(record, "PurchaseOrderStatus", "OverallStatus", "settlementStatus");
        };
    }

    private static String field(Map<String, Object> record, String... names) {
        for (String name : names) {
            Object value = record.get(name);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "未提供";
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
                case "prepare_print_document" -> preparePrintDocument(arguments, vendorId);
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
                .limit(15).map(row -> compact(row, List.of("DeliveryDocument", "DeliveryDirection", "InbDelivery", "PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription", "DeliveryDate", "OverallStatus", "ActualDeliveryQuantity", "DeliveryQuantityUnit", "DeliveryDocumentBySupplier"))).toList();
        return new ToolExecution(Map.of("records", records, "count", records.size(), "source", "普通订单：SAP 内向交货单 / ASN API；退货订单：SAP 外向送货单 API"), "查询到 " + records.size() + " 条 ASN / 送货单记录" + documentSummary(records, "DeliveryDocument", "PurchaseOrder"));
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
                .filter(row -> !booleanValue(row, "IsReturnsItem", "ReturnsItem", "ReturnsIndicator"))
                .filter(row -> !booleanValue(row, "IsCompletelyDelivered"))
                .filter(row -> decimal(row.path("AsnAvailableQuantity").asText()) > 0)
                .limit(20).map(this::orderView).toList();
        if (lines.isEmpty()) return new ToolExecution(Map.of("purchaseOrder", purchaseOrder, "records", List.of(), "warning", "未找到可发运订单行；请核对订单归属、已收货数量及未清 ASN 数量。"), "该订单暂无可发运行");
        return new ToolExecution(Map.of(
                "purchaseOrder", purchaseOrder,
                "records", lines,
                "nextSteps", List.of(
                        "进入“业务协同 → 采购订单”页面，以采购订单 " + purchaseOrder + " 筛选对应订单行。",
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
                        "进入“业务协同 → 结算对账”页面，按采购订单或收货凭证筛选“可结算”行，并确认收货凭证、采购订单、物料、已结算数量与剩余可结算数量。",
                        "勾选同一公司代码和币种下需要本次结算的收货行；逐行填写本次结算数量，不能超过 SAP 实时剩余可结算数量。",
                        "点击“基于已选行创建发票”，填写供应商发票号、凭证日期、过账日期、税务确定日期、抬头不含税金额和税额。",
                        "确认含税金额等于不含税金额加税额。Portal 会按 SAP 订单净价分摊行金额，并在提交时重新校验收货、发票、订单、数量、金额、税码、币种和公司代码后创建 SAP 预制发票。"
                )), "查询到 " + records.size() + " 条可用于预制发票草稿的收货行" + documentSummary(records, "materialDocument", "purchaseOrder"));
    }

    private ToolExecution preparePrintDocument(JsonNode input, String vendorId) {
        String documentType = normalized(input.path("documentType").asText());
        String documentNumber = normalized(input.path("documentNumber").asText());
        if (documentNumber.isBlank()) return new ToolExecution(Map.of("error", "请提供需要打印的单据号。"), "缺少单据号，无法提供打印指引");
        if ("purchase_order".equals(documentType)) {
            List<Map<String, Object>> records = portal.agentPurchaseOrders(vendorId).stream()
                    .filter(row -> documentNumber.equals(normalized(row.path("PurchaseOrder").asText())))
                    .limit(50).map(this::printOrderView).toList();
            return new ToolExecution(Map.of(
                    "documentType", "采购订单",
                    "documentNumber", documentNumber,
                    "records", records,
                    "count", records.size(),
                    "source", "SAP 采购订单 API（Portal 当前供应商范围）",
                    "nextSteps", List.of(
                            "进入“业务协同 → 采购订单”页面，以采购订单 " + documentNumber + " 筛选对应行。",
                            "勾选需要打印的订单行；可跨多个订单勾选。核对物料、交期、数量、价格和状态后，点击页面抬头“打印已选 N 张订单”。",
                            "浏览器会打开 A4 打印预览；选择打印机，或选择“另存为 PDF”保存。打印不会修改或发送 SAP 单据。"
                    )), "已核对采购订单 " + documentNumber + " 的 " + records.size() + " 条打印行");
        }
        if ("delivery_note".equals(documentType)) {
            List<Map<String, Object>> records = portal.agentAsns(vendorId).stream()
                    .filter(row -> documentNumber.equals(normalized(row.path("DeliveryDocument").asText())) || documentNumber.equals(normalized(row.path("InbDelivery").asText())))
                    .limit(50).map(row -> compact(row, List.of("DeliveryDocument", "DeliveryDirection", "InbDelivery", "DeliveryDocumentItem", "PurchaseOrder", "PurchaseOrderItem", "Material", "MaterialDescription", "DeliveryDate", "ActualDeliveryDate", "ActualDeliveryQuantity", "DeliveryQuantityUnit", "OverallStatus", "DeliveryDocumentBySupplier", "TransportReference", "Plant", "StorageLocation", "BatchBySupplier", "SupplierAddressStreetName", "SupplierAddressCityName", "DeliveryAddressStreetName", "DeliveryAddressCityName"))).toList();
            return new ToolExecution(Map.of(
                    "documentType", "ASN / 送货单",
                    "documentNumber", documentNumber,
                    "records", records,
                    "count", records.size(),
                    "source", "普通订单：SAP 内向交货单 / ASN API；退货订单：SAP 外向送货单 API",
                    "nextSteps", List.of(
                            "进入“业务协同 → ASN / 发运”页面，以送货单 " + documentNumber + "、关联采购订单或物料筛选对应行。",
                            "勾选需要打印的送货单行；可跨多个送货单勾选。核对单据类型、关联订单、物料、发运数量、日期、供应商发运单号和运输参考号后，点击页面抬头“打印已选 N 张送货单”。",
                            "浏览器会将同一送货单的当前页面行汇总为 A4 打印预览；选择打印机，或选择“另存为 PDF”保存。退货订单展示 SAP 外向送货单数据，打印不会修改或发送 SAP 单据。"
                    )), "已核对送货单 " + documentNumber + " 的 " + records.size() + " 条打印行");
        }
        return new ToolExecution(Map.of("error", "documentType 仅支持 purchase_order 或 delivery_note。"), "不支持的打印单据类型");
    }

    private Map<String, Object> orderView(JsonNode row) {
        return compact(row, List.of("PurchaseOrder", "PurchaseOrderItem", "OrderType", "PurchasingItemIsFreeOfCharge", "PurchaseOrderItemCategory", "IsReturnsItem", "IsCompletelyDelivered", "Material", "MaterialDescription", "Plant", "OrderQuantity", "ReceivedQuantity", "OpenReceiptQuantity", "CreatedAsnQuantity", "UnclearedAsnQuantity", "AsnAvailableQuantity", "PurchaseOrderQuantityUnit", "DeliveryDate", "PurchaseOrderStatus"));
    }

    private Map<String, Object> printOrderView(JsonNode row) {
        return compact(row, List.of("PurchaseOrder", "Material", "MaterialDescription", "OrderQuantity", "PurchaseOrderQuantityUnit", "NetPriceAmount", "NetPriceQuantity", "DocumentCurrency", "DeliveryDate", "Supplier", "SupplierAddressStreetName", "SupplierAddressCityName", "DeliveryAddressStreetName", "DeliveryAddressCityName"));
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
            case "prepare_print_document" -> "单据打印指引";
            default -> name;
        };
    }

    private String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private boolean booleanValue(JsonNode row, String... fields) { for (String field : fields) { JsonNode value = row.path(field); if (value.asBoolean(false) || "X".equalsIgnoreCase(value.asText()) || "true".equalsIgnoreCase(value.asText())) return true; } return false; }
    private String safeText(String value, int max) { String text = value == null ? "" : value.trim(); return text.length() > max ? text.substring(0, max) : text; }
    private int decimal(String value) { try { return new java.math.BigDecimal(value).signum(); } catch (Exception ignored) { return 0; } }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { return "{\"error\":\"工具结果序列化失败\"}"; } }
    private record ToolExecution(Map<String, Object> data, String summary) { }
}
