package io.github.njgaoyang.dataguard;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stateless MCP Streamable HTTP (JSON) bridge. No arbitrary SQL is accepted.
 * Only the platform's generated SELECT may reach a read-only MySQL/StarRocks account.
 */
@RestController
class McpGateway {
 private final JdbcTemplate db;
 private final AgentApi agents;
 private final SourceApi sources;
 private final PolicyEngine policy;
 private final Audit audit;
 private final Set<String> allowedHosts;
 private final Semaphore slots;
 private final int timeoutSeconds;
 McpGateway(JdbcTemplate db,AgentApi agents,SourceApi sources,PolicyEngine policy,Audit audit,
   @Value("${dataguard.allowed-source-hosts:}") String hosts,
   @Value("${dataguard.max-concurrency:4}") int concurrency,
   @Value("${dataguard.query-timeout-seconds:5}") int timeoutSeconds){
  this.db=db;this.agents=agents;this.sources=sources;this.policy=policy;this.audit=audit;
  this.allowedHosts=new HashSet<>(Arrays.stream(hosts.split(",")).map(String::trim).filter(s->!s.isBlank()).toList());
  this.slots=new Semaphore(Math.max(1,Math.min(32,concurrency)));
  this.timeoutSeconds=Math.max(1,Math.min(30,timeoutSeconds));
 }
 private Map<String,Object> reply(Object id,String key,Object value){
  Map<String,Object> result=new LinkedHashMap<>();result.put("jsonrpc","2.0");result.put("id",id);result.put(key,value);return result;
 }
 private ResponseEntity<Map<String,Object>> error(Object id,int code,String msg){
  return ResponseEntity.ok(reply(id,"error",Map.of("code",code,"message",msg)));
 }
 @SuppressWarnings("unchecked")
 @PostMapping(path="/mcp",consumes="application/json",produces="application/json")
 ResponseEntity<Map<String,Object>> call(
  @RequestHeader(value="Authorization",required=false) String bearer,
  @RequestHeader(value="MCP-Protocol-Version",required=false) String protocol,
  @RequestHeader(value="Origin",required=false) String origin,
  @RequestBody(required=false) Map<String,Object> request){
  // Do not expose MCP to unapproved browser origins.
  String allowed=System.getenv().getOrDefault("DG_ALLOWED_MCP_ORIGINS","");
  if(origin!=null&&!Arrays.asList(allowed.split(",")).contains(origin))
   return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  String secret=bearer!=null&&bearer.startsWith("Bearer ")?bearer.substring(7):null;
  Map<String,Object> agent;
  try{agent=agents.resolve(secret);}catch(RuntimeException ex){
   return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
     .header("WWW-Authenticate","Bearer").body(Map.of("jsonrpc","2.0","error",Map.of("code",-32001,"message","Unauthorized")));
  }
  Object id=request==null?null:request.get("id");
  if(request==null||!"2.0".equals(request.get("jsonrpc"))||!(request.get("method") instanceof String method))
   return error(id,-32600,"Invalid JSON-RPC request");
  if(id==null){
   if(method.startsWith("notifications/"))return ResponseEntity.accepted().build();
   return error(null,-32600,"Request ID required");
  }
  if(protocol!=null&&!List.of("2025-03-26","2025-06-18","2025-11-25").contains(protocol))
   return error(id,-32600,"Unsupported MCP protocol version");
  try{
   Map<String,Object> params=request.get("params") instanceof Map<?,?> p?(Map<String,Object>)p:Map.of();
   Object result=switch(method){
    case "initialize" -> Map.of("protocolVersion","2025-11-25","capabilities",Map.of("tools",Map.of()),
      "serverInfo",Map.of("name","DataGuard","version","1.0.0"));
    case "ping" -> Map.of();
    case "tools/list" -> Map.of("tools",tools());
    case "tools/call" -> invoke(agent,params);
    default -> throw new IllegalArgumentException("Unknown MCP method");
   };
   return ResponseEntity.ok(reply(id,"result",result));
  }catch(PolicyEngine.Pending ex){
   return ResponseEntity.ok(reply(id,"result",toolResult(Map.of(
    "status","PENDING","requestId",ex.requestId,"message","Human approval required; poll get_access_request, then retry the exact query."),true)));
  }catch(ResponseStatusException ex){
   audit.log(String.valueOf(agent.get("name")),"MCP_DENIED",null,method,"DENIED","Protected request rejected");
   return error(id,-32003,"Access denied or invalid request");
  }catch(Exception ex){
   audit.log(String.valueOf(agent.get("name")),"MCP_ERROR",null,method,"ERROR","MCP request failed");
   return error(id,-32003,"Request failed");
  }
 }
 private Map<String,Object> toolResult(Object value,boolean error){
  try{
   return Map.of("content",List.of(Map.of("type","text","text",
    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value))),"isError",error);
  }catch(Exception ex){throw new IllegalStateException("Serialization failure");}
 }
 private List<Map<String,Object>> tools(){
  Map<String,Object> empty=Map.of("type","object","properties",Map.of());
  Map<String,Object> source=Map.of("type","object","properties",
    Map.of("sourceId",Map.of("type","string")),"required",List.of("sourceId"));
  Map<String,Object> table=Map.of("type","object","properties",
    Map.of("sourceId",Map.of("type","string"),"tableName",Map.of("type","string")),
    "required",List.of("sourceId","tableName"));
  Map<String,Object> read=Map.of("type","object","properties",
    Map.of("sourceId",Map.of("type","string"),"tableName",Map.of("type","string"),
      "columns",Map.of("type","array","items",Map.of("type","string")),
      "limit",Map.of("type","integer","minimum",1,"maximum",200),
      "filterColumn",Map.of("type","string"),"filterValue",Map.of("type","string"),
      "sortColumn",Map.of("type","string"),"sortDirection",Map.of("type","string","enum",List.of("ASC","DESC"))),
    "required",List.of("sourceId","tableName","columns"));
  Map<String,Object> status=Map.of("type","object","properties",
     Map.of("requestId",Map.of("type","string")),"required",List.of("requestId"));
  return List.of(
    Map.of("name","list_datasources","description","List sources explicitly assigned to this Agent","inputSchema",empty),
    Map.of("name","get_schema","description","List allowed tables for a source","inputSchema",source),
    Map.of("name","describe_table","description","List only approved columns","inputSchema",table),
    Map.of("name","execute_readonly","description","Execute bounded, audited SELECT; may require human approval","inputSchema",read),
    Map.of("name","get_access_request","description","Check approval status for one pending exact query","inputSchema",status));
 }
 @SuppressWarnings("unchecked")
 private Map<String,Object> invoke(Map<String,Object> agent,Map<String,Object> params){
  String name=String.valueOf(params.getOrDefault("name",""));
  if(!(params.getOrDefault("arguments",Map.of()) instanceof Map<?,?> arguments))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Map<String,Object> args=(Map<String,Object>)arguments;
  String agentId=(String)agent.get("id");
  Object result=switch(name){
   case "list_datasources" -> db.queryForList("""
    SELECT DISTINCT s.id,s.name,s.type FROM dg_sources s
    JOIN dg_agent_tables t ON t.source_id=s.id
    JOIN dg_source_tables allow_t ON allow_t.source_id=t.source_id AND allow_t.table_name=t.table_name
    WHERE t.agent_id=? AND s.enabled=1 AND allow_t.enabled=1 ORDER BY s.name
    """,agentId);
   case "get_schema" -> db.queryForList("""
    SELECT t.table_name AS tableName FROM dg_agent_tables t
    JOIN dg_source_tables w ON w.source_id=t.source_id AND w.table_name=t.table_name
    JOIN dg_sources s ON s.id=t.source_id
    WHERE t.agent_id=? AND t.source_id=? AND w.enabled=1 AND s.enabled=1
    ORDER BY t.table_name
    """,agentId,Guard.id(str(args,"sourceId")));
   case "describe_table" -> db.queryForList("""
    SELECT ac.column_name AS columnName FROM dg_agent_columns ac
    JOIN dg_agent_tables at ON at.agent_id=ac.agent_id AND at.source_id=ac.source_id AND at.table_name=ac.table_name
    JOIN dg_source_columns sc ON sc.source_id=ac.source_id AND sc.table_name=ac.table_name AND sc.column_name=ac.column_name
    JOIN dg_source_tables st ON st.source_id=ac.source_id AND st.table_name=ac.table_name
    JOIN dg_sources s ON s.id=ac.source_id
    WHERE ac.agent_id=? AND ac.source_id=? AND ac.table_name=? AND sc.enabled=1 AND st.enabled=1 AND s.enabled=1
    ORDER BY ac.column_name
    """,agentId,Guard.id(str(args,"sourceId")),Guard.identifier(str(args,"tableName")));
   case "get_access_request" -> policy.status(agent,str(args,"requestId"));
   case "execute_readonly" -> query(agent,args);
   default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown tool");
  };
  return toolResult(result,false);
 }
 private static String str(Map<String,Object> params,String key){
  Object v=params.get(key);
  if(!(v instanceof String s))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid "+key);
  return s;
 }
 private static String optional(Map<String,Object> args,String key){
  Object value=args.get(key);
  if(value==null)return null;
  if(!(value instanceof String s))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid "+key);
  return s;
 }
 private Map<String,Object> query(Map<String,Object> agent,Map<String,Object> input){
  String agentId=(String)agent.get("id"),sourceId=Guard.id(str(input,"sourceId"));
  String table=Guard.identifier(str(input,"tableName"));
  Object requestedLimit=input.getOrDefault("limit",10);
  if(!(requestedLimit instanceof Integer limit)||limit<1||limit>200)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Read row limit is 1 to 200");
  if(!(input.get("columns") instanceof List<?> raw)||raw.isEmpty()||raw.size()>100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Explicit columns required");
  Set<String> unique=new TreeSet<>();
  for(Object col:raw){
   if(!(col instanceof String c))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
   unique.add(Guard.identifier(c));
  }
  List<String> columns=List.copyOf(unique);
  String filterColumn=optional(input,"filterColumn"),filterValue=optional(input,"filterValue");
  String sortColumn=optional(input,"sortColumn"),sortDirection=optional(input,"sortDirection");
  if(filterColumn!=null){filterColumn=Guard.identifier(filterColumn);if(filterValue==null||filterValue.length()>256)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
  else if(filterValue!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  if(sortColumn!=null){sortColumn=Guard.identifier(sortColumn);if(sortDirection==null)sortDirection="ASC";
   if(!Set.of("ASC","DESC").contains(sortDirection))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
  else if(sortDirection!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Set<String> requested=new HashSet<>(columns);
  if(filterColumn!=null)requested.add(filterColumn);
  if(sortColumn!=null)requested.add(sortColumn);
  // All visible, filter and sort fields require BOTH platform and Agent grants.
  authorizeColumns(agentId,sourceId,table,requested);
  Map<String,Object> source=sources.sourceForRead(sourceId);
  String host=String.valueOf(source.get("host"));
  if(!allowedHosts.contains(host))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source host not allowlisted");
  long started=System.nanoTime();
  PolicyEngine.Verdict verdict=policy.begin(agent,sourceId,table,columns,limit,filterColumn,filterValue,sortColumn,sortDirection);
  if("DENY".equals(verdict.action()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Policy denied");
  if("APPROVAL".equals(verdict.action()))throw new PolicyEngine.Pending(verdict.id());
  boolean succeeded=false,acquired=false;
  try{
   if(!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Concurrent reads limited");
   acquired=true;
   String sql="SELECT "+String.join(",",columns.stream().map(s->"`"+s+"`").toList())+
      " FROM `"+table+"`"+(filterColumn==null?"":" WHERE `"+filterColumn+"` = ?")+
      (sortColumn==null?"":" ORDER BY `"+sortColumn+"` "+sortDirection)+" LIMIT "+limit;
   String jdbcUrl="jdbc:mysql://"+host+":"+source.get("port")+"/"+Guard.identifier((String)source.get("databaseName"))+
       "?sslMode=VERIFY_IDENTITY&connectTimeout=3000&socketTimeout=6000";
   List<Map<String,Object>> rows=new ArrayList<>();
   try(var connection=DriverManager.getConnection(jdbcUrl,(String)source.get("username"),sources.decryptPassword(source))){
    connection.setReadOnly(true);
    try(var statement=connection.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
     statement.setQueryTimeout(timeoutSeconds);statement.setMaxRows(limit);
     if(filterColumn!=null)statement.setString(1,filterValue);
     try(var result=statement.executeQuery()){
      while(result.next()){
       Map<String,Object> row=new LinkedHashMap<>();
       for(String column:columns){
        Object value=result.getObject(column);
        row.put(column,value instanceof byte[]?"[REDACTED]":
          value instanceof String text && text.length()>256?text.substring(0,256):value);
       }
       rows.add(row);
      }
     }
    }
   }
   // Re-evaluate revocations after I/O and before releasing any values.
   authorizeColumns(agentId,sourceId,table,requested);
   if(!allowedHosts.contains(host))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source host revoked");
   long ms=(System.nanoTime()-started)/1_000_000;
   policy.complete(verdict,rows.size(),ms);
   audit.log((String)agent.get("name"),"AGENT_READ",verdict.id(),sourceId+"/"+table,"SUCCESS","rows="+rows.size()+" durationMs="+ms);
   succeeded=true;
   return Map.of("requestId",verdict.id(),"status","SUCCESS","rowCount",rows.size(),"rows",rows);
  }catch(java.sql.SQLException ex){
   audit.log((String)agent.get("name"),"AGENT_READ",verdict.id(),sourceId+"/"+table,"ERROR","JDBC read failed");
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Read failed");
  }finally{
   if(acquired)slots.release();
   if(!succeeded)policy.fail(verdict);
  }
 }
 private void authorizeColumns(String agentId,String source,String table,Set<String> requested){
  List<String> granted=db.query("""
   SELECT ac.column_name FROM dg_agent_columns ac
   JOIN dg_agent_tables t ON t.agent_id=ac.agent_id AND t.source_id=ac.source_id AND t.table_name=ac.table_name
   JOIN dg_source_columns c ON c.source_id=ac.source_id AND c.table_name=ac.table_name AND c.column_name=ac.column_name
   JOIN dg_source_tables w ON w.source_id=ac.source_id AND w.table_name=ac.table_name
   JOIN dg_sources s ON s.id=ac.source_id
   JOIN dg_agents a ON a.id=ac.agent_id
   JOIN dg_users u ON u.id=a.owner_user_id
   WHERE ac.agent_id=? AND ac.source_id=? AND ac.table_name=?
     AND c.enabled=1 AND w.enabled=1 AND s.enabled=1
     AND a.enabled=1 AND a.revoked_at IS NULL AND a.expires_at>NOW(3)
     AND u.enabled=1 AND u.must_change_password=0
   """,(rs,i)->rs.getString(1),agentId,source,table);
  if(requested.isEmpty()||!new HashSet<>(granted).containsAll(requested))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Table or column not authorized");
 }
}