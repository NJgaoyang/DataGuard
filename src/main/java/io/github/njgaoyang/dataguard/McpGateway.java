package io.github.njgaoyang.dataguard;

import java.util.*;
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
 private final AgentApi agents;
 private final AgentCatalogService catalog;
 private final PolicyEngine policy;
 private final Audit audit;
 private final SecurityControls securityControls;
 private final SqlQueryService sqlQueryService;
 private final com.fasterxml.jackson.databind.ObjectMapper json;
 McpGateway(AgentApi agents,AgentCatalogService catalog,PolicyEngine policy,Audit audit,
    SecurityControls securityControls,SqlQueryService sqlQueryService,
    com.fasterxml.jackson.databind.ObjectMapper json){
  this.agents=agents;this.catalog=catalog;this.policy=policy;
  this.audit=audit;this.securityControls=securityControls;this.sqlQueryService=sqlQueryService;this.json=json;
 }
 private Map<String,Object> reply(Object id,String key,Object value){
  Map<String,Object> result=new LinkedHashMap<>();result.put("jsonrpc","2.0");result.put("id",id);result.put(key,value);return result;
 }
 private ResponseEntity<Map<String,Object>> error(Object id,int code,String msg){
  return ResponseEntity.ok(reply(id,"error",Map.of("code",code,"message",msg)));
 }
 private static final Set<String> SUPPORTED_PROTOCOLS=Set.of("2025-03-26","2025-06-18","2025-11-25");
 private Map<String,Object> toolFailure(int status,String message){
  return Map.of("status","ERROR","code",switch(status){
   case 400 -> "INVALID_ARGUMENT";case 401 -> "UNAUTHORIZED";case 403 -> "ACCESS_DENIED";
   case 404 -> "NOT_FOUND";case 409 -> "CONFLICT";case 429 -> "RATE_LIMITED";
   case 502 -> "DATASOURCE_FAILURE";default -> "QUERY_FAILED";
  },"message",message);
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
  String secret=bearer!=null&&bearer.regionMatches(true,0,"Bearer ",0,7)?bearer.substring(7):null;
  Map<String,Object> agent;
  try{agent=agents.resolve(secret);}catch(RuntimeException ex){
   return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
     .header("WWW-Authenticate","Bearer").body(Map.of("jsonrpc","2.0","error",Map.of("code",-32001,"message","Unauthorized")));
  }
  Object id=request==null?null:request.get("id");
  if(request==null||!"2.0".equals(request.get("jsonrpc"))||!(request.get("method") instanceof String method))
   return error(id,-32600,"Invalid JSON-RPC request");
  if(protocol!=null&&!SUPPORTED_PROTOCOLS.contains(protocol))
   return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
    reply(id,"error",Map.of("code",-32600,"message","Unsupported MCP protocol version")));
  if(id==null){
   if("notifications/initialized".equals(method)||"notifications/cancelled".equals(method))
    return ResponseEntity.accepted().build();
   return error(null,-32600,"Request ID required");
  }
  try{
   Map<String,Object> params=request.get("params") instanceof Map<?,?> p?(Map<String,Object>)p:Map.of();
   securityControls.chargeAgent(String.valueOf(agent.get("id")),0);
   Object result=switch(method){
    case "initialize" -> {
     String version=params.get("protocolVersion") instanceof String v&&SUPPORTED_PROTOCOLS.contains(v)?v:"2025-11-25";
     yield Map.of("protocolVersion",version,"capabilities",Map.of("tools",Map.of()),
      "serverInfo",Map.of("name","DataGuard","version","1.0.0"));
    }
    case "ping" -> Map.of();
    case "tools/list" -> Map.of("tools",tools());
    case "tools/call" -> invoke(agent,params);
    default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown MCP method");
   };
   return ResponseEntity.ok(reply(id,"result",result));
  }catch(PolicyEngine.Pending ex){
   return ResponseEntity.ok(reply(id,"result",toolResult(Map.of(
    "status","PENDING","requestId",ex.requestId,"message","Human approval required; poll get_access_request, then retry the exact query."),false)));
  }catch(ResponseStatusException ex){
   if(ex.getStatusCode()==HttpStatus.TOO_MANY_REQUESTS){
    audit.log(String.valueOf(agent.get("name")),"MCP_QUOTA",null,method,"DENIED","Agent rate limit exceeded");
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","60").body(
      reply(id,"error",Map.of("code",-32029,"message","Agent quota exceeded")));
   }
   audit.log(String.valueOf(agent.get("name")),"MCP_DENIED",null,method,"DENIED","Protected request rejected");
   if("tools/call".equals(method))return ResponseEntity.ok(reply(id,"result",
     toolResult(toolFailure(ex.getStatusCode().value(),Objects.toString(ex.getReason(),"Request rejected")),true)));
   return error(id,ex.getStatusCode().value()==404?-32601:-32602,"Invalid method or parameters");
  }catch(Exception ex){
   audit.log(String.valueOf(agent.get("name")),"MCP_ERROR",null,method,"ERROR","MCP request failed");
   if("tools/call".equals(method))return ResponseEntity.ok(reply(id,"result",
     toolResult(toolFailure(500,"Request failed"),true)));
   return error(id,-32603,"Internal request failure");
  }
 }
 private Map<String,Object> toolResult(Object value,boolean error){
  try{
   return Map.of("content",List.of(Map.of("type","text","text",
    json.writeValueAsString(value))),"isError",error);
  }catch(Exception ex){throw new IllegalStateException("Serialization failure");}
 }
 private List<Map<String,Object>> tools(){
  Map<String,Object> empty=Map.of("type","object","properties",Map.of());
  Map<String,Object> source=Map.of("type","object","properties",
    Map.of("sourceId",Map.of("type","string")),"required",List.of("sourceId"));
  Map<String,Object> table=Map.of("type","object","properties",
    Map.of("sourceId",Map.of("type","string"),"tableName",Map.of("type","string")),
    "required",List.of("sourceId","tableName"));
  Map<String,Object> sqlRead=Map.of("type","object","properties",Map.of(
    "sourceId",Map.of("type","string"),"sql",Map.of("type","string"),
    "params",Map.of("type","array","items",Map.of("type",List.of("string","number","boolean","null")))),
    "required",List.of("sourceId","sql"));
  Map<String,Object> status=Map.of("type","object","properties",
     Map.of("requestId",Map.of("type","string")),"required",List.of("requestId"));
  return List.of(
    Map.of("name","list_datasources","description","List sources explicitly assigned to this Agent","inputSchema",empty),
    Map.of("name","get_schema","description","List allowed tables for a source","inputSchema",source),
    Map.of("name","describe_table","description","List only approved columns","inputSchema",table),
    Map.of("name","validate_sql","description","Parse SQL and check table, column and function grants without execution","inputSchema",sqlRead),
    Map.of("name","execute_sql_readonly","description","Run a bounded, audited SELECT on one source; supports calculations and authorized two-table JOIN; may require approval","inputSchema",sqlRead),
    Map.of("name","get_access_request","description","Check approval status for one pending exact query","inputSchema",status));
 }
 @SuppressWarnings("unchecked")
 Map<String,Object> invoke(Map<String,Object> agent,Map<String,Object> params){
  return toolResult(invokeRaw(agent,params),false);
 }
 @SuppressWarnings("unchecked")
 Object invokeRaw(Map<String,Object> agent,Map<String,Object> params){
  String name=String.valueOf(params.getOrDefault("name",""));
  if(!(params.getOrDefault("arguments",Map.of()) instanceof Map<?,?> arguments))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Map<String,Object> args=(Map<String,Object>)arguments;
  String agentId=(String)agent.get("id");
  Object result=switch(name){
   case "list_datasources" -> catalog.datasources(agentId);
   case "get_schema" -> catalog.tables(agentId,str(args,"sourceId"));
   case "describe_table" -> catalog.describeTable(agentId,str(args,"sourceId"),str(args,"tableName"));
   case "get_access_request" -> policy.status(agent,str(args,"requestId"));
   case "validate_sql" -> sqlQueryService.validate(agent,args);
   case "execute_sql_readonly" -> sqlQueryService.execute(agent,args);
   default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown tool");
  };
  return result;
 }
 private static String str(Map<String,Object> params,String key){
  Object v=params.get(key);
  if(!(v instanceof String s))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid "+key);
  return s;
 }
}