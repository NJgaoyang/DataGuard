package io.github.njgaoyang.dataguard;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sources")
class SourceApi {
 private final JdbcTemplate db;private final Crypto crypto;private final Audit audit;
 SourceApi(JdbcTemplate db,Crypto crypto,Audit audit){this.db=db;this.crypto=crypto;this.audit=audit;}
 record Input(String name,String type,String host,Integer port,String databaseName,String username,String password){}
 record Grant(String name){}
 @GetMapping List<Map<String,Object>> list(Authentication auth){
  Guard.admin(auth);
  return db.queryForList("""
   SELECT s.id,s.name,s.type,s.host,s.port,s.database_name AS databaseName,
    s.read_username AS username,s.enabled,s.created_at AS createdAt,
    (SELECT COUNT(*) FROM dg_source_tables t WHERE t.source_id=s.id AND t.enabled=1) AS authorizedTables
   FROM dg_sources s ORDER BY s.created_at DESC LIMIT 300
   """);
 }
 @PostMapping Map<String,Object> create(Authentication auth,@RequestBody Input in){
  Guard.admin(auth);
  if(in==null||in.port()==null||in.port()<1||in.port()>65535||!List.of("MYSQL","STARROCKS").contains(in.type())
     ||in.host()==null||!in.host().matches("[A-Za-z0-9.-]{1,253}")
     ||in.password()==null||in.password().isEmpty())
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid source");
  String id=UUID.randomUUID().toString();
  db.update("""
   INSERT INTO dg_sources(id,name,type,host,port,database_name,read_username,password_ciphertext)
   VALUES(?,?,?,?,?,?,?,?)
   """,id,Guard.required(in.name(),128),in.type(),in.host(),in.port(),
      Guard.identifier(in.databaseName()),Guard.required(in.username(),100),crypto.encrypt(in.password()));
  audit.log(auth.getName(),"SOURCE_CREATED",null,id,"SUCCESS","");
  return Map.of("id",id);
 }
 @PostMapping("/{id}/toggle") Map<String,Object> toggle(Authentication auth,@PathVariable String id){
  Guard.admin(auth);int count=db.update("UPDATE dg_sources SET enabled=NOT enabled WHERE id=?",Guard.id(id));
  if(count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"SOURCE_TOGGLED",null,id,"SUCCESS","");
  return Map.of("updated",true);
 }
 @GetMapping("/{id}/tables") List<Map<String,Object>> tables(Authentication auth,@PathVariable String id){
  Guard.admin(auth);
  return db.queryForList("""
   SELECT t.table_name AS tableName,t.enabled,
   (SELECT COUNT(*) FROM dg_source_columns c WHERE c.source_id=t.source_id AND c.table_name=t.table_name AND c.enabled=1) AS authorizedColumns
   FROM dg_source_tables t WHERE t.source_id=? ORDER BY t.table_name
   """,Guard.id(id));
 }
 @PostMapping("/{id}/tables") Map<String,Object> table(Authentication auth,@PathVariable String id,@RequestBody Grant in){
  Guard.admin(auth);String source=Guard.id(id),table=Guard.identifier(in.name());
  db.update("INSERT INTO dg_source_tables(source_id,table_name,enabled) VALUES(?,?,1) ON DUPLICATE KEY UPDATE enabled=1",source,table);
  audit.log(auth.getName(),"TABLE_ALLOWED",null,source+"/"+table,"SUCCESS","");
  return Map.of("updated",true);
 }
 @DeleteMapping("/{id}/tables/{table}") Map<String,Object> removeTable(Authentication auth,@PathVariable String id,@PathVariable String table){
  Guard.admin(auth);String source=Guard.id(id),name=Guard.identifier(table);
  db.update("UPDATE dg_source_tables SET enabled=0 WHERE source_id=? AND table_name=?",source,name);
  audit.log(auth.getName(),"TABLE_REVOKED",null,source+"/"+name,"SUCCESS","");
  return Map.of("updated",true);
 }
 @GetMapping("/{id}/tables/{table}/columns") List<Map<String,Object>> columns(Authentication auth,@PathVariable String id,@PathVariable String table){
  Guard.admin(auth);
  return db.queryForList("SELECT column_name AS columnName,enabled FROM dg_source_columns WHERE source_id=? AND table_name=? ORDER BY column_name",Guard.id(id),Guard.identifier(table));
 }
 @PostMapping("/{id}/tables/{table}/columns") Map<String,Object> column(Authentication auth,@PathVariable String id,@PathVariable String table,@RequestBody Grant in){
  Guard.admin(auth);String source=Guard.id(id),name=Guard.identifier(table),column=Guard.identifier(in.name());
  db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name,enabled) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE enabled=1",source,name,column);
  audit.log(auth.getName(),"COLUMN_ALLOWED",null,source+"/"+name+"/"+column,"SUCCESS","");
  return Map.of("updated",true);
 }
 @DeleteMapping("/{id}/tables/{table}/columns/{column}") Map<String,Object> removeColumn(Authentication auth,@PathVariable String id,@PathVariable String table,@PathVariable String column){
  Guard.admin(auth);String source=Guard.id(id),name=Guard.identifier(table),col=Guard.identifier(column);
  db.update("UPDATE dg_source_columns SET enabled=0 WHERE source_id=? AND table_name=? AND column_name=?",source,name,col);
  audit.log(auth.getName(),"COLUMN_REVOKED",null,source+"/"+name+"/"+col,"SUCCESS","");
  return Map.of("updated",true);
 }
 Map<String,Object> sourceForRead(String sourceId){
  List<Map<String,Object>> r=db.queryForList("""
    SELECT id,type,host,port,database_name AS databaseName,read_username AS username,
      password_ciphertext AS encryptedPassword FROM dg_sources WHERE id=? AND enabled=1
    """,Guard.id(sourceId));
  if(r.size()!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source disabled or unavailable");
  return r.get(0);
 }
 String decryptPassword(Map<String,Object> source){return crypto.decrypt(String.valueOf(source.get("encryptedPassword")));}
}
