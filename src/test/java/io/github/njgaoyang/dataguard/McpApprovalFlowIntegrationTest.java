package io.github.njgaoyang.dataguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end MCP/HTTP approval tests backed by a real MySQL metadata database.
 * Only the external business database adapter is stubbed.
 */
@SpringBootTest(properties="dataguard.allowed-source-hosts=127.0.0.1")
@AutoConfigureMockMvc
class McpApprovalFlowIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @MockitoBean BusinessReadExecutor business;

 private JsonNode mcp(String token,String tool,Map<String,Object> args) throws Exception {
  String body=json.writeValueAsString(Map.of(
   "jsonrpc","2.0","id",1,"method","tools/call",
   "params",Map.of("name",tool,"arguments",args)));
  String response=mvc.perform(post("/mcp").contentType("application/json")
      .header("Authorization","Bearer "+token).content(body))
    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  return json.readTree(response);
 }
 private JsonNode toolData(JsonNode rpc) throws Exception {
  assertTrue(rpc.has("result"),rpc.toString());
  return json.readTree(rpc.path("result").path("content").get(0).path("text").asText());
 }
 @Test
 @Transactional
 void pendingApprovalCanBeConsumedExactlyOnceAndNoPolicyOrColumnCanBeBypassed() throws Exception {
  String owner=UUID.randomUUID().toString(),approver=UUID.randomUUID().toString(),
   outsider=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),agent=UUID.randomUUID().toString();
  String ownerName="owner_"+owner.substring(0,8),reviewerName="reviewer_"+approver.substring(0,8),
   outsiderName="other_"+outsider.substring(0,8);
  for(String[] u:new String[][]{{owner,ownerName,"READER"},{approver,reviewerName,"APPROVER"},{outsider,outsiderName,"APPROVER"}})
   db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,?,?,1,0)",
    u[0],u[1],u[1],"test",u[2]);
  db.update("""
   INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext,enabled)
   VALUES(?,'Test business','MYSQL','127.0.0.1',3306,'business','read_only','encrypted',1)
   """,source);
  db.update("INSERT INTO dg_source_tables(source_id,table_name) VALUES(?,'orders')",source);
  db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name) VALUES(?,'orders','order_id')",source);
  db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name) VALUES(?,'orders','amount')",source);
  String token=AgentApi.newToken();
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 7 DAY))",
   agent,owner,"test-ai-agent",AgentApi.digest(token));
  db.update("INSERT INTO dg_agent_tables(agent_id,source_id,table_name) VALUES(?,?,'orders')",agent,source);
  db.update("INSERT INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(?,?,'orders','order_id')",agent,source);
  db.update("INSERT INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(?,?,'orders','amount')",agent,source);
  String rule=UUID.randomUUID().toString();
  db.update("""
   INSERT INTO dg_rules(id,name,source_id,table_name,min_rows,max_rows,action,approver_id,priority)
   VALUES(?,'Bulk approval',?,'orders',51,200,'APPROVAL',?,10)
   """,rule,source,approver);

  Map<String,Object> query=Map.of("sourceId",source,
    "sql","SELECT order_id, amount FROM orders LIMIT 75");
  JsonNode first=mcp(token,"execute_sql_readonly",query);
  assertFalse(first.path("result").path("isError").asBoolean());
  JsonNode pending=toolData(first);
  assertEquals("PENDING",pending.path("status").asText());
  mvc.perform(post("/api/v1/agent/queries").contentType("application/json")
      .header("Authorization","Bearer "+token).content(json.writeValueAsString(query)))
    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
  mvc.perform(get("/api/v1/agent/datasources").header("Authorization","Bearer "+token))
    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(source));
  mvc.perform(get("/api/v1/agent/datasources/"+source+"/tables")
    .header("Authorization","Bearer "+token)).andExpect(status().isOk());
  String requestId=pending.path("requestId").asText();
  assertEquals(36,requestId.length());
  verifyNoInteractions(business);
  assertEquals(requestId,toolData(mcp(token,"execute_sql_readonly",query)).path("requestId").asText());
  assertEquals("PENDING",toolData(mcp(token,"get_access_request",Map.of("requestId",requestId))).path("status").asText());
  String endpoint="/api/requests/"+requestId+"/approve";
  mvc.perform(post(endpoint).with(user(outsiderName).roles("APPROVER")).with(csrf())
     .contentType("application/json").content("{\"reason\":\"Not assigned\"}"))
    .andExpect(status().isForbidden());
  mvc.perform(post(endpoint).with(user(reviewerName).roles("APPROVER")).with(csrf())
     .contentType("application/json").content("{\"reason\":\"Reviewed SQL\"}"))
    .andExpect(status().isOk());
  assertEquals("APPROVED",toolData(mcp(token,"get_access_request",Map.of("requestId",requestId))).path("status").asText());
  when(business.executeDynamic(anyMap(),anyString(),anyList(),eq(75),anyInt()))
   .thenReturn(List.of(Map.of("order_id",123,"amount",456)));
  JsonNode completed=toolData(mcp(token,"execute_sql_readonly",query));
  assertEquals("SUCCESS",completed.path("status").asText(),completed.toString());
  assertEquals(1,completed.path("rowCount").asInt());
  assertEquals(123,completed.path("rows").get(0).path("order_id").asInt());
  assertEquals("SUCCESS",toolData(mcp(token,"get_access_request",Map.of("requestId",requestId))).path("status").asText());
  verify(business,times(1)).executeDynamic(anyMap(),anyString(),anyList(),eq(75),anyInt());
  JsonNode next=toolData(mcp(token,"execute_sql_readonly",query));
  assertEquals("PENDING",next.path("status").asText());
  assertNotEquals(requestId,next.path("requestId").asText());
  db.update("UPDATE dg_rules SET enabled=0,revision=revision+1 WHERE id=?",rule);
  JsonNode denied=mcp(token,"execute_sql_readonly",query);
  assertTrue(denied.path("result").path("isError").asBoolean(),denied.toString());
  assertEquals("ACCESS_DENIED",toolData(denied).path("code").asText());
  verify(business,times(1)).executeDynamic(anyMap(),anyString(),anyList(),eq(75),anyInt());
  mvc.perform(post("/api/v1/agent/queries").contentType("application/json")
     .header("Authorization","Bearer "+token).content(json.writeValueAsString(query)))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  db.update("""
   INSERT INTO dg_rules(id,name,source_id,table_name,min_rows,max_rows,action,priority)
    VALUES(?,'SQL analytics allowed',?,'orders',1,200,'ALLOW',30)
   """,UUID.randomUUID().toString(),source);
  when(business.executeDynamic(anyMap(),contains("COUNT(*)"),anyList(),eq(1),anyInt()))
   .thenReturn(List.of(Map.of("total",7L)));
  Map<String,Object> count=Map.of("sourceId",source,"sql","SELECT COUNT(*) AS total FROM orders LIMIT 1");
  JsonNode counted=toolData(mcp(token,"execute_sql_readonly",count));
  assertEquals("SUCCESS",counted.path("status").asText(),counted.toString());
  assertEquals(7,counted.path("rows").get(0).path("total").asInt());
  mvc.perform(post("/api/v1/agent/queries").contentType("application/json")
    .header("Authorization","Bearer "+token).content(json.writeValueAsString(count)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].total").value(7));
  when(business.executeDynamic(anyMap(),contains("SUM("),anyList(),eq(10),anyInt()))
   .thenReturn(List.of(Map.of("metric",99)));
  Map<String,Object> aggregate=Map.of("sourceId",source,"sql","SELECT SUM(amount) AS metric FROM orders LIMIT 10");
  JsonNode summary=toolData(mcp(token,"execute_sql_readonly",aggregate));
  assertEquals(99,summary.path("rows").get(0).path("metric").asInt());
  mvc.perform(post("/api/v1/agent/queries").contentType("application/json")
    .header("Authorization","Bearer "+token).content(json.writeValueAsString(aggregate)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].metric").value(99));
  // Removed operations must not remain available under either transport.
  for(String obsolete:List.of("execute_readonly","count_rows","aggregate_readonly")){
   JsonNode old=mcp(token,obsolete,Map.of());
   assertTrue(old.path("result").path("isError").asBoolean());
  }
  mvc.perform(post("/api/v1/agent/counts").header("Authorization","Bearer "+token)
    .contentType("application/json").content("{}")).andExpect(status().isNotFound());
  mvc.perform(post("/api/v1/agent/aggregations").header("Authorization","Bearer "+token)
    .contentType("application/json").content("{}")).andExpect(status().isNotFound());
  mvc.perform(post("/api/v1/agent/queries/sql").header("Authorization","Bearer "+token)
    .contentType("application/json").content("{}")).andExpect(status().isNotFound());
  mvc.perform(post("/mcp").contentType("application/json").header("Authorization","Bearer "+token)
     .header("MCP-Protocol-Version","2025-03-26").content(
       "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"));
  mvc.perform(post("/mcp").contentType("application/json").header("Authorization","Bearer "+token)
     .header("MCP-Protocol-Version","1900-01-01").content(
       "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
   .andExpect(status().isBadRequest());
 }
}