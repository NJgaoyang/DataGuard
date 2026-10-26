package io.github.njgaoyang.dataguard;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OperationalSecurityTest {
 @Autowired JdbcTemplate db;
 @Autowired SecurityControls controls;
 @Test @Transactional
 void repeatedLoginFailuresLockAccountAndSuccessfulLoginResetsIt(){
  String username="failed_"+UUID.randomUUID().toString().substring(0,12);
  for(int i=0;i<7;i++){controls.failedLogin(username);assertFalse(controls.locked(username));}
  controls.failedLogin(username);
  assertTrue(controls.locked(username));
  controls.successfulLogin(username);
  assertFalse(controls.locked(username));
 }
 @Test @Transactional
 void agentQuotaIsDurableAndEnforcedAtSixtyRequests(){
  String userId=UUID.randomUUID().toString(),agent=UUID.randomUUID().toString();
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,?, 'READER',1,0)",
    userId,"quota_"+userId.substring(0,14),"Quota fixture","test");
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 10 DAY))",
    agent,userId,"Test agent",UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
  for(int i=0;i<60;i++)controls.chargeAgent(agent,1);
  ResponseStatusException rejection=assertThrows(ResponseStatusException.class,()->controls.chargeAgent(agent,1));
  assertEquals(HttpStatus.TOO_MANY_REQUESTS,rejection.getStatusCode());
  Integer used=db.queryForObject("SELECT SUM(calls) FROM dg_agent_minute_usage WHERE agent_id=?",Integer.class,agent);
  assertEquals(60,used);
 }
}
