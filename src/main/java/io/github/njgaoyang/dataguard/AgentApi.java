package io.github.njgaoyang.dataguard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agents")
class AgentApi {
 private final JdbcTemplate db;private final Audit audit;
 AgentApi(JdbcTemplate db,Audit audit){this.db=db;this.audit=audit;}
 record Create(String name,String ownerId,String expiresAt){}
 record TableGrant(String sourceId,String tableName){}
 record ColumnGrant(String sourceId,String tableName,String columnName){}
 static String digest(String secret){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception ex){throw new IllegalStateException(ex);}
 }
 static String newToken(){
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
  return "dg_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 static boolean expiryValid(Object value){
  if(value instanceof java.time.LocalDateTime date)return date.isAfter(java.time.LocalDateTime.now());
  if(value instanceof java.sql.Timestamp date)return date.toInstant().isAfter(java.time.Instant.now());
  return false;
 }
 boolean admin(Authentication a){return a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN"));}
 @GetMapping List<Map<String,Object>> list(Authentication auth){
  String filter=admin(auth)?" WHERE a.deleted_at IS NULL":" WHERE owner.username=? AND a.deleted_at IS NULL";
  String q="""
   SELECT a.id,a.name,a.enabled,a.expires_at AS expiresAt,
     a.token_reset_required AS tokenResetRequired,
     a.created_at AS createdAt,owner.id AS ownerId,owner.display_name AS ownerName,
     (SELECT MAX(r.created_at) FROM dg_requests r WHERE r.agent_id=a.id) AS lastAccess,
     CASE WHEN a.enabled=0 THEN 1 ELSE 0 END AS canDelete
   FROM dg_agents a JOIN dg_users owner ON owner.id=a.owner_user_id
   """+filter+" ORDER BY a.created_at DESC LIMIT 300";
  return admin(auth)?db.queryForList(q):db.queryForList(q,auth.getName());
 }
 @PostMapping Map<String,Object> create(Authentication auth,@RequestBody Create in){
  if(in==null||in.expiresAt()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expiry required");
  java.time.LocalDateTime expires;
  try{expires=java.time.LocalDateTime.parse(in.expiresAt());}catch(java.time.format.DateTimeParseException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid expiry date");}
  if(!expires.isAfter(java.time.LocalDateTime.now())||expires.isAfter(java.time.LocalDateTime.of(2099,12,31,23,59,59)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expiry must be future and no later than 2099");
  String ownerId=in.ownerId()==null||in.ownerId().isBlank()?
   db.queryForObject("SELECT id FROM dg_users WHERE username=?",String.class,auth.getName()):Guard.id(in.ownerId());
  if(!admin(auth)&&!ownerId.equals(db.queryForObject("SELECT id FROM dg_users WHERE username=?",String.class,auth.getName())))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  Integer valid=db.queryForObject("SELECT COUNT(*) FROM dg_users WHERE id=? AND enabled=1 AND must_change_password=0",Integer.class,ownerId);
  if(valid==null||valid!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Owner not active");
  String id=UUID.randomUUID().toString(),token=newToken();
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,?)",
   id,ownerId,Guard.required(in.name(),120),digest(token),java.sql.Timestamp.valueOf(expires));
  audit.log(auth.getName(),"AGENT_CREATED",null,id,"SUCCESS","");
  return Map.of("id",id,"token",token);
 }
 void own(Authentication auth,String agent){
  List<String> owners=db.query("SELECT owner.username FROM dg_agents a JOIN dg_users owner ON owner.id=a.owner_user_id WHERE a.id=? AND a.deleted_at IS NULL",(rs,i)->rs.getString(1),Guard.id(agent));
  if(owners.size()!=1||(!admin(auth)&&!auth.getName().equals(owners.get(0))))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 }
 // Never hard-delete identities: requests, approvals, rules and audit remain historically referential.
 @DeleteMapping("/{id}")
 @org.springframework.transaction.annotation.Transactional
 Map<String,Object> deleteAgent(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  Map<String,Object> row=lockAvailable(safe);
  if(on(row.get("enabled")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Stop Agent before deletion");
  db.update("UPDATE dg_agents SET deleted_at=NOW(3),token_hash=?,token_reset_required=1 WHERE id=?",
   digest(newToken()),safe);
  // Existing policy/request history remains; revoke mutable grants and outstanding authorizations.
  db.update("UPDATE dg_requests SET status='DENIED',reason='Agent deleted',decided_at=NOW(3) WHERE agent_id=? AND status IN ('PENDING','APPROVED')",safe);
  db.update("DELETE FROM dg_agent_columns WHERE agent_id=?",safe);
  db.update("DELETE FROM dg_agent_tables WHERE agent_id=?",safe);
  db.update("UPDATE dg_rules SET enabled=0 WHERE agent_id=?",safe);
  audit.log(auth.getName(),"AGENT_DELETED",null,safe,"SUCCESS","Soft delete; history retained, grants removed");
  return Map.of("deleted",true);
 }
 private static boolean on(Object value){
  return value instanceof Number n ? n.intValue()!=0 : Boolean.TRUE.equals(value);
 }
 private Map<String,Object> lockAvailable(String id){
  List<Map<String,Object>> rows=db.queryForList("SELECT enabled,expires_at AS expiresAt,token_reset_required AS resetRequired FROM dg_agents WHERE id=? AND deleted_at IS NULL FOR UPDATE",id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found");
  return rows.get(0);
 }
 private void ensureStopped(String id){
  if(on(lockAvailable(id).get("enabled")))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Stop Agent before editing grants");
 }
 @PostMapping("/{id}/toggle")
 @org.springframework.transaction.annotation.Transactional
 Map<String,Object> toggleEnabled(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  Map<String,Object> row=lockAvailable(safe);
  if(on(row.get("enabled"))){
   db.update("UPDATE dg_agents SET enabled=0 WHERE id=?",safe);
   audit.log(auth.getName(),"AGENT_DISABLED",null,safe,"SUCCESS","");
   return Map.of("enabled",false);
  }
  // Migrated revoked credentials, and genuinely expired credentials, never resurrect an old token.
  boolean fresh=on(row.get("resetRequired"))||!expiryValid(row.get("expiresAt"));
  if(fresh){
   String token=newToken();
   db.update("UPDATE dg_agents SET enabled=1,token_hash=?,token_reset_required=0,expires_at=? WHERE id=?",
    digest(token),java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().plusYears(1)),safe);
   audit.log(auth.getName(),"AGENT_ENABLED",null,safe,"SUCCESS","Fresh Token issued; old secret invalid");
   return Map.of("enabled",true,"token",token);
  }
  db.update("UPDATE dg_agents SET enabled=1 WHERE id=?",safe);
  audit.log(auth.getName(),"AGENT_ENABLED",null,safe,"SUCCESS","");
  return Map.of("enabled",true);
 }
 // One-time secret on successful rotation; previous credential becomes invalid immediately.
 @org.springframework.transaction.annotation.Transactional
 @PostMapping("/{id}/rotate")
 Map<String,Object> rotate(Authentication auth,@PathVariable String id,@RequestBody Rotate request){
  own(auth,id);
  if(request==null||request.expiresAt()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expiry required");
  java.time.LocalDateTime expires;
  try{expires=java.time.LocalDateTime.parse(request.expiresAt());}catch(java.time.format.DateTimeParseException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid date");}
  if(!expires.isAfter(java.time.LocalDateTime.now())||expires.isAfter(java.time.LocalDateTime.of(2099,12,31,23,59,59)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid expiry");
  String safe=Guard.id(id);
  lockAvailable(safe);
  String token=newToken();
  int n=db.update("UPDATE dg_agents SET token_hash=?,expires_at=?,token_reset_required=0 WHERE id=? AND deleted_at IS NULL",
   digest(token),java.sql.Timestamp.valueOf(expires),safe);
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found");
  audit.log(auth.getName(),"AGENT_TOKEN_ROTATED",null,id,"SUCCESS","");
  return Map.of("token",token,"id",Guard.id(id));
 }
 record Rotate(String expiresAt){}

 // Safe connectivity precheck: returns effective grants and identity validity, never the secret.
 @GetMapping("/{id}/check")
 Map<String,Object> check(Authentication auth,@PathVariable String id){
  own(auth,id);String agent=Guard.id(id);
  Map<String,Object> status=db.queryForMap("""
   SELECT a.enabled,a.expires_at AS expiresAt,
    u.enabled AS ownerEnabled,
    (SELECT COUNT(*) FROM dg_agent_columns c JOIN dg_source_columns sc ON
      sc.source_id=c.source_id AND sc.table_name=c.table_name AND sc.column_name=c.column_name
      JOIN dg_source_tables st ON st.source_id=c.source_id AND st.table_name=c.table_name
      JOIN dg_sources ds ON ds.id=c.source_id
      WHERE c.agent_id=a.id AND sc.enabled=1 AND st.enabled=1 AND ds.enabled=1) AS effectiveColumns
   FROM dg_agents a JOIN dg_users u ON u.id=a.owner_user_id WHERE a.id=? AND a.deleted_at IS NULL
   """,agent);
  boolean active=Boolean.TRUE.equals(status.get("enabled"))&&
   Boolean.TRUE.equals(status.get("ownerEnabled"))&&
   expiryValid(status.get("expiresAt"));
  return Map.of("agentActive",active,"authorizedColumns",status.get("effectiveColumns"),
    "mcpEndpoint","/mcp","ready",active&&((Number)status.get("effectiveColumns")).intValue()>0);
 }
 @GetMapping("/{id}/grants") Map<String,Object> grants(Authentication auth,@PathVariable String id){
  own(auth,id);
  return Map.of(
   "tables",db.queryForList("SELECT source_id AS sourceId,table_name AS tableName FROM dg_agent_tables WHERE agent_id=? ORDER BY table_name",Guard.id(id)),
   "columns",db.queryForList("SELECT source_id AS sourceId,table_name AS tableName,column_name AS columnName FROM dg_agent_columns WHERE agent_id=? ORDER BY table_name,column_name",Guard.id(id)));
 }
 record GrantSelection(String sourceId,String tableName,List<String> columns){}
 record GrantUpdate(List<GrantSelection> tables,List<String> expectedColumns){}
 @GetMapping("/{id}/grant-options") List<Map<String,Object>> grantOptions(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String agent=Guard.id(id);
  if(db.queryForObject("SELECT COUNT(*) FROM dg_agents WHERE id=? AND deleted_at IS NULL",Integer.class,agent)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  List<Map<String,Object>> data=db.queryForList("""
   SELECT s.id AS sourceId,s.name AS sourceName,t.table_name AS tableName,c.column_name AS columnName,
    EXISTS(SELECT 1 FROM dg_agent_columns ac WHERE ac.agent_id=? AND ac.source_id=s.id AND ac.table_name=t.table_name AND ac.column_name=c.column_name) AS selected
   FROM dg_source_columns c JOIN dg_source_tables t ON c.source_id=t.source_id AND c.table_name=t.table_name
    JOIN dg_sources s ON s.id=t.source_id
   WHERE s.enabled=1 AND t.enabled=1 AND c.enabled=1 ORDER BY s.name,t.table_name,c.column_name LIMIT 2000
   """,agent);
  if(data.size()>=2000)throw new ResponseStatusException(HttpStatus.CONFLICT,"Too many available grant columns for bulk editing");
  Map<String,Map<String,Object>> result=new java.util.LinkedHashMap<>();
  for(var c:data){String key=c.get("sourceId")+"::"+c.get("tableName");var t=result.get(key);
   if(t==null){t=new java.util.LinkedHashMap<>();t.put("sourceId",c.get("sourceId"));t.put("tableName",c.get("tableName"));t.put("columns",new java.util.ArrayList<String>());t.put("selectedColumns",new java.util.ArrayList<String>());result.put(key,t);}
   ((List<String>)t.get("columns")).add(String.valueOf(c.get("columnName")));
   if(Boolean.TRUE.equals(c.get("selected"))||"1".equals(String.valueOf(c.get("selected"))))((List<String>)t.get("selectedColumns")).add(String.valueOf(c.get("columnName")));
  }
  return new java.util.ArrayList<>(result.values());
 }
 @PutMapping("/{id}/grants")
 @org.springframework.transaction.annotation.Transactional
 Map<String,Object> replaceGrants(Authentication auth,@PathVariable String id,@RequestBody GrantUpdate in){
  Guard.admin(auth);String agent=Guard.id(id);
  if(in==null||in.tables()==null||in.tables().size()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid grants");
  ensureStopped(agent);
  List<String> current=db.query("SELECT CONCAT(source_id,'::',table_name,'::',column_name) FROM dg_agent_columns WHERE agent_id=? ORDER BY source_id,table_name,column_name",(rs,i)->rs.getString(1),agent);
  if(in.expectedColumns()==null||!current.equals(in.expectedColumns().stream().sorted().toList()))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent grants changed; refresh before saving");
  var allowed=db.queryForList("""
   SELECT s.id AS sourceId,t.table_name AS tableName,c.column_name AS columnName
   FROM dg_source_columns c JOIN dg_source_tables t ON c.source_id=t.source_id AND c.table_name=t.table_name
   JOIN dg_sources s ON s.id=t.source_id WHERE s.enabled=1 AND t.enabled=1 AND c.enabled=1
   """);
  var allow=new java.util.HashSet<String>();for(var a:allowed)allow.add(a.get("sourceId")+"::"+a.get("tableName")+"::"+a.get("columnName"));
  var grants=new java.util.LinkedHashMap<String,java.util.List<String>>();
  for(var t:in.tables()){
   String source=Guard.id(t.sourceId()),table=Guard.identifier(t.tableName());
   if(t.columns()==null||t.columns().isEmpty()||t.columns().size()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid columns");
   String key=source+"::"+table;if(grants.containsKey(key))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate table");
   var cols=new java.util.ArrayList<String>();for(String raw:t.columns()){String col=Guard.identifier(raw);if(!allow.contains(key+"::"+col)||cols.contains(col))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Unapproved column");cols.add(col);}grants.put(key,cols);
  }
  db.update("DELETE FROM dg_agent_columns WHERE agent_id=?",agent);db.update("DELETE FROM dg_agent_tables WHERE agent_id=?",agent);
  for(var e:grants.entrySet()){String[] key=e.getKey().split("::",2);db.update("INSERT INTO dg_agent_tables(agent_id,source_id,table_name) VALUES(?,?,?)",agent,key[0],key[1]);for(String col:e.getValue())db.update("INSERT INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(?,?,?,?)",agent,key[0],key[1],col);}
  audit.log(auth.getName(),"AGENT_GRANTS_REPLACED",null,agent,"SUCCESS","tables="+grants.size());return Map.of("updated",true);
 }
 @GetMapping("/{id}/usage") Map<String,Object> usage(Authentication auth,@PathVariable String id){
  own(auth,id);String agent=Guard.id(id);
  Map<String,Object> stats=db.queryForMap("""
    SELECT COUNT(*) AS requests,
     COALESCE(SUM(status='SUCCESS'),0) AS completed,
     COALESCE(SUM(status IN ('DENIED','REJECTED')),0) AS denied,
     MAX(created_at) AS lastAccess
    FROM dg_requests WHERE agent_id=?
    """,agent);
  List<Map<String,Object>> recent=db.queryForList("""
    SELECT id,created_at AS createdAt,table_name AS tableName,status,
     requested_rows AS requestedRows,returned_rows AS returnedRows
    FROM dg_requests WHERE agent_id=? ORDER BY created_at DESC LIMIT 10
    """,agent);
  return Map.of("stats",stats,"recent",recent);
 }
 @org.springframework.transaction.annotation.Transactional
 @PostMapping("/{id}/tables") Map<String,Object> grantTable(Authentication auth,@PathVariable String id,@RequestBody TableGrant input){
  Guard.admin(auth);String agent=Guard.id(id),source=Guard.id(input.sourceId()),table=Guard.identifier(input.tableName());
  ensureStopped(agent);
  Integer allowed=db.queryForObject("""
   SELECT COUNT(*) FROM dg_source_tables w JOIN dg_sources s ON s.id=w.source_id
   WHERE w.source_id=? AND w.table_name=? AND w.enabled=1 AND s.enabled=1
   """,Integer.class,source,table);
  if(allowed!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source table is not allowlisted");
  db.update("INSERT IGNORE INTO dg_agent_tables(agent_id,source_id,table_name) VALUES(?,?,?)",agent,source,table);
  audit.log(auth.getName(),"AGENT_TABLE_GRANTED",null,agent+"/"+source+"/"+table,"SUCCESS","");
  return Map.of("updated",true);
 }
 @org.springframework.transaction.annotation.Transactional
 @PostMapping("/{id}/columns") Map<String,Object> grantColumn(Authentication auth,@PathVariable String id,@RequestBody ColumnGrant input){
  Guard.admin(auth);String agent=Guard.id(id),source=Guard.id(input.sourceId()),table=Guard.identifier(input.tableName()),column=Guard.identifier(input.columnName());
  ensureStopped(agent);
  Integer allowed=db.queryForObject("""
   SELECT COUNT(*) FROM dg_agent_tables t JOIN dg_source_tables w ON w.source_id=t.source_id AND w.table_name=t.table_name
   JOIN dg_source_columns c ON c.source_id=t.source_id AND c.table_name=t.table_name
   WHERE t.agent_id=? AND t.source_id=? AND t.table_name=? AND c.column_name=? AND c.enabled=1 AND w.enabled=1
   """,Integer.class,agent,source,table,column);
  if(allowed!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot grant an unapproved column");
  db.update("INSERT IGNORE INTO dg_agent_columns(agent_id,source_id,table_name,column_name) VALUES(?,?,?,?)",agent,source,table,column);
  audit.log(auth.getName(),"AGENT_COLUMN_GRANTED",null,agent+"/"+source+"/"+table+"/"+column,"SUCCESS","");
  return Map.of("updated",true);
 }
 @org.springframework.transaction.annotation.Transactional
 @DeleteMapping("/{id}/tables/{source}/{table}") Map<String,Object> revokeTable(Authentication auth,@PathVariable String id,@PathVariable String source,@PathVariable String table){
  Guard.admin(auth);ensureStopped(Guard.id(id));
  db.update("DELETE FROM dg_agent_columns WHERE agent_id=? AND source_id=? AND table_name=?",Guard.id(id),Guard.id(source),Guard.identifier(table));
  db.update("DELETE FROM dg_agent_tables WHERE agent_id=? AND source_id=? AND table_name=?",Guard.id(id),Guard.id(source),Guard.identifier(table));
  audit.log(auth.getName(),"AGENT_TABLE_REVOKED",null,id+"/"+table,"SUCCESS","");
  return Map.of("updated",true);
 }
 @org.springframework.transaction.annotation.Transactional
 @DeleteMapping("/{id}/columns/{source}/{table}/{column}") Map<String,Object> revokeColumn(Authentication auth,@PathVariable String id,@PathVariable String source,@PathVariable String table,@PathVariable String column){
  Guard.admin(auth);ensureStopped(Guard.id(id));
  db.update("DELETE FROM dg_agent_columns WHERE agent_id=? AND source_id=? AND table_name=? AND column_name=?",Guard.id(id),Guard.id(source),Guard.identifier(table),Guard.identifier(column));
  audit.log(auth.getName(),"AGENT_COLUMN_REVOKED",null,id+"/"+table+"/"+column,"SUCCESS","");
  return Map.of("updated",true);
 }
 Map<String,Object> resolve(String raw){
  if(raw==null||!raw.matches("dg_[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Agent credential");
  List<Map<String,Object>> rows=db.queryForList("""
    SELECT a.id,a.name,a.owner_user_id AS ownerId,u.username AS ownerUsername
    FROM dg_agents a JOIN dg_users u ON u.id=a.owner_user_id
    WHERE a.token_hash=? AND a.enabled=1 AND a.deleted_at IS NULL AND a.token_reset_required=0
      AND a.expires_at>NOW(3) AND u.enabled=1 AND u.must_change_password=0
    """,digest(raw));
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Agent credential");
  return rows.get(0);
 }
}