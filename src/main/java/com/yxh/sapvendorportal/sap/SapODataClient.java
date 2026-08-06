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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String createPath = asnCreatePath();
        verifyAsnCreateTarget(base, createPath);
        HttpResponse<String> csrf = send(baseRequest(URI.create(base)).header("X-CSRF-Token", "Fetch").GET().build());
        if (csrf.statusCode() >= 400) throw sapError(csrf);
        String csrfToken = csrf.headers().firstValue("x-csrf-token").orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP 未返回 CSRF Token，不能创建 ASN。"));
        ObjectNode payload = inboundDeliveryPayload(vendorId, input);
        HttpRequest request = baseRequest(URI.create(base + "/" + createPath))
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
        payload.put(asnVendorField(), vendorId);
        copyText(input, payload, "portalAsnNumber", "DeliveryDocumentBySupplier");
        String plannedDeliveryDate = input.path("plannedDeliveryDate").asText();
        if (!plannedDeliveryDate.isBlank()) payload.put("DeliveryDate", odataDateTime(plannedDeliveryDate));
        copyText(input, payload, "transportReference", "BillOfLading");
        ObjectNode itemContainer = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        input.path("items").forEach(source -> {
            ObjectNode item = objectMapper.createObjectNode();
            copyText(source, item, "sourcePurchaseOrder", "ReferenceSDDocument");
            copyText(source, item, "sourcePurchaseOrderItem", "ReferenceSDDocumentItem");
            copyText(source, item, "material", "Material");
            if (source.hasNonNull("quantity")) item.put("ActualDeliveryQuantity", source.get("quantity").asText());
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
        payload.put("TaxDeterminationDate", odataDateTime(input.path("taxDeterminationDate").asText()));
        payload.put("SupplierInvoiceIDByInvcgParty", input.path("invoiceReference").asText());
        payload.put("InvoicingParty", vendorId);
        payload.put("DocumentCurrency", input.path("documentCurrency").asText());
        payload.put("InvoiceGrossAmount", input.path("grossAmount").asText());
        copyText(input, payload, "headerText", "DocumentHeaderText");
        payload.put("TaxIsCalculatedAutomatically", true);
        payload.put("SupplierInvoiceStatus", "A");
        ObjectNode itemContainer = objectMapper.createObjectNode();
        ArrayNode items = objectMapper.createArrayNode();
        int itemNumber = 1;
        for (JsonNode source : input.path("items")) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("SupplierInvoiceItem", String.format("%05d", itemNumber++));
            copyText(source, item, "sourcePurchaseOrder", "PurchaseOrder");
            copyText(source, item, "sourcePurchaseOrderItem", "PurchaseOrderItem");
            item.put("DocumentCurrency", input.path("documentCurrency").asText());
            item.put("SupplierInvoiceItemAmount", source.path("amount").asText());
            item.put("QuantityInPurchaseOrderUnit", source.path("quantity").asText());
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
    private String asnCreatePath() {
        String configured = properties.getSap().getAsnCreatePath();
        String path = blank(configured) ? properties.getSap().getAsn().getEntity() : configured.trim();
        if (blank(path) || !path.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ASN 创建实体配置无效；请使用 SAP $metadata 中的实体集名称。 ");
        }
        return path;
    }
    private String asnVendorField() {
        String configured = properties.getSap().getAsnCreateVendorField();
        return blank(configured) ? "Supplier" : configured.trim();
    }
    private void verifyAsnCreateTarget(String base, String createPath) {
        HttpResponse<String> response = send(baseRequest(URI.create(base + "/$metadata")).header("Accept", "application/xml").GET().build());
        if (response.statusCode() >= 400) throw sapError(response);
        verifyEntitySetMetadata(response.body(), createPath);
    }
    static void verifyEntitySetMetadata(String metadata, String entitySet) {
        Pattern pattern = Pattern.compile("<EntitySet\\b(?=[^>]*\\bName\\s*=\\s*\"" + Pattern.quote(entitySet) + "\")[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(metadata);
        if (!matcher.find()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP $metadata 中未找到 ASN 创建实体集 " + entitySet + "；请核对 SAP_ASN_CREATE_PATH。 ");
        }
        String definition = matcher.group();
        if (Pattern.compile("(?:sap:)?creatable\\s*=\\s*\"false\"", Pattern.CASE_INSENSITIVE).matcher(definition).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP $metadata 表明 ASN 实体集 " + entitySet + " 不允许创建；请由 SAP 管理员开放写权限或提供可创建实体。 ");
        }
    }
    private JsonNode execute(HttpRequest request) { HttpResponse<String> response = send(request); if (response.statusCode() >= 400) throw sapError(response); try { return objectMapper.readTree(response.body()); } catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SAP 返回了无法解析的响应。", exception); } }
    private HttpResponse<String> send(HttpRequest request) { try { return httpClient.send(request, HttpResponse.BodyHandlers.ofString()); } catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 SAP OData 服务。", exception); } }
    private ResponseStatusException sapError(HttpResponse<String> response) { try { JsonNode error = objectMapper.readTree(response.body()).path("error"); String message = error.path("message").path("value").asText(error.path("message").asText("")); return new ResponseStatusException(HttpStatus.BAD_GATEWAY, sapErrorMessage(response.statusCode(), message)); } catch (Exception ignored) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, sapErrorMessage(response.statusCode(), "")); } }
    static String sapErrorMessage(int statusCode, String message) {
        String summary = message == null ? "" : message.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (summary.length() > 400) summary = summary.substring(0, 400) + "…";
        return summary.isBlank() ? "SAP OData 请求失败（HTTP " + statusCode + "）。" : "SAP OData 请求失败（HTTP " + statusCode + "）：" + summary;
    }
    private List<JsonNode> records(JsonNode payload) { JsonNode v4 = payload.path("value"); if (v4.isArray()) { List<JsonNode> result = new ArrayList<>(); v4.forEach(result::add); return result; } JsonNode v2 = payload.path("d").path("results"); if (v2.isArray()) { List<JsonNode> result = new ArrayList<>(); v2.forEach(result::add); return result; } return List.of(); }
    private String write(JsonNode value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("无法生成 SAP 请求体。", exception); } }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
