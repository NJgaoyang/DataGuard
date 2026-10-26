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
 private final java.util.Set<String> allowedHosts;
 private final boolean allowInsecureLoopbackStarRocks;
 private final String sourceTruststoreUrl;
 private final String sourceTruststorePassword;
 SourceApi(JdbcTemplate db,Crypto crypto,Audit audit,
    @org.springframework.beans.factory.annotation.Value("${dataguard.allowed-source-hosts:}") String hosts,
    @org.springframework.beans.factory.annotation.Value("${dataguard.allow-insecure-loopback-starrocks:false}") boolean allowInsecureLoopbackStarRocks,
    @org.springframework.beans.factory.annotation.Value("${dataguard.source-truststore-url:}") String sourceTruststoreUrl,
    @org.springframework.beans.factory.annotation.Value("${dataguard.source-truststore-password:}") String sourceTruststorePassword){
  this.db=db;this.crypto=crypto;this.audit=audit;
  this.allowedHosts=new java.util.HashSet<>(java.util.Arrays.stream(hosts.split(",")).map(String::trim).filter(s->!s.isBlank()).toList());
  this.allowInsecureLoopbackStarRocks=allowInsecureLoopbackStarRocks;
  this.sourceTruststoreUrl=sourceTruststoreUrl;
  this.sourceTruststorePassword=sourceTruststorePassword;
 }
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

 record CredentialUpdate(String username,String password){}
 @PostMapping("/{id}/credentials")
 Map<String,Object> credentials(Authentication auth,@PathVariable String id,@RequestBody CredentialUpdate input){
  Guard.admin(auth);String safe=Guard.id(id);
  if(input==null||input.password()==null||input.password().isBlank()||input.password().length()>512)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid datasource credentials");
  int rows=db.update("UPDATE dg_sources SET read_username=?,password_ciphertext=? WHERE id=?",
    Guard.required(input.username(),100),crypto.encrypt(input.password()),safe);
  if(rows!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  audit.log(auth.getName(),"SOURCE_CREDENTIALS_UPDATED",null,safe,"SUCCESS","Credentials changed");
  return Map.of("updated",true);
 }
 @PostMapping("/{id}/test")
 Map<String,Object> testConnection(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  Map<String,Object> source=sourceForRead(safe);
  if(!allowedHosts.contains(String.valueOf(source.get("host"))))
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source host not approved");
  long started=System.nanoTime();
  try(var conn=java.sql.DriverManager.getConnection(jdbcUrlFor(source),
     String.valueOf(source.get("username")),decryptPassword(source));
     var stmt=conn.createStatement()){
   conn.setReadOnly(true);
   stmt.setQueryTimeout(3);
   try(var rs=stmt.executeQuery("SELECT 1")){if(!rs.next())throw new java.sql.SQLException("No test result");}
   long ms=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
   audit.log(auth.getName(),"SOURCE_CONNECTION_TEST",null,safe,"SUCCESS","durationMs="+ms);
   return Map.of("ok",true,"latencyMs",ms,"driver",String.valueOf(source.get("type")));
  }catch(java.sql.SQLException ex){
   audit.log(auth.getName(),"SOURCE_CONNECTION_TEST",null,safe,"ERROR",
       ex.getSQLState()==null?"Unable to connect":"JDBC SQL state "+ex.getSQLState());
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Database connection test failed; check host, TLS and credentials");
  }
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
 @org.springframework.transaction.annotation.Transactional
 @DeleteMapping("/{id}/tables/{table}") Map<String,Object> removeTable(Authentication auth,@PathVariable String id,@PathVariable String table){
  Guard.admin(auth);String source=Guard.id(id),name=Guard.identifier(table);
  db.update("DELETE FROM dg_agent_columns WHERE source_id=? AND table_name=?",source,name);
  db.update("DELETE FROM dg_agent_tables WHERE source_id=? AND table_name=?",source,name);
  db.update("UPDATE dg_source_tables SET enabled=0 WHERE source_id=? AND table_name=?",source,name);
  audit.log(auth.getName(),"TABLE_REVOKED",null,source+"/"+name,"SUCCESS","");
  return Map.of("updated",true);
 }
 @GetMapping("/{id}/tables/{table}/columns") List<Map<String,Object>> columns(Authentication auth,@PathVariable String id,@PathVariable String table){
  Guard.admin(auth);
  return db.queryForList("SELECT column_name AS columnName,enabled,masked FROM dg_source_columns WHERE source_id=? AND table_name=? ORDER BY column_name",Guard.id(id),Guard.identifier(table));
 }
 @GetMapping("/{id}/tables/{table}/physical-columns")
 List<Map<String,Object>> physicalColumns(Authentication auth,@PathVariable String id,@PathVariable String table){
  Guard.admin(auth);String sourceId=Guard.id(id),validTable=Guard.identifier(table);
  Map<String,Object> source=sourceForRead(sourceId);
  String host=String.valueOf(source.get("host"));
  if(!allowedHosts.contains(host))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source host is not approved");
  java.util.Map<String,Map<String,Object>> grants=new java.util.HashMap<>();
  for(Map<String,Object> grant:columns(auth,id,table))grants.put(String.valueOf(grant.get("columnName")),grant);
  String jdbcUrl=jdbcUrlFor(source);
  List<Map<String,Object>> result=new java.util.ArrayList<>();
  try(var connection=java.sql.DriverManager.getConnection(jdbcUrl,
      String.valueOf(source.get("username")),decryptPassword(source))){
   connection.setReadOnly(true);
   try(var metadata=connection.getMetaData().getColumns(
       String.valueOf(source.get("databaseName")),null,validTable,null)){
    while(metadata.next()&&result.size()<200){
     String name=metadata.getString("COLUMN_NAME");
     if(name==null||!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))continue;
     String type=metadata.getString("TYPE_NAME");
     Map<String,Object> grant=grants.get(name);
     result.add(Map.of("columnName",name,"dataType",type==null?"—":type.toLowerCase(java.util.Locale.ROOT),
        "enabled",grant!=null&&(Boolean.TRUE.equals(grant.get("enabled"))||"1".equals(String.valueOf(grant.get("enabled")))),
        "masked",grant!=null&&(Boolean.TRUE.equals(grant.get("masked"))||"1".equals(String.valueOf(grant.get("masked"))))));
    }
   }
  }catch(java.sql.SQLException ex){
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unable to inspect approved datasource metadata");
  }
  return result;
 }
 @PostMapping("/{id}/tables/{table}/columns") Map<String,Object> column(Authentication auth,@PathVariable String id,@PathVariable String table,@RequestBody Grant in){
  Guard.admin(auth);String source=Guard.id(id),name=Guard.identifier(table),column=Guard.identifier(in.name());
  db.update("INSERT INTO dg_source_columns(source_id,table_name,column_name,enabled) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE enabled=1",source,name,column);
  audit.log(auth.getName(),"COLUMN_ALLOWED",null,source+"/"+name+"/"+column,"SUCCESS","");
  return Map.of("updated",true);
 }
 @org.springframework.transaction.annotation.Transactional
 @DeleteMapping("/{id}/tables/{table}/columns/{column}") Map<String,Object> removeColumn(Authentication auth,@PathVariable String id,@PathVariable String table,@PathVariable String column){
  Guard.admin(auth);String source=Guard.id(id),name=Guard.identifier(table),col=Guard.identifier(column);
  db.update("DELETE FROM dg_agent_columns WHERE source_id=? AND table_name=? AND column_name=?",source,name,col);
  db.update("UPDATE dg_source_columns SET enabled=0 WHERE source_id=? AND table_name=? AND column_name=?",source,name,col);
  audit.log(auth.getName(),"COLUMN_REVOKED",null,source+"/"+name+"/"+col,"SUCCESS","");
  return Map.of("updated",true);
 }
 @PostMapping("/{id}/tables/{table}/columns/{column}/mask")
 Map<String,Object> toggleMask(Authentication auth,@PathVariable String id,@PathVariable String table,@PathVariable String column){
  Guard.admin(auth);String source=Guard.id(id),name=Guard.identifier(table),col=Guard.identifier(column);
  int n=db.update("UPDATE dg_source_columns SET masked=NOT masked WHERE source_id=? AND table_name=? AND column_name=? AND enabled=1",source,name,col);
  if(n!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Authorized field required");
  audit.log(auth.getName(),"COLUMN_MASK_CHANGED",null,source+"/"+name+"/"+col,"SUCCESS","");
  return Map.of("updated",true);
 }
 String jdbcUrlFor(Map<String,Object> source){
  String host=String.valueOf(source.get("host"));
  if(!allowedHosts.contains(host))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source host is not approved");
  String type=String.valueOf(source.get("type"));
  boolean loopback=java.util.Set.of("127.0.0.1","localhost").contains(host);
  boolean insecureStarRocks=allowInsecureLoopbackStarRocks&&loopback&&"STARROCKS".equals(type);
  String url="jdbc:mysql://"+host+":"+source.get("port")+"/"+Guard.identifier(String.valueOf(source.get("databaseName")))+
    "?sslMode="+(insecureStarRocks?"DISABLED":"VERIFY_IDENTITY")+"&connectTimeout=3000&socketTimeout=6000";
  if(!insecureStarRocks&&!sourceTruststoreUrl.isBlank()){
   url+="&trustCertificateKeyStoreUrl="+sourceTruststoreUrl+
      "&trustCertificateKeyStorePassword="+sourceTruststorePassword;
  }
  return url;
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
