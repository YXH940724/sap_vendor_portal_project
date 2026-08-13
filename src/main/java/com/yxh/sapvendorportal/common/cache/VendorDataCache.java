package com.yxh.sapvendorportal.common.cache;

import com.yxh.sapvendorportal.config.PortalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 供应商范围内的短期只读缓存。缓存键始终包含供应商编码，绝不跨供应商复用 SAP 数据；
 * 同一键的并发首读会合并为一次上游请求，避免导航、工作台和 AI 同时触发重复 OData 调用。
 */
@Component
public class VendorDataCache {
    private static final Logger log = LoggerFactory.getLogger(VendorDataCache.class);
    private final PortalProperties properties;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Entry>> loading = new ConcurrentHashMap<>();

    public VendorDataCache(PortalProperties properties) { this.properties = properties; }

    public <T> T get(String domain, String vendorId, String variant, Supplier<T> loader) {
        long ttlMillis = Math.max(0, properties.getSapCacheTtlSeconds()) * 1_000L;
        if (ttlMillis == 0) return loader.get();
        String key = key(domain, vendorId, variant);
        Entry cached = entries.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            log.debug("SAP cache hit: {}", key);
            return cast(cached.value());
        }
        CompletableFuture<Entry> created = new CompletableFuture<>();
        CompletableFuture<Entry> active = loading.putIfAbsent(key, created);
        if (active == null) {
            active = created;
            try {
                T value = loader.get();
                Entry fresh = new Entry(value, System.currentTimeMillis() + ttlMillis);
                entries.put(key, fresh);
                created.complete(fresh);
                log.debug("SAP cache refreshed: {}", key);
            } catch (Throwable exception) {
                created.completeExceptionally(exception);
            } finally {
                loading.remove(key, created);
            }
        } else log.debug("SAP cache coalesced: {}", key);
        try { return cast(active.join().value()); }
        catch (java.util.concurrent.CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    public void invalidateVendor(String vendorId) {
        String prefix = safe(vendorId) + "|";
        entries.keySet().removeIf(key -> key.startsWith(prefix));
        log.debug("SAP cache invalidated for vendor {}", safe(vendorId));
    }

    private String key(String domain, String vendorId, String variant) { return safe(vendorId) + "|" + safe(domain) + "|" + safe(variant); }
    private String safe(String value) { return value == null ? "" : value.replace("|", "%7C"); }
    @SuppressWarnings("unchecked") private <T> T cast(Object value) { return (T) value; }
    private record Entry(Object value, long expiresAt) { }
}
