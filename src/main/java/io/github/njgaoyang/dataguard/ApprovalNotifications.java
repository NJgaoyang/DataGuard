package io.github.njgaoyang.dataguard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@RestController
@RequestMapping("/api/notifications")
class ApprovalNotifications {
 private final JdbcTemplate db;
 ApprovalNotifications(JdbcTemplate db){this.db=db;}

 @Scheduled(fixedDelay=60_000,initialDelay=12_000)
 void publish(){
  // Database idempotency (username, request_id, event_type); never send business query data.
  db.update("""
    INSERT IGNORE INTO dg_notifications(username,request_id,event_type,content)
    SELECT u.username,q.id,'PENDING',CONCAT('待读取审批：',q.table_name)
    FROM dg_requests q JOIN dg_users u ON u.id=q.approver_id
    WHERE q.status='PENDING' AND q.expires_at>NOW(3) AND u.enabled=1
    """);
  db.update("""
    INSERT IGNORE INTO dg_notifications(username,request_id,event_type,content)
    SELECT u.username,q.id,'REMINDER',CONCAT('审批即将到期：',q.table_name)
    FROM dg_requests q JOIN dg_users u ON u.id=q.approver_id
    WHERE q.status='PENDING' AND q.expires_at>NOW(3)
      AND q.expires_at<=DATE_ADD(NOW(3),INTERVAL 2 HOUR) AND u.enabled=1
    """);
 }
 @GetMapping
 Map<String,Object> list(Authentication auth){
  if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  String username=auth.getName();
  int unread=db.queryForObject("SELECT COUNT(*) FROM dg_notifications WHERE username=? AND read_at IS NULL",Integer.class,username);
  List<Map<String,Object>> notices=db.queryForList("""
   SELECT n.id,n.request_id AS requestId,n.event_type AS eventType,n.content,
    n.created_at AS createdAt,n.read_at AS readAt,q.status AS requestStatus
   FROM dg_notifications n JOIN dg_requests q ON q.id=n.request_id
   WHERE n.username=? ORDER BY n.id DESC LIMIT 40
   """,username);
  return Map.of("unread",unread,"items",notices);
 }
 @PostMapping("/{id}/read")
 Map<String,Object> read(Authentication auth,@PathVariable long id){
  if(id<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  int changed=db.update("UPDATE dg_notifications SET read_at=NOW(3) WHERE id=? AND username=? AND read_at IS NULL",id,auth.getName());
  return Map.of("updated",changed==1);
 }
}

@RestController
@RequestMapping("/api/ops")
class OperationsApi {
 private final JdbcTemplate db;
 OperationsApi(JdbcTemplate db){this.db=db;}
 @GetMapping("/overview")
 Map<String,Object> overview(Authentication auth){
  Guard.admin(auth);
  Map<String,Object> info=new LinkedHashMap<>();
  info.put("totalAgents",db.queryForObject("SELECT COUNT(*) FROM dg_agents WHERE enabled=1 AND deleted_at IS NULL AND expires_at>NOW(3)",Integer.class));
  info.put("totalSources",db.queryForObject("SELECT COUNT(*) FROM dg_sources WHERE enabled=1",Integer.class));
  info.put("pendingApprovals",db.queryForObject("SELECT COUNT(*) FROM dg_requests WHERE status='PENDING' AND expires_at>NOW(3)",Integer.class));
  info.put("readsLast24h",db.queryForObject("SELECT COUNT(*) FROM dg_requests WHERE created_at>=DATE_SUB(NOW(3),INTERVAL 1 DAY)",Integer.class));
  info.put("failedLast24h",db.queryForObject("SELECT COUNT(*) FROM dg_requests WHERE status IN ('FAILED','DENIED','REJECTED') AND created_at>=DATE_SUB(NOW(3),INTERVAL 1 DAY)",Integer.class));
  info.put("mcpErrorsLast24h",db.queryForObject("SELECT COUNT(*) FROM dg_audit WHERE event_type IN ('MCP_ERROR','MCP_DENIED') AND event_at>=DATE_SUB(NOW(3),INTERVAL 1 DAY)",Integer.class));
  info.put("quotaRejectedLast24h",db.queryForObject("SELECT COUNT(*) FROM dg_audit WHERE event_type='MCP_QUOTA' AND event_at>=DATE_SUB(NOW(3),INTERVAL 1 DAY)",Integer.class));
  info.put("loginLockedAccounts",db.queryForObject("SELECT COUNT(*) FROM dg_login_lock WHERE locked_until>NOW(3)",Integer.class));
  return info;
 }
}