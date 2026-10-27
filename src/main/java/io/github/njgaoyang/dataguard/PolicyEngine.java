package io.github.njgaoyang.dataguard;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class PolicyEngine {
 private final JdbcTemplate db; private final Audit audit;
 PolicyEngine(JdbcTemplate db,Audit audit){this.db=db;this.audit=audit;}
 record Verdict(String action,String id) {}
 static final class Pending extends RuntimeException {
  final String requestId;
  Pending(String id){super("Human approval required");requestId=id;}
 }
 static String fingerprint(String agent,String source,String table,List<String> columns,int limit,String shape){
  try{
   MessageDigest h=MessageDigest.getInstance("SHA-256");
   for(String part:List.of(agent,source,table,String.join(",",new TreeSet<>(columns)),
      Integer.toString(limit),Objects.toString(shape,""))){
    byte[] bytes=part.getBytes(StandardCharsets.UTF_8);
    h.update(ByteBuffer.allocate(4).putInt(bytes.length).array());h.update(bytes);
   }
   return HexFormat.of().formatHex(h.digest());
  }catch(Exception ex){throw new IllegalStateException(ex);}
 }
 @Transactional
 public Verdict begin(Map<String,Object> agent,String source,String table,List<String> columns,int count,String shape){
  String agentId=(String)agent.get("id"),ownerId=(String)agent.get("ownerId");
  // Agent row serializes identical concurrent approvals. No possibility of double consumption.
  List<String> locked=db.query("SELECT id FROM dg_agents WHERE id=? AND enabled=1 AND revoked_at IS NULL AND expires_at>NOW(3) FOR UPDATE",(rs,i)->rs.getString(1),agentId);
  if(locked.size()!=1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Agent is unavailable");
  List<Map<String,Object>> rules=db.queryForList("""
   SELECT id,action,approver_id AS approverId,column_name AS columnName,revision
   FROM dg_rules WHERE enabled=1 AND (source_id IS NULL OR source_id=?)
      AND (agent_id IS NULL OR agent_id=?) AND table_name IN ('*',?)
      AND min_rows<=? AND max_rows>=?
   ORDER BY CASE action WHEN 'DENY' THEN 0 WHEN 'APPROVAL' THEN 1 ELSE 2 END,priority,id
   """,source,agentId,table,count,count);
  Map<String,Object> rule=rules.stream().filter(r->r.get("columnName")==null||
    columns.contains(String.valueOf(r.get("columnName")))).findFirst().orElse(null);
  String action=rule==null?"DENY":String.valueOf(rule.get("action"));
  String approver=rule==null?null:(String)rule.get("approverId");
  if("APPROVAL".equals(action)){
   Integer enabled=db.queryForObject("SELECT COUNT(*) FROM dg_users WHERE id=? AND enabled=1 AND role_code IN ('ADMIN','APPROVER') AND must_change_password=0 AND id<>?",Integer.class,approver,ownerId);
   if(enabled!=1)action="DENY";
  }
  String hash=fingerprint(agentId,source,table,columns,count,shape);
  if("APPROVAL".equals(action)){
   List<Map<String,Object>> existing=db.queryForList("""
    SELECT id,status FROM dg_requests WHERE agent_id=? AND query_hash=?
      AND rule_id=? AND rule_revision=?
      AND status IN ('PENDING','APPROVED') AND expires_at>NOW(3)
    ORDER BY created_at DESC LIMIT 1 FOR UPDATE
    """,agentId,hash,rule.get("id"),rule.get("revision"));
   if(!existing.isEmpty()){
    String id=String.valueOf(existing.get(0).get("id"));
    if("APPROVED".equals(existing.get(0).get("status"))){
     int changed=db.update("UPDATE dg_requests SET status='EXECUTING' WHERE id=? AND status='APPROVED'",id);
     if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval already used");
     return new Verdict("ALLOW",id);
    }
    return new Verdict("APPROVAL",id);
   }
  }
  String id=UUID.randomUUID().toString();
  String status=switch(action){case "ALLOW"->"EXECUTING";case "APPROVAL"->"PENDING";default->"DENIED";};
  db.update("""
   INSERT INTO dg_requests(id,agent_id,owner_id,source_id,table_name,columns_csv,requested_rows,
      query_hash,rule_id,rule_revision,action,status,approver_id,expires_at,reason)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 24 HOUR),?)
   """,id,agentId,ownerId,source,table,String.join(",",columns),count,hash,
     rule==null?null:rule.get("id"),rule==null?null:rule.get("revision"),action,status,approver,
     "DENY".equals(action)?"Unmatched or prohibited access rule":null);
  if("DENY".equals(action))audit.log((String)agent.get("name"),"AGENT_READ",id,source+"/"+table,"DENIED","Denied by dynamic access rules");
  return new Verdict(action,id);
 }
 /**
  * Re-evaluate after JDBC I/O: newly published DENY, revoked rule, or a revised rule
  * must prevent returning buffered results, including previously approved queries.
  */
 void verifyStillAllowed(Verdict verdict,Map<String,Object> agent,String source,String table,
                         List<String> accessedColumns,int requestedRows){
  List<Map<String,Object>> current=db.queryForList("""
   SELECT r.id,r.action,r.revision,r.column_name AS columnName FROM dg_rules r
   WHERE r.enabled=1 AND (r.source_id IS NULL OR r.source_id=?)
     AND (r.agent_id IS NULL OR r.agent_id=?) AND r.table_name IN ('*',?)
     AND r.min_rows<=? AND r.max_rows>=?
   ORDER BY CASE r.action WHEN 'DENY' THEN 0 WHEN 'APPROVAL' THEN 1 ELSE 2 END,r.priority,r.id
   """,source,agent.get("id"),table,requestedRows,requestedRows);
  Map<String,Object> winner=current.stream().filter(r->r.get("columnName")==null||
    accessedColumns.contains(String.valueOf(r.get("columnName")))).findFirst().orElse(null);
  List<Map<String,Object>> request=db.queryForList(
   "SELECT rule_id AS ruleId,rule_revision AS ruleRevision,action,status FROM dg_requests WHERE id=?",verdict.id());
  if(winner==null||request.size()!=1||!"EXECUTING".equals(request.get(0).get("status"))||
     !Objects.equals(winner.get("id"),request.get(0).get("ruleId"))||
     !Objects.equals(((Number)winner.get("revision")).intValue(),((Number)request.get(0).get("ruleRevision")).intValue())||
     !Objects.equals(winner.get("action"),request.get(0).get("action"))||
     "DENY".equals(winner.get("action")))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Access policy changed while reading");
 }
 @Transactional
 public void complete(Verdict verdict,int rows,long time,String actor,String resource){
  int updated=db.update(
   "UPDATE dg_requests SET status='SUCCESS',returned_rows=?,duration_ms=?,finished_at=NOW(3) WHERE id=? AND status='EXECUTING'",
    rows,time,verdict.id());
  if(updated!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Request cannot be completed");
  audit.log(actor,"AGENT_READ",verdict.id(),resource,"SUCCESS","rows="+rows+" durationMs="+time);
 }
 void fail(Verdict verdict){
  if(verdict!=null&&"ALLOW".equals(verdict.action()))
   db.update("UPDATE dg_requests SET status='FAILED',finished_at=NOW(3) WHERE id=? AND status='EXECUTING'",verdict.id());
 }
 Map<String,Object> status(Map<String,Object> agent,String id){
  List<Map<String,Object>> rows=db.queryForList("""
   SELECT id,CASE WHEN status IN ('PENDING','APPROVED') AND expires_at<=NOW(3)
       THEN 'EXPIRED' ELSE status END AS status,
     requested_rows AS requestedRows,returned_rows AS returnedRows,
     created_at AS createdAt,expires_at AS expiresAt
   FROM dg_requests WHERE id=? AND agent_id=?
   """,Guard.id(id),agent.get("id"));
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  return rows.get(0);
 }
}
@RestController
@RequestMapping("/api")
class ControlApi {
 private final JdbcTemplate db;private final Audit audit;private final PolicyEngine policy;
 ControlApi(JdbcTemplate db,Audit audit,PolicyEngine policy){this.db=db;this.audit=audit;this.policy=policy;}
 record RuleInput(String name,String sourceId,String agentId,String tableName,String columnName,
   Integer minRows,Integer maxRows,String action,String approverId,Integer priority){}
 record Decision(String reason){}
 @GetMapping("/rules") List<Map<String,Object>> rules(Authentication auth){
  Guard.admin(auth);
  return db.queryForList("""
   SELECT r.id,r.name,r.source_id AS sourceId,s.name AS sourceName,r.agent_id AS agentId,
     a.name AS agentName,r.table_name AS tableName,r.column_name AS columnName,
     r.min_rows AS minRows,r.max_rows AS maxRows,r.action,r.priority,r.revision,r.enabled,
     u.display_name AS approverName,r.approver_id AS approverId
   FROM dg_rules r LEFT JOIN dg_sources s ON s.id=r.source_id
    LEFT JOIN dg_agents a ON a.id=r.agent_id LEFT JOIN dg_users u ON u.id=r.approver_id
   ORDER BY r.priority,r.created_at DESC LIMIT 300
   """);
 }
 @PostMapping("/rules") Map<String,Object> createRule(Authentication auth,@RequestBody RuleInput in){
  Guard.admin(auth);
  if(in==null||in.minRows()==null||in.maxRows()==null||in.priority()==null||in.minRows()<1||
   in.maxRows()>db.queryForObject("SELECT COALESCE(MAX(setting_value),200) FROM dg_security_settings WHERE setting_key='max_rows_per_query'",Integer.class)||in.maxRows()<in.minRows()||in.priority()<1||in.priority()>10000||
   !Set.of("ALLOW","APPROVAL","DENY").contains(in.action()))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid rule");
  String table="*".equals(in.tableName())?"*":Guard.identifier(in.tableName());
  String source=Guard.nullableId(in.sourceId()),agent=Guard.nullableId(in.agentId());
  String reviewer=Guard.nullableId(in.approverId());
  if("APPROVAL".equals(in.action())){
   if(reviewer==null||db.queryForObject("SELECT COUNT(*) FROM dg_users WHERE id=? AND enabled=1 AND must_change_password=0 AND role_code IN ('APPROVER','ADMIN')",Integer.class,reviewer)!=1)
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Active internal approver required");
  }else if(reviewer!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reviewer only valid for approval rules");
  String column=in.columnName()==null||in.columnName().isBlank()?null:Guard.identifier(in.columnName());
  String id=UUID.randomUUID().toString();
  db.update("""
    INSERT INTO dg_rules(id,name,source_id,agent_id,table_name,column_name,min_rows,max_rows,action,approver_id,priority)
    VALUES(?,?,?,?,?,?,?,?,?,?,?)
    """,id,Guard.required(in.name(),120),source,agent,table,column,in.minRows(),in.maxRows(),in.action(),reviewer,in.priority());
  audit.log(auth.getName(),"RULE_CREATED",null,id,"SUCCESS",in.action());
  return Map.of("id",id);
 }
 @PutMapping("/rules/{id}")
 @Transactional
 public Map<String,Object> updateRule(Authentication auth,@PathVariable String id,@RequestBody RuleInput in){
  Guard.admin(auth);
  if(in==null||in.minRows()==null||in.maxRows()==null||in.priority()==null||in.minRows()<1||
     in.maxRows()>db.queryForObject("SELECT COALESCE(MAX(setting_value),200) FROM dg_security_settings WHERE setting_key='max_rows_per_query'",Integer.class)||in.maxRows()<in.minRows()||in.priority()<1||in.priority()>10000||
     !Set.of("ALLOW","APPROVAL","DENY").contains(in.action()))
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid rule");
  String safe=Guard.id(id),table="*".equals(in.tableName())?"*":Guard.identifier(in.tableName());
  String source=Guard.nullableId(in.sourceId()),agent=Guard.nullableId(in.agentId());
  String approver=Guard.nullableId(in.approverId());
  if("APPROVAL".equals(in.action())){
   if(approver==null||db.queryForObject("SELECT COUNT(*) FROM dg_users WHERE id=? AND enabled=1 AND must_change_password=0 AND role_code IN ('APPROVER','ADMIN')",Integer.class,approver)!=1)
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Active internal approver required");
  }else if(approver!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reviewer only allowed on approval rules");
  String column=in.columnName()==null||in.columnName().isBlank()?null:Guard.identifier(in.columnName());
  int changed=db.update("""
    UPDATE dg_rules SET name=?,source_id=?,agent_id=?,table_name=?,column_name=?,
      min_rows=?,max_rows=?,action=?,approver_id=?,priority=?,revision=revision+1 WHERE id=?
    """,Guard.required(in.name(),120),source,agent,table,column,in.minRows(),in.maxRows(),
    in.action(),approver,in.priority(),safe);
  if(changed!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Rule unavailable");
  db.update("""
    UPDATE dg_requests SET status='REJECTED',reason='Access rule was revised',decided_at=NOW(3)
    WHERE rule_id=? AND status IN ('PENDING','APPROVED')
    """,safe);
  audit.log(auth.getName(),"RULE_UPDATED",null,safe,"SUCCESS","");
  return Map.of("updated",true);
 }
 @DeleteMapping("/rules/{id}")
 @Transactional
 public Map<String,Object> deleteRule(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  var existingRule=db.queryForList("SELECT id,enabled FROM dg_rules WHERE id=? FOR UPDATE",safe);
  if(existingRule.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  Object ruleEnabled=existingRule.get(0).get("enabled");
  if(ruleEnabled instanceof Number ? ((Number)ruleEnabled).intValue()!=0 : Boolean.TRUE.equals(ruleEnabled))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Disable rule before deletion");
  Long refs=db.queryForObject("SELECT COUNT(*) FROM dg_requests WHERE rule_id=?",Long.class,safe);
  if(refs!=null&&refs>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Rule has historical requests; disable instead");
  db.update("DELETE FROM dg_rules WHERE id=?",safe);
  audit.log(auth.getName(),"RULE_DELETED",null,safe,"SUCCESS","No historical references");return Map.of("deleted",true);
 }
 @PostMapping("/rules/{id}/toggle")
 @Transactional
 public Map<String,Object> toggle(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  if(db.update("UPDATE dg_rules SET enabled=NOT enabled,revision=revision+1 WHERE id=?",safe)!=1)
   throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  db.update("""
    UPDATE dg_requests SET status='REJECTED',reason='Access rule status changed',decided_at=NOW(3)
    WHERE rule_id=? AND status IN ('PENDING','APPROVED')
    """,safe);
  audit.log(auth.getName(),"RULE_TOGGLED",null,safe,"SUCCESS","");
  return Map.of("updated",true);
 }
 @GetMapping("/requests") List<Map<String,Object>> requests(Authentication auth,@RequestParam(defaultValue="all") String scope){
  if(!Set.of("all","pending","mine","handled").contains(scope))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
  String conditions=" WHERE 1=1";
  List<Object> params=new ArrayList<>();
  if("mine".equals(scope)){conditions+=" AND owner.username=?";params.add(auth.getName());}
  else if("pending".equals(scope)){conditions+=" AND reviewer.username=? AND q.status='PENDING' AND q.expires_at>NOW(3)";params.add(auth.getName());}
  else if("handled".equals(scope)){conditions+=" AND reviewer.username=? AND q.status<>'PENDING'";params.add(auth.getName());}
  else if(!admin){conditions+=" AND owner.username=?";params.add(auth.getName());}
  String sql="""
   SELECT q.id,q.created_at AS createdAt,q.expires_at AS expiresAt,
     q.finished_at AS finishedAt,q.table_name AS tableName,q.columns_csv AS columnsRequested,
     q.requested_rows AS requestedRows,q.returned_rows AS returnedRows,q.duration_ms AS durationMs,
     q.status,q.action,q.reason,q.rule_id AS ruleId,q.source_id AS sourceId,s.name AS sourceName,
     a.name AS agentName,owner.username AS ownerUsername,owner.display_name AS requesterName,
     reviewer.display_name AS approverName,reviewer.username AS approverUsername,access_rule.name AS ruleName
   FROM dg_requests q JOIN dg_agents a ON a.id=q.agent_id
   JOIN dg_sources s ON s.id=q.source_id JOIN dg_users owner ON owner.id=q.owner_id
   LEFT JOIN dg_users reviewer ON reviewer.id=q.approver_id
   LEFT JOIN dg_rules access_rule ON access_rule.id=q.rule_id
   """+conditions+" ORDER BY q.created_at DESC LIMIT 300";
  return db.queryForList(sql,params.toArray());
 }

 // Server-side pagination: access records are not limited to the newest 300 rows.
 // Both the count and page query apply exactly the same authorization predicate.
 @GetMapping("/requests/page")
 Map<String,Object> requestPage(Authentication auth,
   @RequestParam(defaultValue="1") int page,
   @RequestParam(defaultValue="") String status,
   @RequestParam(defaultValue="") String agent,
   @RequestParam(defaultValue="") String source,
   @RequestParam(defaultValue="") String search,
   @RequestParam(defaultValue="") String from,
   @RequestParam(defaultValue="") String to){
  if(page<1||page>100000||search.length()>120||agent.length()>160||source.length()>160)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination or filters");
  if(!status.isBlank()&&!Set.of("SUCCESS","PENDING","APPROVED","DENIED","REJECTED","DENIED_OR_REJECTED","FAILED","EXECUTING","EXPIRED").contains(status))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid status");
  String conditions=" WHERE 1=1";
  List<Object> params=new ArrayList<>();
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
  if(!admin){conditions+=" AND (owner.username=? OR reviewer.username=?)";params.add(auth.getName());params.add(auth.getName());}
  if("DENIED_OR_REJECTED".equals(status))conditions+=" AND q.status IN ('DENIED','REJECTED')";
  else if(!status.isBlank()){conditions+=" AND q.status=?";params.add(status);}
  if(!agent.isBlank()){conditions+=" AND a.name=?";params.add(agent);}
  if(!source.isBlank()){conditions+=" AND s.name=?";params.add(source);}
  if(!search.isBlank()){
   conditions+=" AND (q.id LIKE ? OR q.table_name LIKE ? OR a.name LIKE ? OR s.name LIKE ? OR owner.display_name LIKE ?)";
   String q="%"+search.replace("%","\\%").replace("_","\\_")+"%";
   for(int i=0;i<5;i++)params.add(q);
  }
  try{
   if(!from.isBlank()){
    conditions+=" AND q.created_at>=?";
    params.add(java.sql.Date.valueOf(java.time.LocalDate.parse(from)));
   }
   if(!to.isBlank()){
    conditions+=" AND q.created_at<DATE_ADD(?,INTERVAL 1 DAY)";
    params.add(java.sql.Date.valueOf(java.time.LocalDate.parse(to)));
   }
  }catch(java.time.format.DateTimeParseException ex){
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid date range");
  }
  String joins="""
   FROM dg_requests q JOIN dg_agents a ON a.id=q.agent_id
   JOIN dg_sources s ON s.id=q.source_id JOIN dg_users owner ON owner.id=q.owner_id
   LEFT JOIN dg_users reviewer ON reviewer.id=q.approver_id
   """;
  long total=db.queryForObject("SELECT COUNT(*) "+joins+conditions,Long.class,params.toArray());
  String projection="""
   SELECT q.id,q.created_at AS createdAt,q.expires_at AS expiresAt,
    q.finished_at AS finishedAt,q.table_name AS tableName,q.columns_csv AS columnsRequested,
    q.requested_rows AS requestedRows,q.returned_rows AS returnedRows,q.duration_ms AS durationMs,
    q.status,q.action,q.reason,q.rule_id AS ruleId,q.source_id AS sourceId,s.name AS sourceName,
    a.name AS agentName,owner.username AS ownerUsername,owner.display_name AS requesterName,
    reviewer.display_name AS approverName,reviewer.username AS approverUsername,access_rule.name AS ruleName
    FROM dg_requests q JOIN dg_agents a ON a.id=q.agent_id
    JOIN dg_sources s ON s.id=q.source_id JOIN dg_users owner ON owner.id=q.owner_id
    LEFT JOIN dg_users reviewer ON reviewer.id=q.approver_id
    LEFT JOIN dg_rules access_rule ON access_rule.id=q.rule_id
   
   """;
  List<Object> pageParams=new ArrayList<>(params);
  pageParams.add(10);
  pageParams.add((page-1)*10);
  List<Map<String,Object>> items=db.queryForList(projection+conditions+
   " ORDER BY q.created_at DESC,q.id DESC LIMIT ? OFFSET ?",pageParams.toArray());
  return Map.of("items",items,"total",total,"page",page,"pageSize",10);
 }

 // Resolve a single request by UUID. Never return another user's request.
 @GetMapping("/requests/{id}")
 Map<String,Object> requestDetail(Authentication auth,@PathVariable String id){
  String safe=Guard.id(id);
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
  String condition=admin?" WHERE q.id=?":" WHERE q.id=? AND (owner.username=? OR reviewer.username=?)";
  Object[] args=admin?new Object[]{safe}:new Object[]{safe,auth.getName(),auth.getName()};
  List<Map<String,Object>> rows=db.queryForList("""
   SELECT q.id,q.created_at AS createdAt,q.expires_at AS expiresAt,
    q.finished_at AS finishedAt,q.table_name AS tableName,q.columns_csv AS columnsRequested,
    q.requested_rows AS requestedRows,q.returned_rows AS returnedRows,q.duration_ms AS durationMs,
    q.status,q.action,q.reason,q.rule_id AS ruleId,q.source_id AS sourceId,s.name AS sourceName,
    a.name AS agentName,owner.username AS ownerUsername,owner.display_name AS requesterName,
    reviewer.display_name AS approverName,reviewer.username AS approverUsername,access_rule.name AS ruleName
    FROM dg_requests q JOIN dg_agents a ON a.id=q.agent_id
    JOIN dg_sources s ON s.id=q.source_id JOIN dg_users owner ON owner.id=q.owner_id
    LEFT JOIN dg_users reviewer ON reviewer.id=q.approver_id
    LEFT JOIN dg_rules access_rule ON access_rule.id=q.rule_id
   
   """+condition,args);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  return rows.get(0);
 }
 @PostMapping("/requests/{id}/{action}")
 @Transactional
 public Map<String,Object> decide(Authentication auth,@PathVariable String id,@PathVariable String action,@RequestBody Decision in){
  Guard.reviewer(auth);
  if(!Set.of("approve","reject").contains(action)||in==null||in.reason()==null||in.reason().isBlank()||in.reason().length()>512)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reason required");
  String safe=Guard.id(id);
  List<Map<String,Object>> rows=db.queryForList("""
   SELECT q.status,q.owner_id AS ownerId,q.approver_id AS approverId,q.expires_at AS expiresAt,
     u.id AS reviewerId FROM dg_requests q JOIN dg_users u ON u.username=?
   WHERE q.id=? FOR UPDATE
   """,auth.getName(),safe);
  if(rows.size()!=1||!"PENDING".equals(rows.get(0).get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Request unavailable");
  Map<String,Object> req=rows.get(0);
  if((!auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"))&&!req.get("approverId").equals(req.get("reviewerId")))||req.get("ownerId").equals(req.get("reviewerId")))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only assigned independent reviewer may decide");
  Integer stillValid=db.queryForObject(
    "SELECT COUNT(*) FROM dg_requests WHERE id=? AND expires_at>NOW(3)",Integer.class,safe);
  if(stillValid==null||stillValid!=1)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval expired");
  String result=action.equals("approve")?"APPROVED":"REJECTED";
  db.update("UPDATE dg_requests SET status=?,reason=?,decided_at=NOW(3) WHERE id=? AND status='PENDING'",result,in.reason().strip(),safe);
  audit.log(auth.getName(),"REQUEST_"+result,safe,"approval","SUCCESS","");
  return Map.of("status",result);
 }
 @GetMapping("/dashboard") Map<String,Object> dashboard(Authentication auth){
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
  String condition=admin?"":" AND owner.username=?";
  Object[] args=admin?new Object[]{}:new Object[]{auth.getName()};
  Map<String,Object> summary=db.queryForMap("""
    SELECT COUNT(*) AS requests,COALESCE(SUM(q.status='PENDING' AND q.expires_at>NOW(3)),0) AS pending,
      COALESCE(SUM(q.status IN ('DENIED','REJECTED')),0) AS denied,
      COALESCE(SUM(q.status='SUCCESS'),0) AS success
    FROM dg_requests q JOIN dg_users owner ON owner.id=q.owner_id
    WHERE q.created_at>=DATE_SUB(NOW(3),INTERVAL 24 HOUR)
    """+condition,args);
  List<Map<String,Object>> recent=db.queryForList("""
    SELECT q.id,q.created_at AS createdAt,q.status,q.table_name AS tableName,
      q.requested_rows AS requestedRows,a.name AS agentName
    FROM dg_requests q JOIN dg_agents a ON a.id=q.agent_id
    JOIN dg_users owner ON owner.id=q.owner_id WHERE 1=1
    """+condition+" ORDER BY q.created_at DESC LIMIT 8",args);
  return Map.of("stats",summary,"recent",recent);
 }
}