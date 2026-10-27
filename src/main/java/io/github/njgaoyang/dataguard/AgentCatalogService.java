package io.github.njgaoyang.dataguard;
import java.sql.DriverManager;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
/** Authorized catalog shared by MCP and REST. */
@Service
class AgentCatalogService {
 private final JdbcTemplate db;
 private final SourceApi sources;
 private final Set<String> allowedHosts;
 AgentCatalogService(JdbcTemplate db,SourceApi sources,@Value("${dataguard.allowed-source-hosts:}") String hosts){
  this.db=db;this.sources=sources;
  this.allowedHosts=new HashSet<>(Arrays.stream(hosts.split(",")).map(String::trim).filter(s->!s.isBlank()).toList());
 }
 List<Map<String,Object>> datasources(String agentId){
  return db.queryForList("""
    SELECT DISTINCT s.id,s.name,s.type FROM dg_sources s
    JOIN dg_agent_tables t ON t.source_id=s.id
    JOIN dg_source_tables allow_t ON allow_t.source_id=t.source_id AND allow_t.table_name=t.table_name
    WHERE t.agent_id=? AND s.enabled=1 AND allow_t.enabled=1 ORDER BY s.name
    """,agentId);
 }
 List<Map<String,Object>> tables(String agentId,String sourceId){
  return db.queryForList("""
    SELECT t.table_name AS tableName FROM dg_agent_tables t
    JOIN dg_source_tables w ON w.source_id=t.source_id AND w.table_name=t.table_name
    JOIN dg_sources s ON s.id=t.source_id
    WHERE t.agent_id=? AND t.source_id=? AND w.enabled=1 AND s.enabled=1
    ORDER BY t.table_name
    """,agentId,Guard.id(sourceId));
 }
 List<Map<String,Object>> describeTable(String agentId,String sourceId,String tableName){
  String source=Guard.id(sourceId),table=Guard.identifier(tableName);
  List<Map<String,Object>> grants=db.queryForList("""
 SELECT ac.column_name AS columnName,sc.masked FROM dg_agent_columns ac
    JOIN dg_agent_tables at ON at.agent_id=ac.agent_id AND at.source_id=ac.source_id AND at.table_name=ac.table_name
    JOIN dg_source_columns sc ON sc.source_id=ac.source_id AND sc.table_name=ac.table_name AND sc.column_name=ac.column_name
    JOIN dg_source_tables st ON st.source_id=ac.source_id AND st.table_name=ac.table_name
    JOIN dg_sources s ON s.id=ac.source_id
    WHERE ac.agent_id=? AND ac.source_id=? AND ac.table_name=? AND sc.enabled=1 AND st.enabled=1 AND s.enabled=1
    ORDER BY ac.column_name
   """,agentId,source,table);
  if(grants.isEmpty())return grants;
  Map<String,Object> dataSource=sources.sourceForRead(source);
  if(!allowedHosts.contains(String.valueOf(dataSource.get("host"))))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source host not allowlisted");
  Set<String> primaryKeys=new HashSet<>();
  Map<String,Map<String,Object>> physical=new HashMap<>();
  try(var connection=DriverManager.getConnection(sources.jdbcUrlFor(dataSource),
        String.valueOf(dataSource.get("username")),sources.decryptPassword(dataSource))){
   connection.setReadOnly(true);
   var meta=connection.getMetaData();
   // StarRocks and some MySQL-compatible JDBC sources do not expose primary keys.
   // Preserve authorized column descriptions even when optional PK metadata is absent.
   try(var keys=meta.getPrimaryKeys(String.valueOf(dataSource.get("databaseName")),null,table)){
    while(keys.next())primaryKeys.add(keys.getString("COLUMN_NAME"));
   }catch(java.sql.SQLException unsupportedPrimaryKeys){ /* optional JDBC metadata */ }
   try(var fields=meta.getColumns(String.valueOf(dataSource.get("databaseName")),null,table,null)){
    while(fields.next()){
     String name=fields.getString("COLUMN_NAME");
     if(name!=null)physical.put(name,Map.of("dataType",Objects.toString(fields.getString("TYPE_NAME"),"unknown"),
       "nullable",fields.getInt("NULLABLE")!=java.sql.DatabaseMetaData.columnNoNulls));
    }
   }
  }catch(java.sql.SQLException ex){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unable to inspect authorized table");}
  List<Map<String,Object>> result=new ArrayList<>();
  for(var grant:grants){
   String name=String.valueOf(grant.get("columnName"));
   var item=new LinkedHashMap<String,Object>(grant);
   item.putAll(physical.getOrDefault(name,Map.of("dataType","unknown","nullable",true)));
   item.put("primaryKey",primaryKeys.contains(name));
   result.add(item);
  }
  return result;
 }

}