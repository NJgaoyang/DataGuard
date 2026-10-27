package io.github.njgaoyang.dataguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApprovalScopesIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;

 Set<String> ids(String scope,String name,String role)throws Exception{
  String response=mvc.perform(get("/api/requests").param("scope",scope).with(user(name).roles(role)))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  JsonNode data=mapper.readTree(response);
  Set<String> result=new HashSet<>();
  for(JsonNode item:data)result.add(item.path("id").asText());
  return result;
 }
 @Test @Transactional
 void scopesSeparateAssignedPendingSelfSubmittedAndCompletedForAdminAndApprovers()throws Exception{
  db.update("UPDATE dg_users SET must_change_password=0 WHERE username='admin'");
  String adminId=db.queryForObject("SELECT id FROM dg_users WHERE username='admin'",String.class);
  String ownerId=UUID.randomUUID().toString(),reviewerId=UUID.randomUUID().toString(),
   source=UUID.randomUUID().toString(),foreignAgent=UUID.randomUUID().toString(),
   adminAgent=UUID.randomUUID().toString();
  String owner="scope_owner_"+ownerId.substring(0,8),reviewer="scope_reviewer_"+reviewerId.substring(0,8);
  for(String[] u:new String[][]{{ownerId,owner,"READER"},{reviewerId,reviewer,"APPROVER"}})
   db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,'test',?,1,0)",u[0],u[1],u[1],u[2]);
  db.update("""
    INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext,enabled)
    VALUES(?,'Approval filter source','MYSQL','127.0.0.1',3306,'approval_test','reader','encrypted',1)
    """,source);
  for(String[] agent:new String[][]{{foreignAgent,ownerId},{adminAgent,adminId}})
   db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 3 DAY))",
    agent[0],agent[1],"approval-scopes-"+agent[0].substring(0,8),AgentApi.digest(AgentApi.newToken()));

  String pendingOther=UUID.randomUUID().toString(),pendingSelf=UUID.randomUUID().toString(),
   autoSuccess=UUID.randomUUID().toString(),reviewedRejected=UUID.randomUUID().toString(),
   selfSuccess=UUID.randomUUID().toString();
  for(String[] row:new String[][]{
   {pendingOther,foreignAgent,ownerId,reviewerId,"PENDING","APPROVAL"},
   {pendingSelf,adminAgent,adminId,reviewerId,"PENDING","APPROVAL"},
   {autoSuccess,foreignAgent,ownerId,null,"SUCCESS","ALLOW"},
   {reviewedRejected,foreignAgent,ownerId,reviewerId,"REJECTED","APPROVAL"},
   {selfSuccess,adminAgent,adminId,null,"SUCCESS","ALLOW"}
  }){
   db.update("""
     INSERT INTO dg_requests(id,agent_id,owner_id,source_id,table_name,columns_csv,requested_rows,
       query_hash,approver_id,action,status,expires_at)
     VALUES(?,?,?,?,'orders','order_id',2,?,?,?, ?,DATE_ADD(NOW(3),INTERVAL 3 DAY))
     """,row[0],row[1],row[2],source,UUID.randomUUID().toString().replace("-","").repeat(2),row[3],row[5],row[4]);
  }
  Set<String> allAdmin=ids("all","admin","ADMIN");
  assertTrue(allAdmin.containsAll(Set.of(pendingOther,pendingSelf,autoSuccess,reviewedRejected,selfSuccess)));
  Set<String> toApproveAdmin=ids("pending","admin","ADMIN");
  assertTrue(toApproveAdmin.contains(pendingOther));
  assertFalse(toApproveAdmin.contains(pendingSelf),"Admins cannot approve their own submissions");
  assertFalse(toApproveAdmin.contains(autoSuccess));
  assertFalse(toApproveAdmin.contains(reviewedRejected));
  Set<String> mineAdmin=ids("mine","admin","ADMIN");
  assertTrue(mineAdmin.containsAll(Set.of(pendingSelf,selfSuccess)));
  assertFalse(mineAdmin.contains(pendingOther));
  Set<String> handledAdmin=ids("handled","admin","ADMIN");
  assertTrue(handledAdmin.containsAll(Set.of(autoSuccess,reviewedRejected,selfSuccess)));
  assertFalse(handledAdmin.contains(pendingOther));
  assertFalse(handledAdmin.contains(pendingSelf));

  Set<String> pendingReviewer=ids("pending",reviewer,"APPROVER");
  assertTrue(pendingReviewer.containsAll(Set.of(pendingOther,pendingSelf)));
  assertFalse(pendingReviewer.contains(autoSuccess));
  Set<String> handledReviewer=ids("handled",reviewer,"APPROVER");
  assertTrue(handledReviewer.contains(reviewedRejected));
  assertFalse(handledReviewer.contains(autoSuccess));
  Set<String> mineOwner=ids("mine",owner,"READER");
  assertTrue(mineOwner.containsAll(Set.of(pendingOther,autoSuccess,reviewedRejected)));
  assertFalse(mineOwner.contains(pendingSelf));

  mvc.perform(get("/api/requests").param("scope","pending").with(user(owner).roles("READER")))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/requests").param("scope","unexpected").with(user("admin").roles("ADMIN")))
   .andExpect(status().isBadRequest());
 }
}
