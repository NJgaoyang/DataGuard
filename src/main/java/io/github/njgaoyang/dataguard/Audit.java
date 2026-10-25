package io.github.njgaoyang.dataguard;

import java.util.Map;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@Service
class Audit {
 private final JdbcTemplate db;
 Audit(JdbcTemplate db){this.db=db;}
 void log(String actor,String action,String requestId,String object,String result,String detail){
  String clean=detail==null?"":detail.replaceAll("[\\r\\n\\t]"," ");
  db.update("INSERT INTO dg_audit(actor,event_type,request_id,object_ref,result,message) VALUES(?,?,?,?,?,?)",
   actor,action,requestId,object,result,clean.substring(0,Math.min(512,clean.length())));
 }
 @RestController @RequestMapping("/api/audit")
 static class Api {
  private final JdbcTemplate db; Api(JdbcTemplate db){this.db=db;}
  @GetMapping List<Map<String,Object>> list(Authentication auth){
   Guard.admin(auth);
   return db.queryForList("SELECT id,event_at AS eventAt,actor,event_type AS eventType,request_id AS requestId,object_ref AS objectRef,result,message FROM dg_audit ORDER BY id DESC LIMIT 200");
  }
 }
}