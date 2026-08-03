package com.yxh.sapvendorportal.sap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yxh.sapvendorportal.config.PortalProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class SapODataClient {
    private final PortalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
    public SapODataClient(PortalProperties properties, ObjectMapper objectMapper) { this.properties = properties; this.objectMapper = objectMapper; }

    public List<JsonNode> get(PortalProperties.Service service, String vendorId, String search, String searchField, String orderBy, int top) {
        URI uri = ODataUrlBuilder.scopedQuery(service, vendorId, search, searchField, orderBy, top);
        HttpRequest request = baseRequest(uri).header("Accept", "application/json").GET().build();
        JsonNode payload = execute(request);
        return records(payload);
    }
    public List<JsonNode> getByReferences(PortalProperties.Service service, List<String> references, String referenceField, String orderBy, int top) {
        URI uri = ODataUrlBuilder.referenceScopedQuery(service, references, referenceField, orderBy, top);
        HttpRequest request = baseRequest(uri).header("Accept", "application/json").GET().build();
        return records(execute(request));
    }

    public JsonNode createAsn(String vendorId, JsonNode input) {
        String base = properties.getSap().getAsn().getUrl().replaceAll("/$", "");
        HttpResponse<String> csrf = send(baseRequest(URI.create(base)).header("X-CSRF-Token", "Fetch").GET().build());
        if (csrf.statusCode() >= 400) throw sapError(csrf);
        String csrfToken = csrf.headers().firstValue("x-csrf-token").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP 未返回 CSRF Token，不能创建 ASN。"));
        ObjectNode payload = inboundDeliveryPayload(vendorId, input);
        HttpRequest request = baseRequest(URI.create(base + "/A_InbDeliveryHeader"))
                .header("Accept", "application/json").header("Content-Type", "application/json").header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(write(payload), StandardCharsets.UTF_8)).build();
        return execute(request);
    }

    public JsonNode createSupplierInvoice(String vendorId, JsonNode input) {
        String base = properties.getSap().getSupplierInvoice().getUrl().replaceAll("/$", "");
        HttpResponse<String> csrf = send(baseRequest(URI.create(base)).header("X-CSRF-Token", "Fetch").GET().build());
        if (csrf.statusCode() >= 400) throw sapError(csrf);
        String csrfToken = csrf.headers().firstValue("x-csrf-token").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP 未返回 CSRF Token，不能创建供应商发票。"));
        ObjectNode payload = supplierInvoicePayload(vendorId, input);
        HttpRequest request = baseRequest(URI.create(base + "/A_SupplierInvoice"))
                .header("Accept", "application/json").header("Content-Type", "application/json").header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(write(payload), StandardCharsets.UTF_8)).build();
        return execute(request);
    }

    private ObjectNode inboundDeliveryPayload(String vendorId, JsonNode input) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("Supplier", vendorId);
        copyText(input, payload, "plannedDeliveryDate", "DeliveryDate");
        copyText(input, payload, "transportReference", "BillOfLading");
        ObjectNode itemContainer = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        input.path("items").forEach(source -> {
            ObjectNode item = objectMapper.createObjectNode();
            copyText(source, item, "sourcePurchaseOrder", "ReferenceSDDocument");
            copyText(source, item, "sourcePurchaseOrderItem", "ReferenceSDDocumentItem");
            copyText(source, item, "material", "Material");
            if (source.hasNonNull("quantity")) item.set("ActualDeliveryQuantity", source.get("quantity"));
            copyText(source, item, "unit", "DeliveryQuantityUnit");
            items.add(item);
        });
        itemContainer.set("results", items);
        payload.set("to_DeliveryDocumentItem", itemContainer);
        return payload;
    }
    private ObjectNode supplierInvoicePayload(String vendorId, JsonNode input) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("CompanyCode", input.path("companyCode").asText());
        payload.put("DocumentDate", odataDateTime(input.path("documentDate").asText()));
        payload.put("PostingDate", odataDateTime(input.path("postingDate").asText()));
        payload.put("SupplierInvoiceIDByInvcgParty", input.path("invoiceReference").asText());
        payload.put("InvoicingParty", vendorId);
        payload.put("DocumentCurrency", input.path("documentCurrency").asText());
        payload.set("InvoiceGrossAmount", input.path("grossAmount"));
        payload.put("TaxIsCalculatedAutomatically", true);
        ObjectNode itemContainer = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        int itemNumber = 1;
        for (JsonNode source : input.path("items")) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("SupplierInvoiceItem", String.format("%05d", itemNumber++));
            copyText(source, item, "sourcePurchaseOrder", "PurchaseOrder");
            copyText(source, item, "sourcePurchaseOrderItem", "PurchaseOrderItem");
            item.put("DocumentCurrency", input.path("documentCurrency").asText());
            item.set("SupplierInvoiceItemAmount", source.path("amount"));
            item.set("QuantityInPurchaseOrderUnit", source.path("quantity"));
            copyText(source, item, "unit", "PurchaseOrderQuantityUnit");
            copyText(source, item, "taxCode", "TaxCode");
            copyText(source, item, "sourceMaterialDocument", "ReferenceDocument");
            copyText(source, item, "sourceMaterialDocumentYear", "ReferenceDocumentFiscalYear");
            copyText(source, item, "sourceMaterialDocumentItem", "ReferenceDocumentItem");
            items.add(item);
        }
        itemContainer.set("results", items);
        payload.set("to_SuplrInvcItemPurOrdRef", itemContainer);
        return payload;
    }
    private void copyText(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        String value = source.path(sourceField).asText();
        if (!value.isBlank()) target.put(targetField, value);
    }
    private String odataDateTime(String value) { return value.matches("^\\d{4}-\\d{2}-\\d{2}$") ? value + "T00:00:00" : value; }

    private HttpRequest.Builder baseRequest(URI uri) {
        String authorization = "bearer".equals(properties.getSap().getAuthMode())
                ? "Bearer " + properties.getSap().getBearerToken()
                : "Basic " + Base64.getEncoder().encodeToString((properties.getSap().getUsername() + ":" + properties.getSap().getPassword()).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Authorization", authorization);
    }
    private JsonNode execute(HttpRequest request) { HttpResponse<String> response = send(request); if (response.statusCode() >= 400) throw sapError(response); try { return objectMapper.readTree(response.body()); } catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP 返回了无法解析的响应。", exception); } }
    private HttpResponse<String> send(HttpRequest request) { try { return httpClient.send(request, HttpResponse.BodyHandlers.ofString()); } catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 SAP OData 服务。", exception); } }
    private ResponseStatusException sapError(HttpResponse<String> response) { try { JsonNode error = objectMapper.readTree(response.body()).path("error"); String message = error.path("message").path("value").asText(error.path("message").asText("SAP OData 返回 HTTP " + response.statusCode())); return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message); } catch (Exception ignored) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP OData 返回 HTTP " + response.statusCode()); } }
    private List<JsonNode> records(JsonNode payload) { JsonNode v4 = payload.path("value"); if (v4.isArray()) { List<JsonNode> result = new ArrayList<>(); v4.forEach(result::add); return result; } JsonNode v2 = payload.path("d").path("results"); if (v2.isArray()) { List<JsonNode> result = new ArrayList<>(); v2.forEach(result::add); return result; } return List.of(); }
    private String write(JsonNode value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("无法生成 SAP 请求体。", exception); } }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
