package com.yxh.sapvendorportal.common.cache;

import com.yxh.sapvendorportal.config.PortalProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VendorDataCacheTest {
    @Test
    void reusesOnlyTheSameVendorAndInvalidatesThatVendorOnRefresh() {
        PortalProperties properties = new PortalProperties();
        properties.setSapCacheTtlSeconds(120);
        VendorDataCache cache = new VendorDataCache(properties);
        AtomicInteger calls = new AtomicInteger();

        String first = cache.get("purchaseOrders", "13300006", "top=100", () -> "result-" + calls.incrementAndGet());
        String second = cache.get("purchaseOrders", "13300006", "top=100", () -> "result-" + calls.incrementAndGet());
        String otherVendor = cache.get("purchaseOrders", "13300007", "top=100", () -> "result-" + calls.incrementAndGet());
        cache.invalidateVendor("13300006");
        String refreshed = cache.get("purchaseOrders", "13300006", "top=100", () -> "result-" + calls.incrementAndGet());

        assertThat(first).isEqualTo("result-1");
        assertThat(second).isEqualTo("result-1");
        assertThat(otherVendor).isEqualTo("result-2");
        assertThat(refreshed).isEqualTo("result-3");
    }
}
