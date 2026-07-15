package com.nice.agentic.widget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;

/**
 * Optional Valkey (Redis-compatible) client used by {@link WidgetPayloadResolver} for
 * real-time widget data (currently the {@code queue_state} widget).
 *
 * <p>Dev environment clusters (accessed via SSM tunnel to {@code localhost:6379}):</p>
 * <ul>
 *   <li>{@code ctact}:    clustercfg.dev-cxcv-ctact-valkey.ibtpaj.usw2.cache.amazonaws.com</li>
 *   <li>{@code agctact}:  clustercfg.dev-cxcv-agctact-valkey.ibtpaj.usw2.cache.amazonaws.com</li>
 *   <li>{@code agsessact}: clustercfg.dev-cxcv-agsessact-valkey.ibtpaj.usw2.cache.amazonaws.com</li>
 * </ul>
 *
 * <p>If the host is unreachable (no tunnel, wrong creds) this bean logs a single warning
 * at startup and marks itself as unavailable. All callers receive {@code Optional.empty()}
 * and fall through to stub data — the demo survives without a live Valkey connection.</p>
 */
@Component
public class ValkeyWidgetClient {

    private static final Logger log = LoggerFactory.getLogger(ValkeyWidgetClient.class);

    private final String host;
    private final int    port;

    private Jedis  jedis;
    private boolean available;

    public ValkeyWidgetClient(
            @Value("${valkey.host:localhost}") String host,
            @Value("${valkey.port:6379}")      int port) {
        this.host = host;
        this.port = port;
    }

    @PostConstruct
    void init() {
        try {
            jedis = new Jedis(host, port, /* connectionTimeout */ 2000, /* soTimeout */ 2000);
            jedis.ping(); // fail fast if not reachable
            available = true;
            log.info("ValkeyWidgetClient connected — {}:{}", host, port);
        } catch (Exception e) {
            available = false;
            log.warn("ValkeyWidgetClient could not connect to {}:{} ({}) — " +
                    "real-time widget data will fall back to stubs", host, port, e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        if (jedis != null) {
            try { jedis.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Returns {@code true} when the Valkey connection is healthy.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Fetch the string value for {@code key}.
     *
     * @return the value, or {@code Optional.empty()} if not connected or key is absent.
     */
    public Optional<String> get(String key) {
        if (!available) return Optional.empty();
        try {
            String value = jedis.get(key);
            return Optional.ofNullable(value);
        } catch (JedisException e) {
            log.warn("Valkey GET '{}' failed ({}), falling back to stub", key, e.getMessage());
            available = false; // disable further attempts this session
            return Optional.empty();
        }
    }

    /**
     * Fetch the integer value for {@code key}, returning {@code Optional.empty()} on absence
     * or parse error.
     */
    public Optional<Integer> getInt(String key) {
        return get(key).map(v -> {
            try { return Integer.parseInt(v); }
            catch (NumberFormatException e) { return null; }
        });
    }
}
