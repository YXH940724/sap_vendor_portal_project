package com.yxh.sapvendorportal.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/** 供应商协同门户的业务编排边界，隔离 HTTP 控制器、AI 助手与 SAP 集成实现。 */
public interface PortalService {
    Map<String, Object> health();
    Map<String, String> session(HttpServletRequest request);
    Map<String, Object> dashboard(HttpServletRequest request);
    default Map<String, Object> dashboard(HttpServletRequest request, boolean refresh) { return dashboard(request); }
    Map<String, Object> data(String resourceName, String search, int top, HttpServletRequest request);
    default Map<String, Object> data(String resourceName, String search, int top, HttpServletRequest request, boolean refresh) { return data(resourceName, search, top, request); }
    Map<String, Object> reconciliation(HttpServletRequest request);
    default Map<String, Object> reconciliation(HttpServletRequest request, boolean refresh) { return reconciliation(request); }
    Map<String, Object> createAsn(JsonNode input, HttpServletRequest request);
    Map<String, Object> createInvoice(JsonNode input, HttpServletRequest request);
    List<JsonNode> agentPurchaseOrders(String vendorId);
    List<JsonNode> agentAsns(String vendorId);
    List<JsonNode> agentGoodsReceipts(String vendorId);
    List<Map<String, Object>> agentReconciliation(String vendorId);
}
