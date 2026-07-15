package com.nice.agentic.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import javax.net.ssl.SSLContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeds demo data into Valkey and OpenSearch for the Banking AHT spike scenario.
 * Activate with: --spring.profiles.active=dev,seed
 *
 * This runner populates:
 * - Valkey: queue state keys (queue:depth, queue:wait, queue:sla)
 * - OpenSearch: agent-contact-write-alias with realistic agent contact documents
 */
@Component
@Profile("seed")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String OS_INDEX = "agent-contact-write-alias";
    private static final String TENANT_ID = "11efd95f-eed7-42e0-a6c9-0242ac110002";

    @Value("${valkey.host:localhost}")
    private String valkeyHost;

    @Value("${valkey.port:6379}")
    private int valkeyPort;

    @Value("${opensearch.endpoint:https://localhost:6380}")
    private String osEndpoint;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(String... args) throws Exception {
        log.info("=== DevDataSeeder starting — seeding Valkey + OpenSearch with Banking AHT demo data ===");

        boolean valkeyOk = seedValkey();
        boolean osOk = seedOpenSearch();

        log.info("=== DevDataSeeder complete — Valkey: {} | OpenSearch: {} ===",
                valkeyOk ? "OK" : "SKIPPED", osOk ? "OK" : "SKIPPED");
    }

    // -------------------------------------------------------------------------
    // Valkey Seeding
    // -------------------------------------------------------------------------

    private boolean seedValkey() {
        try (Jedis jedis = new Jedis(valkeyHost, valkeyPort, 3000, 3000)) {
            jedis.ping();
            log.info("Connected to Valkey at {}:{}", valkeyHost, valkeyPort);

            // Queue state keys for Banking skill
            jedis.set("queue:depth:Banking", "23");
            jedis.set("queue:wait:Banking", "187");
            jedis.set("queue:sla:Banking", "0.82");

            // Queue state for other skills
            jedis.set("queue:depth:General-Support", "4");
            jedis.set("queue:wait:General-Support", "32");
            jedis.set("queue:sla:General-Support", "0.96");

            jedis.set("queue:depth:Collections", "11");
            jedis.set("queue:wait:Collections", "98");
            jedis.set("queue:sla:Collections", "0.88");

            // Staffing keys — real-time agent counts
            jedis.set("staffing:Banking:onShift", "44");
            jedis.set("staffing:Banking:available", "38");
            jedis.set("staffing:Banking:inCall", "29");
            jedis.set("staffing:Banking:newHireCount", "14");

            jedis.set("staffing:General-Support:onShift", "28");
            jedis.set("staffing:General-Support:available", "22");
            jedis.set("staffing:General-Support:inCall", "15");

            jedis.set("staffing:Collections:onShift", "18");
            jedis.set("staffing:Collections:available", "12");
            jedis.set("staffing:Collections:inCall", "11");

            // Agent-level AHT tracking (used by coach trigger)
            jedis.hset("agent:AG-042", "name", "Jordan M.");
            jedis.hset("agent:AG-042", "skill", "Banking");
            jedis.hset("agent:AG-042", "currentAht", "612");
            jedis.hset("agent:AG-042", "baselineAht", "498");
            jedis.hset("agent:AG-042", "tenure", "21");
            jedis.hset("agent:AG-042", "contactsToday", "8");

            // Snapshot keys for the agent-contact cache pattern
            // Key: snapshot:index:<tenantId> — set of agent-contact IDs currently in cache
            jedis.sadd("snapshot:index:" + TENANT_ID,
                    "ac-001", "ac-002", "ac-003", "ac-004", "ac-005",
                    "ac-006", "ac-007", "ac-008", "ac-009", "ac-010");

            log.info("Valkey seeded: queue state, staffing, agent profiles, snapshot index");
            return true;
        } catch (Exception e) {
            log.warn("Valkey seeding skipped — could not connect to {}:{} ({}). " +
                    "Start SSM tunnel first.", valkeyHost, valkeyPort, e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // OpenSearch Seeding
    // -------------------------------------------------------------------------

    private boolean seedOpenSearch() {
        if (osEndpoint == null || osEndpoint.isBlank()) {
            log.warn("OpenSearch seeding skipped — no endpoint configured");
            return false;
        }

        try {
            OpenSearchClient client = buildOsClient();

            List<BulkOperation> ops = new ArrayList<>();
            Instant baseTime = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(4, ChronoUnit.HOURS);
            Random rng = new Random(42);

            // Generate 50 agent-contact documents for Banking skill
            // Mix of veterans (low AHT) and new hires (high AHT)
            String[] veteranAgents = {"AG-101", "AG-102", "AG-103", "AG-104", "AG-105",
                                      "AG-106", "AG-107", "AG-108", "AG-109", "AG-110"};
            String[] newHireAgents = {"AG-201", "AG-202", "AG-203", "AG-204", "AG-205",
                                      "AG-206", "AG-207", "AG-208", "AG-209", "AG-210",
                                      "AG-211", "AG-212", "AG-213", "AG-214"};

            int docId = 1;

            // Veteran contacts (low AHT: 240-340s) — spread across full day
            for (int i = 0; i < 25; i++) {
                String agent = veteranAgents[i % veteranAgents.length];
                long handleSeconds = 240 + rng.nextInt(100);
                long activeSeconds = handleSeconds - 30 - rng.nextInt(20);
                long acwSeconds = handleSeconds - activeSeconds;
                Instant start = baseTime.plus(rng.nextInt(240), ChronoUnit.MINUTES);

                Map<String, Object> doc = buildAgentContactDoc(
                        "ac-v-" + docId, agent, "Banking", start,
                        handleSeconds, activeSeconds, acwSeconds, 0, false);
                ops.add(buildIndexOp(OS_INDEX, "ac-v-" + docId, doc));
                docId++;
            }

            // New-hire contacts (high AHT: 480-680s) — only after 11:00
            Instant newHireStart = baseTime.plus(2, ChronoUnit.HOURS); // 11:00
            for (int i = 0; i < 25; i++) {
                String agent = newHireAgents[i % newHireAgents.length];
                long handleSeconds = 480 + rng.nextInt(200);
                long holdSeconds = 30 + rng.nextInt(60);
                long activeSeconds = handleSeconds - holdSeconds - 30 - rng.nextInt(30);
                long acwSeconds = handleSeconds - activeSeconds - holdSeconds;
                Instant start = newHireStart.plus(rng.nextInt(120), ChronoUnit.MINUTES);

                Map<String, Object> doc = buildAgentContactDoc(
                        "ac-n-" + docId, agent, "Banking", start,
                        handleSeconds, activeSeconds, acwSeconds, holdSeconds, true);
                ops.add(buildIndexOp(OS_INDEX, "ac-n-" + docId, doc));
                docId++;
            }

            // Index all documents
            BulkRequest bulkRequest = new BulkRequest.Builder()
                    .operations(ops)
                    .build();

            BulkResponse response = client.bulk(bulkRequest);
            if (response.errors()) {
                log.warn("OpenSearch bulk had errors — some documents may not have indexed");
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .limit(5)
                        .forEach(item -> log.warn("  Error: {} — {}", item.id(), item.error().reason()));
            } else {
                log.info("OpenSearch seeded: {} documents indexed into '{}'", ops.size(), OS_INDEX);
            }

            return true;
        } catch (Exception e) {
            log.warn("OpenSearch seeding skipped — could not connect to {} ({}). " +
                    "Start SSM tunnel first.", osEndpoint, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildAgentContactDoc(
            String agentContactId, String agentId, String skillName,
            Instant startTime, long handleSeconds, long activeSeconds,
            long acwSeconds, long holdSeconds, boolean isNewHire) {

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("agentContactId", agentContactId);
        doc.put("tenantId", TENANT_ID);
        doc.put("userId", agentId);
        doc.put("agentNo", Integer.parseInt(agentId.replace("AG-", "")));
        doc.put("skillName", skillName);
        doc.put("skillNo", 1001);
        doc.put("channelName", "Phone");
        doc.put("channelCategoryName", "Voice");
        doc.put("channelNo", 1);
        doc.put("contactTypeName", isNewHire ? "billing-dispute" : "account-inquiry");
        doc.put("contactTypeNo", isNewHire ? 5 : 2);
        doc.put("contactDirectionName", "Inbound");
        doc.put("contactDirectionNo", 1);
        doc.put("agentContactStartTimestamp", startTime.toEpochMilli());
        doc.put("agentContactEndTimestamp", startTime.plusSeconds(handleSeconds).toEpochMilli());
        doc.put("handleSeconds", handleSeconds);
        doc.put("activeSeconds", activeSeconds);
        doc.put("acwSeconds", acwSeconds);
        doc.put("holdSeconds", holdSeconds);
        doc.put("holdCount", holdSeconds > 0 ? 1 : 0);
        doc.put("talkTimeSeconds", activeSeconds);
        doc.put("agentContactDurationSeconds", handleSeconds);
        doc.put("focusCount", 1);
        doc.put("refused", false);
        doc.put("transferred", false);
        doc.put("consult", false);
        doc.put("elevated", false);
        doc.put("evicted", false);
        doc.put("teamId", isNewHire ? "team-new-hire" : "team-banking-veteran");
        doc.put("teamNo", isNewHire ? 200L : 100L);
        doc.put("busNo", 1);
        doc.put("divisionNo", 1);
        return doc;
    }

    @SuppressWarnings("unchecked")
    private BulkOperation buildIndexOp(String index, String id, Map<String, Object> doc) {
        return new BulkOperation.Builder()
                .index(new IndexOperation.Builder<>()
                        .index(index)
                        .id(id)
                        .document(doc)
                        .build())
                .build();
    }

    private OpenSearchClient buildOsClient() throws Exception {
        java.net.URI uri = java.net.URI.create(osEndpoint);
        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                .build();

        RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(rc -> rc.setConnectTimeout(5000).setSocketTimeout(15000))
                .setHttpClientConfigCallback((HttpAsyncClientBuilder hc) ->
                        hc.setSSLContext(sslContext)
                          .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                          .setMaxConnTotal(5));

        RestClientTransport transport = new RestClientTransport(
                builder.build(), new JacksonJsonpMapper(objectMapper));
        return new OpenSearchClient(transport);
    }
}
