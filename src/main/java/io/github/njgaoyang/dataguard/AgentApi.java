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
  String filter=admin(auth)?"":" WHERE owner.username=?";
  String q="""
   SELECT a.id,a.name,a.enabled,a.expires_at AS expiresAt,a.revoked_at AS revokedAt,
     a.created_at AS createdAt,owner.id AS ownerId,owner.display_name AS ownerName,
     (SELECT MAX(r.created_at) FROM dg_requests r WHERE r.agent_id=a.id) AS lastAccess
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
  List<String> owners=db.query("SELECT owner.username FROM dg_agents a JOIN dg_users owner ON owner.id=a.owner_user_id WHERE a.id=?",(rs,i)->rs.getString(1),Guard.id(agent));
  if(owners.size()!=1||(!admin(auth)&&!auth.getName().equals(owners.get(0))))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 }
 @DeleteMapping("/{id}")
 @org.springframework.transaction.annotation.Transactional
 Map<String,Object> deleteAgent(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  if(db.queryForList("SELECT id FROM dg_agents WHERE id=? FOR UPDATE",safe).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  Number refs=db.queryForObject("SELECT (SELECT COUNT(*) FROM dg_requests WHERE agent_id=?)+(SELECT COUNT(*) FROM dg_rules WHERE agent_id=?)",Long.class,safe,safe);
  if(refs!=null&&refs.longValue()>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent has historical requests or rules; revoke instead");
  db.update("DELETE FROM dg_agent_columns WHERE agent_id=?",safe);
  db.update("DELETE FROM dg_agent_tables WHERE agent_id=?",safe);
  db.update("DELETE FROM dg_agents WHERE id=?",safe);
  audit.log(auth.getName(),"AGENT_DELETED",null,safe,"SUCCESS","No historical references");
  return Map.of("deleted",true);
 }
 @PostMapping("/{id}/revoke") Map<String,Object> revoke(Authentication auth,@PathVariable String id){
  own(auth,id);db.update("UPDATE dg_agents SET revoked_at=NOW(3),enabled=0 WHERE id=?",Guard.id(id));
  audit.log(auth.getName(),"AGENT_REVOKED",null,id,"SUCCESS","");
  return Map.of("updated",true);
 }

 // One-time secret on successful rotation; previous credential becomes invalid immediately.
 @org.springframework.transaction.annotation.Transactional
 @PostMapping("/{id}/rotate")
 Map<String,Object> rotate(Authentication auth,@PathVariable String id,@RequestBody Rotate request){
  own(auth,id);
  if(request==null||request.validDays()==null||request.validDays()<1||request.validDays()>90)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Token validity must be 1 to 90 days");
  String token=newToken();
  int n=db.update("UPDATE dg_agents SET token_hash=?,expires_at=DATE_ADD(NOW(3),INTERVAL ? DAY) WHERE id=? AND enabled=1 AND revoked_at IS NULL",
   digest(token),request.validDays(),Guard.id(id));
  if(n!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent already revoked");
  audit.log(auth.getName(),"AGENT_TOKEN_ROTATED",null,id,"SUCCESS","");
  return Map.of("token",token,"id",Guard.id(id));
 }
 record Rotate(Integer validDays){}

 // Safe connectivity precheck: returns effective grants and identity validity, never the secret.
 @GetMapping("/{id}/check")
 Map<String,Object> check(Authentication auth,@PathVariable String id){
  own(auth,id);String agent=Guard.id(id);
  Map<String,Object> status=db.queryForMap("""
   SELECT a.enabled,a.expires_at AS expiresAt,a.revoked_at AS revokedAt,
    u.enabled AS ownerEnabled,
    (SELECT COUNT(*) FROM dg_agent_columns c JOIN dg_source_columns sc ON
      sc.source_id=c.source_id AND sc.table_name=c.table_name AND sc.column_name=c.column_name
      JOIN dg_source_tables st ON st.source_id=c.source_id AND st.table_name=c.table_name
      JOIN dg_sources ds ON ds.id=c.source_id
      WHERE c.agent_id=a.id AND sc.enabled=1 AND st.enabled=1 AND ds.enabled=1) AS effectiveColumns
   FROM dg_agents a JOIN dg_users u ON u.id=a.owner_user_id WHERE a.id=?
   """,agent);
  boolean active=Boolean.TRUE.equals(status.get("enabled"))&&
   Boolean.TRUE.equals(status.get("ownerEnabled"))&&status.get("revokedAt")==null&&
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
 @PostMapping("/{id}/tables") Map<String,Object> grantTable(Authentication auth,@PathVariable String id,@RequestBody TableGrant input){
  Guard.admin(auth);String agent=Guard.id(id),source=Guard.id(input.sourceId()),table=Guard.identifier(input.tableName());
  Integer allowed=db.queryForObject("""
   SELECT COUNT(*) FROM dg_source_tables w JOIN dg_sources s ON s.id=w.source_id
   WHERE w.source_id=? AND w.table_name=? AND w.enabled=1 AND s.enabled=1
   """,Integer.class,source,table);
  if(allowed!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source table is not allowlisted");
  db.update("INSERT IGNORE INTO dg_agent_tables(agent_id,source_id,table_name) VALUES(?,?,?)",agent,source,table);
  audit.log(auth.getName(),"AGENT_TABLE_GRANTED",null,agent+"/"+source+"/"+table,"SUCCESS","");
  return Map.of("updated",true);
 }
 @PostMapping("/{id}/columns") Map<String,Object> grantColumn(Authentication auth,@PathVariable String id,@RequestBody ColumnGrant input){
  Guard.admin(auth);String agent=Guard.id(id),source=Guard.id(input.sourceId()),table=Guard.identifier(input.tableName()),column=Guard.identifier(input.columnName());
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
 @DeleteMapping("/{id}/tables/{source}/{table}") Map<String,Object> revokeTable(Authentication auth,@PathVariable String id,@PathVariable String source,@PathVariable String table){
  Guard.admin(auth);
  db.update("DELETE FROM dg_agent_columns WHERE agent_id=? AND source_id=? AND table_name=?",Guard.id(id),Guard.id(source),Guard.identifier(table));
  db.update("DELETE FROM dg_agent_tables WHERE agent_id=? AND source_id=? AND table_name=?",Guard.id(id),Guard.id(source),Guard.identifier(table));
  audit.log(auth.getName(),"AGENT_TABLE_REVOKED",null,id+"/"+table,"SUCCESS","");
  return Map.of("updated",true);
 }
 @DeleteMapping("/{id}/columns/{source}/{table}/{column}") Map<String,Object> revokeColumn(Authentication auth,@PathVariable String id,@PathVariable String source,@PathVariable String table,@PathVariable String column){
  Guard.admin(auth);
  db.update("DELETE FROM dg_agent_columns WHERE agent_id=? AND source_id=? AND table_name=? AND column_name=?",Guard.id(id),Guard.id(source),Guard.identifier(table),Guard.identifier(column));
  audit.log(auth.getName(),"AGENT_COLUMN_REVOKED",null,id+"/"+table+"/"+column,"SUCCESS","");
  return Map.of("updated",true);
 }
 Map<String,Object> resolve(String raw){
  if(raw==null||!raw.matches("dg_[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Agent credential");
  List<Map<String,Object>> rows=db.queryForList("""
    SELECT a.id,a.name,a.owner_user_id AS ownerId,u.username AS ownerUsername
    FROM dg_agents a JOIN dg_users u ON u.id=a.owner_user_id
    WHERE a.token_hash=? AND a.enabled=1 AND a.revoked_at IS NULL
      AND a.expires_at>NOW(3) AND u.enabled=1 AND u.must_change_password=0
    """,digest(raw));
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Agent credential");
  return rows.get(0);
 }
}
