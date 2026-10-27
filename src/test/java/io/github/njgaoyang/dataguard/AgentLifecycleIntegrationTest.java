package io.github.njgaoyang.dataguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AgentLifecycleIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired AgentApi agentApi;

 @Test @Transactional
 void stoppedCanEditOrSoftDeleteAndHistoryCannotBeLost() throws Exception {
  db.update("UPDATE dg_users SET must_change_password=0 WHERE username='admin'");
  String owner=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),
   agent=UUID.randomUUID().toString(),rule=UUID.randomUUID().toString(),
   requestId=UUID.randomUUID().toString(),token=AgentApi.newToken();
  String ownerName="owner_lifecycle_"+owner.substring(0,8);
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,?,?,1,0)",
   owner,ownerName,ownerName,"test","READER");
  db.update("""
   INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext,enabled)
   VALUES(?,'Lifecycle MySQL','MYSQL','127.0.0.1',3306,'business','reader','encrypted',1)
   """,source);
  db.update("INSERT INTO dg_source_tables(source_id,table_name) VALUES(?,'orders')",source);
  db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name) VALUES(?,'orders','order_id')",source);
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 7 DAY))",
   agent,owner,"lifecycle-"+agent.substring(0,8),AgentApi.digest(token));
  db.update("INSERT INTO dg_agent_tables(agent_id,source_id,table_name) VALUES(?,?,'orders')",agent,source);
  db.update("INSERT INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(?,?,'orders','order_id')",agent,source);
  db.update("""
   INSERT INTO dg_rules(id,name,source_id,agent_id,table_name,action,priority)
   VALUES(?,'History must survive',?,?,'orders','APPROVAL',10)
   """,rule,source,agent);
  db.update("""
   INSERT INTO dg_requests(id,agent_id,owner_id,source_id,table_name,columns_csv,requested_rows,query_hash,
    rule_id,action,status,expires_at)
   VALUES(?,?,?,?,'orders','order_id',1,?,?,'APPROVAL','PENDING',DATE_ADD(NOW(3),INTERVAL 2 HOUR))
   """,requestId,agent,owner,source,"a".repeat(64),rule);

  assertEquals(agent,agentApi.resolve(token).get("id"));
  var admin=user("admin").roles("ADMIN");
  mvc.perform(delete("/api/agents/"+agent).with(admin).with(csrf()))
   .andExpect(status().isConflict());
  mvc.perform(post("/api/agents/"+agent+"/tables").with(admin).with(csrf())
    .contentType("application/json")
    .content(json.writeValueAsString(Map.of("sourceId",source,"tableName","orders"))))
   .andExpect(status().isConflict());

  mvc.perform(post("/api/agents/"+agent+"/toggle").with(admin).with(csrf()))
   .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
  assertThrows(ResponseStatusException.class,()->agentApi.resolve(token));
  mvc.perform(post("/api/agents/"+agent+"/tables").with(admin).with(csrf())
    .contentType("application/json")
    .content(json.writeValueAsString(Map.of("sourceId",source,"tableName","orders"))))
   .andExpect(status().isOk());
  mvc.perform(post("/api/agents/"+agent+"/toggle").with(admin).with(csrf()))
   .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
   .andExpect(jsonPath("$.token").doesNotExist());
  assertEquals(agent,agentApi.resolve(token).get("id"));

  // A migrated revoked Agent has an invalid old credential even when enabled again.
  mvc.perform(post("/api/agents/"+agent+"/toggle").with(admin).with(csrf()))
   .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
  db.update("UPDATE dg_agents SET token_reset_required=1,token_hash=? WHERE id=?",AgentApi.digest(AgentApi.newToken()),agent);
  var enable=mvc.perform(post("/api/agents/"+agent+"/toggle").with(admin).with(csrf()))
    .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
    .andExpect(jsonPath("$.token").isString()).andReturn().getResponse().getContentAsString();
  String regenerated=json.readTree(enable).get("token").asText();
  assertThrows(ResponseStatusException.class,()->agentApi.resolve(token));
  assertEquals(agent,agentApi.resolve(regenerated).get("id"));

  mvc.perform(post("/api/agents/"+agent+"/toggle").with(admin).with(csrf()))
   .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
  mvc.perform(delete("/api/agents/"+agent).with(admin).with(csrf()))
   .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
  assertNotNull(db.queryForObject("SELECT deleted_at FROM dg_agents WHERE id=?",java.sql.Timestamp.class,agent));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM dg_requests WHERE id=?",Integer.class,requestId));
  assertEquals("DENIED",db.queryForObject("SELECT status FROM dg_requests WHERE id=?",String.class,requestId));
  assertEquals(0,db.queryForObject("SELECT enabled FROM dg_rules WHERE id=?",Integer.class,rule));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM dg_agent_tables WHERE agent_id=?",Integer.class,agent));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM dg_agent_columns WHERE agent_id=?",Integer.class,agent));
  assertThrows(ResponseStatusException.class,()->agentApi.resolve(regenerated));
  JsonNode visible=json.readTree(mvc.perform(get("/api/agents").with(admin))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  for(JsonNode item:visible)assertNotEquals(agent,item.path("id").asText());
  mvc.perform(post("/api/agents/"+agent+"/toggle").with(admin).with(csrf()))
   .andExpect(status().isNotFound());
  mvc.perform(post("/api/agents/"+agent+"/rotate").with(admin).with(csrf())
    .contentType("application/json").content("{\"expiresAt\":\"2099-01-31T23:59:59\"}"))
   .andExpect(status().isForbidden());
 }
}
