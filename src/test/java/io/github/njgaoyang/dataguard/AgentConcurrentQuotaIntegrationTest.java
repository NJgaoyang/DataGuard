package io.github.njgaoyang.dataguard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AgentConcurrentQuotaIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired SecurityControls quota;

 @Test
 void concurrentCallsCannotExceedPerAgentMinuteLimitAndAgentsRemainIsolated() throws Exception {
  String ownerId=UUID.randomUUID().toString();
  String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
  db.update("""
    INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password)
    VALUES(?,?,?,'test','READER',1,0)
    """,ownerId,"quota_parallel_"+ownerId.substring(0,8),"Parallel test owner");
  try {
   for(String id:List.of(a,b)) {
    db.update("""
      INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at)
      VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 1 DAY))
      """,id,ownerId,"Parallel test agent "+id.substring(0,6),
      UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
   }
   CountDownLatch starting=new CountDownLatch(1);
   List<Callable<Integer>> tasks=new ArrayList<>();
   // 70 calls per Agent at the same time, exceeding the per-minute 60-call ceiling.
   for(int n=0;n<140;n++){
    String agent=n%2==0?a:b;
    tasks.add(()->{
     if(!starting.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Workers did not start");
     try{quota.chargeAgent(agent,1);return 200;}
     catch(ResponseStatusException ex){
      assertEquals(HttpStatus.TOO_MANY_REQUESTS,ex.getStatusCode());
      return 429;
     }
    });
   }
   var pool=Executors.newFixedThreadPool(12);
   try{
    var futures=new ArrayList<Future<Integer>>();
    for(var task:tasks)futures.add(pool.submit(task));
    starting.countDown();
    int accepted=0,limited=0;
    for(var future:futures){
     if(future.get(35,TimeUnit.SECONDS)==200)accepted++;
     else limited++;
    }
    assertEquals(140,accepted+limited);
    // The invariant must hold regardless of whether the test spans a minute boundary.
    for(var row:db.queryForList("""
      SELECT agent_id AS agentId,minute_bucket AS minuteBucket,calls,requested_rows AS requestedRows
      FROM dg_agent_minute_usage WHERE agent_id IN (?,?)
      """,a,b)){
     assertTrue(((Number)row.get("calls")).intValue()<=60);
     assertEquals(row.get("calls"),row.get("requestedRows"));
    }
    assertTrue(accepted>=2,"Both Agents must be independently served");
   }finally{pool.shutdownNow();}
  }finally{
   db.update("DELETE FROM dg_agents WHERE id IN (?,?)",a,b);
   db.update("DELETE FROM dg_users WHERE id=?",ownerId);
  }
 }
}
