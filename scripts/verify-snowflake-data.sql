-- Verify that tenant 11efd95f-eed7-42e0-a6c9-0242ac110002 has data in the views
-- used by the RCA agent. Run these against Snowflake dev (account: cxone_na1_dev).
-- No tunnel needed — Snowflake connects directly over internet.

-- 1. Check agent_contact_fact (AHT data)
SELECT
    COUNT(*) AS total_contacts,
    COUNT(DISTINCT skill_name) AS distinct_skills,
    COUNT(DISTINCT agent_no) AS distinct_agents,
    MIN(TO_TIMESTAMP(agent_contact_start_timestamp/1000)) AS earliest,
    MAX(TO_TIMESTAMP(agent_contact_start_timestamp/1000)) AS latest,
    AVG(handle_seconds) AS avg_aht
FROM DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011
WHERE _tenant_id = '11efd95f-eed7-42e0-a6c9-0242ac110002';

-- 2. List skill names available for this tenant
SELECT DISTINCT skill_name, COUNT(*) AS contacts
FROM DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011
WHERE _tenant_id = '11efd95f-eed7-42e0-a6c9-0242ac110002'
GROUP BY skill_name
ORDER BY contacts DESC
LIMIT 20;

-- 3. Check agent dimension (for tenure/leaderboard)
SELECT COUNT(*) AS agent_count
FROM DATAHUB.SUITE_REFINED.SLIM_AGENT_SCD_DIM_VIEW_V001
WHERE _tenant_id = '11efd95f-eed7-42e0-a6c9-0242ac110002';

-- 4. Check skill dimension
SELECT skill_name, skill_no
FROM DATAHUB.SUITE_REFINED.SKILL_SCD_DIM_VIEW_V001
WHERE _tenant_id = '11efd95f-eed7-42e0-a6c9-0242ac110002'
  AND current_flag = 1
LIMIT 20;

-- 5. Recent contacts (last 24h) — will these be found by our time-window queries?
SELECT COUNT(*) AS last_24h_contacts
FROM DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011
WHERE _tenant_id = '11efd95f-eed7-42e0-a6c9-0242ac110002'
  AND agent_contact_start_timestamp >= DATEADD(hour, -24, CURRENT_TIMESTAMP());
