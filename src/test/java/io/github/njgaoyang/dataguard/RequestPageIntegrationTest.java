package io.github.njgaoyang.dataguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
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
class RequestPageIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;

 @Test @Transactional
 void serverPagesTenRowsAndDetailsRespectAccessControl() throws Exception {
  String ownerId=UUID.randomUUID().toString(),otherId=UUID.randomUUID().toString(),
   source=UUID.randomUUID().toString(),agent=UUID.randomUUID().toString();
  String owner="page_"+ownerId.substring(0,8),other="other_"+otherId.substring(0,8);
  for(String[] u:new String[][]{{ownerId,owner},{otherId,other}}){
   db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,'test','READER',1,0)",
    u[0],u[1],u[1]);
  }
  db.update("""
    INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext,enabled)
    VALUES(?,'Pagination source','MYSQL','127.0.0.1',3306,'pagination','ro','encrypted',1)
    """,source);
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3),INTERVAL 3 DAY))",
   agent,ownerId,"Page test Agent",UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
  String requestId=null;
  for(int i=0;i<23;i++){
   String id=UUID.randomUUID().toString();
   if(i==0)requestId=id;
   db.update("""
     INSERT INTO dg_requests(id,agent_id,owner_id,source_id,table_name,columns_csv,
       requested_rows,query_hash,action,status,returned_rows,created_at,expires_at)
     VALUES(?,?,?,?,'orders','order_id',5,?,'ALLOW','SUCCESS',3,?,?)
     """,id,agent,ownerId,source,
      UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),
      Timestamp.valueOf(LocalDateTime.of(2026,10,20,10,0).plusSeconds(i)),
      Timestamp.valueOf(LocalDateTime.of(2028,10,20,10,0)));
  }
  for(int pg=1;pg<=3;pg++){
   String resp=mvc.perform(get("/api/requests/page").param("page",String.valueOf(pg))
     .with(user(owner).roles("READER")))
    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
   JsonNode root=mapper.readTree(resp);
   assertEquals(23,root.path("total").asInt());
   assertEquals(10,root.path("pageSize").asInt());
   assertEquals(pg==3?3:10,root.path("items").size());
  }
  String detail=mvc.perform(get("/api/requests/"+requestId).with(user(owner).roles("READER")))
    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertEquals(requestId,mapper.readTree(detail).path("id").asText());
  mvc.perform(get("/api/requests/"+requestId).with(user(other).roles("READER")))
    .andExpect(status().isNotFound());
  String otherPage=mvc.perform(get("/api/requests/page").with(user(other).roles("READER")))
    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertEquals(0,mapper.readTree(otherPage).path("total").asInt());
  String range=mvc.perform(get("/api/requests/page")
      .param("from","2026-10-21").with(user(owner).roles("READER")))
    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertEquals(0,mapper.readTree(range).path("total").asInt());
 }
}
