package io.github.njgaoyang.dataguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="dataguard.allowed-source-hosts=127.0.0.1")
@AutoConfigureMockMvc
class SqlQueryIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired SqlQueryService sqlService;
 @MockitoBean BusinessReadExecutor business;

 record Fixture(String agentId,String source,String token,Map<String,Object> principal) {}
 @Transactional
 Fixture fixture(){
  String owner=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),agent=UUID.randomUUID().toString();
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,?,?,1,0)",
   owner,"sqlOwner_"+owner.substring(0,8),"SQL Owner","bcrypt","READER");
  db.update("""
   INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext,enabled)
    VALUES(?,'SQL test source','MYSQL','127.0.0.1',3306,'testdb','ro','test',1)
   """,source);
  String token=AgentApi.newToken();
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 1 DAY))",
   agent,owner,"sql-test",AgentApi.digest(token));
  for(String table:List.of("orders","order_items")){
   db.update("INSERT INTO dg_source_tables(source_id,table_name) VALUES(?,?)",source,table);
   db.update("INSERT INTO dg_agent_tables(agent_id,source_id,table_name) VALUES(?,?,?)",agent,source,table);
  }
  for(String col:List.of("order_id","amount","status","created_at","customer_note")){
   db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name) VALUES(?,'orders',?)",source,col);
   if(!"customer_note".equals(col))
    db.update("INSERT INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(? ,?,'orders',?)",agent,source,col);
  }
  for(String col:List.of("item_id","order_id","quantity","unit_price")){
   db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name) VALUES(?,'order_items',?)",source,col);
   db.update("INSERT INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(? ,?,'order_items',?)",agent,source,col);
  }
  for(String table:List.of("orders","order_items"))
   db.update("INSERT INTO dg_rules(id,name,source_id,agent_id,table_name,min_rows,max_rows,action,priority) VALUES(?,?,?,?,?,1,200,'ALLOW',1)",
    UUID.randomUUID().toString(),"allow SQL",source,agent,table);
  return new Fixture(agent,source,token,Map.of("id",agent,"ownerId",owner,"name","sql-test"));
 }
 private JsonNode tool(String token,String name,Map<String,Object> arg) throws Exception{
  var result=mvc.perform(post("/mcp").contentType("application/json").accept("application/json","text/event-stream")
   .header("Authorization","Bearer "+token)
   .content(json.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method","tools/call",
     "params",Map.of("name",name,"arguments",arg)))))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  return json.readTree(result);
 }
 private JsonNode result(JsonNode response) throws Exception {
  return json.readTree(response.path("result").path("content").get(0).path("text").asText());
 }
 @Test @Transactional
 void twoTableJoinAndCalculationsWorkThroughRestAndMcp() throws Exception {
  Fixture f=fixture();
  String sql="""
   SELECT o.order_id AS id, ROUND(SUM(i.quantity * i.unit_price),2) AS total,
          SUM(CASE WHEN o.status='SUCCESS' THEN 1 ELSE 0 END) AS successes
    FROM orders o LEFT JOIN order_items i ON o.order_id=i.order_id
    GROUP BY o.order_id ORDER BY total DESC LIMIT 10
   """;
  var args=Map.<String,Object>of("sourceId",f.source(),"sql",sql);
  var validated=sqlService.validate(f.principal(),args);
  assertEquals("VALID",validated.get("status"));
  assertEquals(List.of("orders","order_items"),validated.get("tables"));
  when(business.executeDynamic(anyMap(),anyString(),anyList(),eq(10),anyInt()))
   .thenReturn(List.of(Map.of("id",21,"total",120.8,"successes",1)));
  JsonNode result=result(tool(f.token(),"execute_sql_readonly",args));
  assertEquals("SUCCESS",result.path("status").asText(),result.toString());
  assertEquals(21,result.path("rows").get(0).path("id").asInt());
  assertEquals(2,result.path("requestIds").size());
  mvc.perform(post("/api/v1/agent/queries").header("Authorization","Bearer "+f.token())
   .contentType("application/json").content(json.writeValueAsString(args)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.rowCount").value(1));
  mvc.perform(post("/api/v1/agent/queries/validate").header("Authorization","Bearer "+f.token())
   .contentType("application/json").content(json.writeValueAsString(args)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"));
  verify(business,times(2)).executeDynamic(anyMap(),contains("JOIN order_items"),anyList(),eq(10),anyInt());
 }
 @Test @Transactional
 void blockedSqlAndForbiddenColumnsNeverReachBusinessDatabase() throws Exception {
  Fixture f=fixture();
  List<String> denied=List.of(
   "DELETE FROM orders",
   "SELECT * FROM orders",
   "SELECT o.order_id AS id FROM orders o; DROP TABLE orders",
   "SELECT o.order_id AS id FROM orders o JOIN order_items i ON 1=1 LIMIT 10",
   "SELECT o.order_id AS id FROM orders o JOIN order_items i ON o.order_id=o.order_id LIMIT 10",
   "SELECT o.order_id AS id FROM orders o CROSS JOIN order_items i LIMIT 10",
   "SELECT o.order_id AS id FROM orders o JOIN order_items i ON o.order_id=i.order_id WHERE i.secret='x'",
   "SELECT customer_note FROM orders LIMIT 1",
   "SELECT SLEEP(1) AS snooze FROM orders LIMIT 1",
   "SELECT (SELECT COUNT(*) FROM order_items) AS n FROM orders LIMIT 1",
   "SELECT o.order_id AS id FROM orders o JOIN some_private t ON o.order_id=t.id LIMIT 1",
   "SELECT o.order_id AS id FROM orders o JOIN order_items i ON o.order_id=i.order_id WHERE i.quantity=1 OR o.customer_note='secret'"
  );
  for(String sql:denied){
   var args=Map.<String,Object>of("sourceId",f.source(),"sql",sql);
   var rpc=tool(f.token(),"execute_sql_readonly",args);
   assertTrue(rpc.path("result").path("isError").asBoolean(),sql+" => "+rpc);
  }
  verifyNoInteractions(business);
 }
 @Test @Transactional
 void functionConfigurationAndParamBindingDoNotRequireJavaChanges() throws Exception {
  Fixture f=fixture();
  String sql="SELECT o.order_id AS id, ROUND(o.amount, ?) AS rounded FROM orders o WHERE o.status=? LIMIT 3";
  Map<String,Object> args=Map.of("sourceId",f.source(),"sql",sql,"params",List.of(2,"SUCCESS"));
  var plan=sqlService.plan(f.principal(),args);
  assertEquals(2,plan.params().size());
  assertEquals(3,plan.limit());
  assertEquals("VALID",sqlService.validate(f.principal(),Map.of("sourceId",f.source(),
    "sql","SELECT COUNT(*) AS total FROM orders LIMIT 1")).get("status"));
  assertEquals(1,sqlService.plan(f.principal(),Map.of("sourceId",f.source(),
    "sql","SELECT order_id FROM orders WHERE status=? LIMIT 1",
    "params",java.util.Arrays.asList((Object)null))).params().size());
  db.update("UPDATE dg_sql_functions SET enabled=0 WHERE function_name='ROUND'");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,
   ()->sqlService.plan(f.principal(),args));
  db.update("INSERT INTO dg_sql_functions(function_name,enabled) VALUES('RADIANS',1) ON DUPLICATE KEY UPDATE enabled=1");
  assertEquals("VALID",sqlService.validate(f.principal(),Map.of("sourceId",f.source(),
   "sql","SELECT RADIANS(amount) AS rad FROM orders LIMIT 5")).get("status"));
 }
 @Test @Transactional
 void sameSourceFullJoinAllowedOnlyOnStarRocks() {
  Fixture f=fixture();
  String sql="SELECT o.order_id AS id FROM orders o FULL OUTER JOIN order_items i ON o.order_id=i.order_id LIMIT 2";
  assertThrows(org.springframework.web.server.ResponseStatusException.class,
   ()->sqlService.plan(f.principal(),Map.of("sourceId",f.source(),"sql",sql)));
  db.update("UPDATE dg_sources SET type='STARROCKS' WHERE id=?",f.source());
  assertEquals("VALID",sqlService.validate(f.principal(),Map.of("sourceId",f.source(),"sql",sql)).get("status"));
 }
}