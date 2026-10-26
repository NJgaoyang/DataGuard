package io.github.njgaoyang.dataguard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Service
@EnableScheduling
class SecurityControls {
 private final JdbcTemplate db;
 int setting(String key,int fallback){
  List<Integer> values=db.query("SELECT setting_value FROM dg_security_settings WHERE setting_key=?",(rs,i)->rs.getInt(1),key);
  return values.isEmpty()?fallback:values.get(0);
 }

 SecurityControls(JdbcTemplate db){this.db=db;}

 // Per username to prevent password guessing while avoiding shared reverse-proxy-IP lockouts.
 boolean locked(String username){
  if(username==null||!username.matches("[A-Za-z0-9_.-]{3,64}"))return false;
  Integer n=db.queryForObject("SELECT COUNT(*) FROM dg_login_lock WHERE username=? AND locked_until>NOW(3)",Integer.class,username);
  return n!=null&&n>0;
 }
 @Transactional
 public void failedLogin(String username){
  if(username==null||!username.matches("[A-Za-z0-9_.-]{3,64}"))return;
  db.update("INSERT IGNORE INTO dg_login_lock(username,failures,first_failed_at) VALUES(?,0,NOW(3))",username);
  db.update("""
   UPDATE dg_login_lock SET
    locked_until=CASE WHEN locked_until>NOW(3) THEN locked_until
      WHEN first_failed_at<DATE_SUB(NOW(3),INTERVAL 15 MINUTE) THEN NULL
      WHEN failures>=7 THEN DATE_ADD(NOW(3),INTERVAL 15 MINUTE)
      ELSE locked_until END,
    failures=CASE WHEN first_failed_at<DATE_SUB(NOW(3),INTERVAL 15 MINUTE) THEN 1 ELSE failures+1 END,
    first_failed_at=CASE WHEN first_failed_at<DATE_SUB(NOW(3),INTERVAL 15 MINUTE) THEN NOW(3) ELSE first_failed_at END
   WHERE username=?
   """,username);
 }
 void successfulLogin(String username){
  if(username!=null)db.update("DELETE FROM dg_login_lock WHERE username=?",username);
 }

 // One durable shared-DB quota even across multiple Java processes.
 // All authenticated MCP JSON-RPC calls count; read requests also consume projected row budget.
 @Transactional
 public void chargeAgent(String agentId,int requestedRows){
  String id=Guard.id(agentId);
  if(requestedRows<0||requestedRows>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  // One atomic UPSERT instead of INSERT IGNORE + UPDATE: the two-statement pattern
  // deadlocks under concurrent first-use of the same (agent, minute) unique key.
  // In this REQUIRED transaction, exceeding the quota throws and rolls back
  // the increment, so accepted counters never exceed either limit.
  db.update("""
    INSERT INTO dg_agent_minute_usage(agent_id,minute_bucket,calls,requested_rows)
    VALUES(?,DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:00'),1,?)
    ON DUPLICATE KEY UPDATE
      calls=calls+1,requested_rows=requested_rows+VALUES(requested_rows)
    """,id,requestedRows);
  Map<String,Object> current=db.queryForMap("""
    SELECT calls,requested_rows AS requestedRows
    FROM dg_agent_minute_usage
    WHERE agent_id=? AND minute_bucket=DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:00')
    """,id);
  if(((Number)current.get("calls")).intValue()>setting("agent_calls_per_minute",60)||
      ((Number)current.get("requestedRows")).intValue()>setting("agent_rows_per_minute",3000))
   throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
     "Agent quota exceeded");
 }
 @Scheduled(fixedDelay=3_600_000,initialDelay=3_600_000)
 void purgeOldCounters(){
  db.update("DELETE FROM dg_agent_minute_usage WHERE minute_bucket<DATE_SUB(NOW(),INTERVAL 2 DAY)");
  db.update("DELETE FROM dg_login_lock WHERE (locked_until IS NULL OR locked_until<NOW(3)) AND first_failed_at<DATE_SUB(NOW(),INTERVAL 2 DAY)");
 }
}

class LoginLockFilter extends OncePerRequestFilter {
 private final SecurityControls controls;
 LoginLockFilter(SecurityControls controls){this.controls=controls;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
    throws ServletException,IOException {
  if("POST".equals(req.getMethod()) && "/login".equals(req.getRequestURI())
      &&controls.locked(req.getParameter("username"))){
    res.sendError(429,"Account temporarily locked. Try again after 15 minutes");return;
  }
  chain.doFilter(req,res);
 }
}
