package com.yxh.sapvendorportal.sap;

import com.yxh.sapvendorportal.config.PortalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ODataUrlBuilderTest {
    @Test
    void addsSupplierScopeAndEscapesSingleQuotes() {
        var service = service();
        var uri = ODataUrlBuilder.scopedQuery(service, "V'01", "", "PurchaseOrder", "LastChangeDateTime desc", 999);
        assertThat(uri.toString()).contains("Supplier%20eq%20%27V%27%2701%27").contains("$top=100");
    }
    @Test
    void refusesUnscopedService() {
        var service = service(); service.setSupplierField("");
        assertThatThrownBy(() -> ODataUrlBuilder.scopedQuery(service, "1000", "", "id", "id asc", 1)).isInstanceOf(ResponseStatusException.class);
    }
    @Test
    void addsConfiguredExpand() {
        var service = service(); service.setExpand("_PurchaseOrderItem");
        assertThat(ODataUrlBuilder.scopedQuery(service, "1000", "", "", "PurchaseOrder asc", 10).toString()).contains("$expand=_PurchaseOrderItem");
    }
    private PortalProperties.Service service() { var service = new PortalProperties.Service(); service.setUrl("https://example.test/odata"); service.setEntity("PurchaseOrder"); service.setSupplierField("Supplier"); return service; }
}
