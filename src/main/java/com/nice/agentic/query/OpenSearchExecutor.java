package com.nice.agentic.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the OpenSearch Java client.
 *
 * <p>Two operating modes:</p>
 * <ul>
 *   <li><strong>Not configured</strong> — {@code opensearch.endpoint} is blank.
 *       All calls return an empty list; {@link RealQueryExecutor} falls back to stubs.</li>
 *   <li><strong>Configured / local dev tunnel</strong> — endpoint is
 *       {@code https://localhost:6380} (SSM tunnel →
 *       {@code cxcv-opensearch.dev.wfosaas.internal.com:443}).
 *       Uses {@code TrustSelfSignedStrategy} + {@code NoopHostnameVerifier} to accept the
 *       tunnel's self-signed certificate — same pattern as
 *       {@code PushMissingRecordsOpenSearchConfig#getOpenSearchTransportForLocal()}
 *       in the nrt-cache repo.</li>
 * </ul>
 *
 * <h2>Key indices in dev</h2>
 * <ul>
 *   <li>{@code agent-contact-write-alias} — real-time agent contacts; AHT data lives here.
 *       Filter by {@code skillName} field for per-queue queries.</li>
 *   <li>{@code agent-contact-activity-write-alias} — agent contact activity.</li>
 * </ul>
 */
@Component
public class OpenSearchExecutor {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchExecutor.class);

    private final String endpoint;
    private final String username;
    private final String password;
    private final boolean runningLocally;
    private final int maxConnTotal;
    private final int connectTimeout;

    private OpenSearchClient client;
    private boolean configured;

    public OpenSearchExecutor(
            @Value("${opensearch.endpoint:}")                   String endpoint,
            @Value("${opensearch.username:}")                   String username,
            @Value("${opensearch.password:}")                   String password,
            @Value("${opensearch.running-locally:true}")        boolean runningLocally,
            @Value("${opensearch.max-conn-total:10}")           int maxConnTotal,
            @Value("${opensearch.connect-timeout:5000}")        int connectTimeout) {
        this.endpoint       = endpoint;
        this.username       = username;
        this.password       = password;
        this.runningLocally = runningLocally;
        this.maxConnTotal   = maxConnTotal;
        this.connectTimeout = connectTimeout;
    }

    @PostConstruct
    void init() {
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("OpenSearchExecutor is NOT configured (opensearch.endpoint is blank) — " +
                    "set OPENSEARCH_ENDPOINT env var to enable real queries");
            configured = false;
            return;
        }

        try {
            client    = buildClient();
            configured = true;
            log.info("OpenSearchExecutor initialised — endpoint={} runningLocally={}",
                    endpoint, runningLocally);
        } catch (Exception e) {
            log.warn("OpenSearchExecutor failed to initialise ({}), falling back to stubs",
                    e.getMessage());
            configured = false;
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Run a match query on {@code index}, apply the supplied key=value {@code filters},
     * and return up to {@code limit} hits as a list of maps.
     *
     * <p>If not configured or if the request fails, returns an empty list — callers
     * fall back to stub data.</p>
     */
    public List<Map<String, Object>> search(String index, Map<String, String> filters, int limit) {
        if (!configured) {
            return List.of();
        }
        try {
            SearchResponse<Map> response = client.search(
                s -> {
                    s.index(index).size(limit);
                    if (!filters.isEmpty()) {
                        s.query(q -> q.bool(b -> {
                            filters.forEach((field, value) ->
                                b.must(m -> m.term(t -> t.field(field).value(v -> v.stringValue(value))))
                            );
                            return b;
                        }));
                    }
                    return s;
                },
                Map.class
            );

            List<Map<String, Object>> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    //noinspection unchecked
                    results.add((Map<String, Object>) hit.source());
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("OpenSearch query failed for index {} ({}), returning empty result",
                    index, e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Client construction
    // -------------------------------------------------------------------------

    private OpenSearchClient buildClient() throws Exception {
        URI uri = URI.create(endpoint);
        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

        RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(rc ->
                        rc.setConnectTimeout(connectTimeout)
                          .setSocketTimeout(connectTimeout * 3));

        if (runningLocally) {
            // Dev-tunnel pattern: accept self-signed cert (tunnel terminates TLS at jump-server).
            // Same approach as PushMissingRecordsOpenSearchConfig#getOpenSearchTransportForLocal()
            // in the nrt-cache repo.
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                    .build();

            final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
            if (username != null && !username.isBlank()) {
                credsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));
            }

            builder.setHttpClientConfigCallback((HttpAsyncClientBuilder httpClientBuilder) ->
                httpClientBuilder
                    .setSSLContext(sslContext)
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .setDefaultCredentialsProvider(credsProvider)
                    .setMaxConnTotal(maxConnTotal)
            );
        } else {
            // Production path: use valid CA-signed cert; optionally add credentials
            if (username != null && !username.isBlank()) {
                BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));
                builder.setHttpClientConfigCallback(hc ->
                        hc.setDefaultCredentialsProvider(credsProvider));
            }
        }

        RestClientTransport transport = new RestClientTransport(
                builder.build(), new JacksonJsonpMapper(new ObjectMapper()));
        return new OpenSearchClient(transport);
    }
}
