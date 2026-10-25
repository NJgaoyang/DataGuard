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
 record Create(String name,String ownerId,Integer validDays){}
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
 boolean admin(Authentication a){return a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN"));}
 @GetMapping List<Map<String,Object>> list(Authentication auth){
  String filter=admin(auth)?"":" WHERE owner.username=?";
  String q="""
   SELECT a.id,a.name,a.enabled,a.expires_at AS expiresAt,a.revoked_at AS revokedAt,
     a.created_at AS createdAt,owner.id AS ownerId,owner.display_name AS ownerName
   FROM dg_agents a JOIN dg_users owner ON owner.id=a.owner_user_id
   """+filter+" ORDER BY a.created_at DESC LIMIT 300";
  return admin(auth)?db.queryForList(q):db.queryForList(q,auth.getName());
 }
 @PostMapping Map<String,Object> create(Authentication auth,@RequestBody Create in){
  if(in==null||in.validDays()==null||in.validDays()<1||in.validDays()>90)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Token validity must be 1 to 90 days");
  String ownerId=in.ownerId()==null||in.ownerId().isBlank()?
   db.queryForObject("SELECT id FROM dg_users WHERE username=?",String.class,auth.getName()):Guard.id(in.ownerId());
  if(!admin(auth)&&!ownerId.equals(db.queryForObject("SELECT id FROM dg_users WHERE username=?",String.class,auth.getName())))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  Integer valid=db.queryForObject("SELECT COUNT(*) FROM dg_users WHERE id=? AND enabled=1 AND must_change_password=0",Integer.class,ownerId);
  if(valid==null||valid!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Owner not active");
  String id=UUID.randomUUID().toString(),token=newToken();
  db.update("INSERT INTO dg_agents(id,owner_user_id,name,token_hash,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(3), INTERVAL ? DAY))",
   id,ownerId,Guard.required(in.name(),120),digest(token),in.validDays());
  audit.log(auth.getName(),"AGENT_CREATED",null,id,"SUCCESS","");
  return Map.of("id",id,"token",token);
 }
 void own(Authentication auth,String agent){
  List<String> owners=db.query("SELECT owner.username FROM dg_agents a JOIN dg_users owner ON owner.id=a.owner_user_id WHERE a.id=?",(rs,i)->rs.getString(1),Guard.id(agent));
  if(owners.size()!=1||(!admin(auth)&&!auth.getName().equals(owners.get(0))))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 }
 @PostMapping("/{id}/revoke") Map<String,Object> revoke(Authentication auth,@PathVariable String id){
  own(auth,id);db.update("UPDATE dg_agents SET revoked_at=NOW(3),enabled=0 WHERE id=?",Guard.id(id));
  audit.log(auth.getName(),"AGENT_REVOKED",null,id,"SUCCESS","");
  return Map.of("updated",true);
 }
 @GetMapping("/{id}/grants") Map<String,Object> grants(Authentication auth,@PathVariable String id){
  own(auth,id);
  return Map.of(
   "tables",db.queryForList("SELECT source_id AS sourceId,table_name AS tableName FROM dg_agent_tables WHERE agent_id=? ORDER BY table_name",Guard.id(id)),
   "columns",db.queryForList("SELECT source_id AS sourceId,table_name AS tableName,column_name AS columnName FROM dg_agent_columns WHERE agent_id=? ORDER BY table_name,column_name",Guard.id(id)));
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
