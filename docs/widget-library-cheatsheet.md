# Widget Library Integration Cheat-Sheet

## Status: STUB — pending real library wiring

### Library to wire
- com.nice.saas.wfo:cxcv-query-engine-business-logic (query engine, Snowflake path)
- Widget payload library: TBD — share Maven coordinates
- OpenSearch client library: TBD — share Maven coordinates

### Integration points (all marked with PLACEHOLDER comments)
1. `WidgetPayloadResolver` — each resolve() method
2. `RealQueryExecutor#executeSnowflake()` — wire real JDBC/query-engine call
3. `RealQueryExecutor#executeOpenSearch()` — wire real OpenSearch client

### What the real resolve() should do (Snowflake path)
1. Build a QueryRequest (com.nice.saas.wfo.qe.api.service.model.QueryRequest)
2. Load TemplateData from DynamoDB or classpath
3. Call buildQueryFromAPIRequestAndTemplate(templateData, queryRequest, true, null, null)
4. Execute SQL via Snowflake JDBC: DriverManager.getConnection(snowflake_url, props)
5. Map ResultSet via SnowflakeCommonUtils.resultSetToList(rs)
6. Return as Map<String, Object>
