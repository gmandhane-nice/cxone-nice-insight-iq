package com.nice.agentic.config;

import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoint for cache management.
 */
@RestController
@RequestMapping("/admin/cache")
public class CacheAdminController {

    private final CacheManager cacheManager;

    public CacheAdminController(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Evicts all entries from all caches.
     */
    @GetMapping("/clear")
    public Map<String, Object> clearAll() {
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "cleared");
        result.put("caches", cacheManager.getCacheNames());
        return result;
    }
}
