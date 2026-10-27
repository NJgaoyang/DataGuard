package io.github.njgaoyang.dataguard;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayMysqlIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired PolicyEngine policy;
 @Autowired SourceApi sources;
 @Autowired MockMvc mvc;
 @Test void flywayAndFirstUserAreInitialized(){
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM dg_users WHERE username='admin'",Integer.class));
  assertEquals(10,db.queryForObject("""
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name IN
      ('dg_users','dg_sources','dg_source_tables','dg_source_columns','dg_agents',
       'dg_agent_tables','dg_agent_columns','dg_rules','dg_requests','dg_audit')
    """,Integer.class));
 }
 @Test void anonymousApiAndMcpMustBeRejected() throws Exception {
  mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/sources")).andExpect(status().isUnauthorized());
  mvc.perform(post("/mcp").contentType("application/json").accept("application/json","text/event-stream")
    .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
   .andExpect(status().isUnauthorized());
  mvc.perform(get("/api/csrf")).andExpect(status().isOk());
 }
 @Test
 @Transactional
 void disabledOrRoleChangedSessionIsImmediatelyRejected() throws Exception {
  String id=UUID.randomUUID().toString();
  String username="disabled_"+id.substring(0,8);
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,?,?,0,0)",
    id,username,"Disabled admin","test","ADMIN");
  var identity=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(username).roles("ADMIN");
  mvc.perform(get("/api/sources").with(identity)).andExpect(status().isUnauthorized());
  db.update("UPDATE dg_users SET enabled=1,role_code='READER' WHERE id=?",id);
  mvc.perform(get("/api/sources").with(identity)).andExpect(status().isUnauthorized());
 }
 @Test void initialPasswordMustBlockAdministrativeApi() throws Exception {
  mvc.perform(get("/api/sources").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
    .andExpect(status().is(428));
 }
 @Test
 @Transactional
 void adminCanListRulesAndRequestsAgainstRealMySql() throws Exception {
  db.update("UPDATE dg_users SET must_change_password=0 WHERE username='admin'");
  var admin=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN");
  mvc.perform(get("/api/rules").with(admin)).andExpect(status().isOk());
  mvc.perform(get("/api/requests?scope=all").with(admin)).andExpect(status().isOk());
 }
 @Test
 @Transactional
 void revokingPlatformColumnNeverResurrectsOldAgentGrant(){
  String source=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString(),agent=UUID.randomUUID().toString();
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,must_change_password) VALUES(?,?,?,?,?,0)",
    owner,"user_"+owner.substring(0,8),"Test owner","hash","READER");
  db.update("""
    INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext,enabled)
    VALUES(?,?,?,?,?,?,?,?,1)
    """,source,"source","MYSQL","127.0.0.1",3306,"business","reader","encrypted");
  db.update("INSERT INTO dg_source_tables(source_id,table_name) VALUES(?,'orders')",source);
  db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name) VALUES(?,'orders','amount')",source);
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 7 DAY))",
    agent,owner,"Agent",AgentApi.digest("token-"+agent));
  db.update("INSERT INTO dg_agent_tables(agent_id,source_id,table_name) VALUES(?,?,'orders')",agent,source);
  db.update("INSERT INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(?,?,'orders','amount')",agent,source);
  var admin=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
    "admin","N/A",java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
  sources.toggleMask(admin,source,"orders","amount");
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM dg_source_columns WHERE source_id=? AND table_name='orders' AND column_name='amount' AND masked=1",Integer.class,source));
  sources.removeColumn(admin,source,"orders","amount");
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM dg_agent_columns WHERE agent_id=?",Integer.class,agent));
  sources.column(admin,source,"orders",new SourceApi.Grant("amount"));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM dg_agent_columns WHERE agent_id=?",Integer.class,agent));
 }
 @Test
 @Transactional
 void runtimeRulesAreFailClosedAndAnApprovalCanOnlyBeConsumedOnce(){
  String owner=UUID.randomUUID().toString(),reviewer=UUID.randomUUID().toString(),
    source=UUID.randomUUID().toString(),agentId=UUID.randomUUID().toString();
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,must_change_password) VALUES(?,?,?,?,?,0)",
   owner,"owner_"+owner.substring(0,8),"数据申请人","test","READER");
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,must_change_password) VALUES(?,?,?,?,?,0)",
   reviewer,"review_"+reviewer.substring(0,8),"审批人","test","APPROVER");
  db.update("""
    INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext,enabled)
    VALUES(?,?,?,?,?,?,?,?,1)
    """,source,"test","MYSQL","127.0.0.1",3306,"business","readuser","encrypted");
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 7 DAY))",
    agentId,owner,"test-agent",AgentApi.digest("test-"+agentId));
  Map<String,Object> agent=Map.of("id",agentId,"ownerId",owner,"name","test-agent");
  List<String> columns=List.of("amount","order_id");
  assertEquals("DENY",policy.begin(agent,source,"orders",columns,30,null).action());
  db.update("""
    INSERT INTO dg_rules(id,name,source_id,table_name,min_rows,max_rows,action,priority)
    VALUES(?,?,?,'orders',1,50,'ALLOW',10)
    """,UUID.randomUUID().toString(),"small-order-read",source);
  assertEquals("ALLOW",policy.begin(agent,source,"orders",columns,30,null).action());
  db.update("""
    INSERT INTO dg_rules(id,name,source_id,table_name,min_rows,max_rows,action,approver_id,priority)
    VALUES(?,?,?,'orders',51,200,'APPROVAL',?,10)
    """,UUID.randomUUID().toString(),"review-large-read",source,reviewer);
  PolicyEngine.Verdict pending=policy.begin(agent,source,"orders",columns,150,null);
  assertEquals("APPROVAL",pending.action());
  assertEquals(pending.id(),policy.begin(agent,source,"orders",columns,150,null).id());
  db.update("UPDATE dg_requests SET status='APPROVED' WHERE id=?",pending.id());
  assertEquals("ALLOW",policy.begin(agent,source,"orders",columns,150,null).action());
  // Once EXECUTING, the grant cannot be reused.
  assertEquals("APPROVAL",policy.begin(agent,source,"orders",columns,150,null).action());
  // Denials always take precedence even when allow rules remain enabled.
  db.update("""
    INSERT INTO dg_rules(id,name,source_id,table_name,min_rows,max_rows,action,priority)
    VALUES(?,?,?,'orders',1,200,'DENY',1)
    """,UUID.randomUUID().toString(),"emergency-deny",source);
  assertEquals("DENY",policy.begin(agent,source,"orders",columns,30,null).action());
 }
}
