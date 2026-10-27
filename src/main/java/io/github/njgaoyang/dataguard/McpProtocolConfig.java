package io.github.njgaoyang.dataguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Official MCP Java SDK server. Transport, JSON-RPC, protocol negotiation,
 * notifications and tool schemas belong to the SDK rather than a hand-coded controller.
 *
 * This is deliberately STATELESS Streamable HTTP: POST JSON-RPC, GET returns 405
 * (no outbound SSE stream), no session ID, no application-controlled HTTP+SSE fallback.
 */
@Configuration
class McpProtocolConfig {
 private static final String AGENT="dataguard.agent";
 private static final String PATH="/mcp";
 private final AgentApi agents;
 private final AgentCatalogService catalog;
 private final SqlQueryService sql;
 private final PolicyEngine policy;
 private final Audit audit;
 private final SecurityControls controls;
 private final ObjectMapper jackson;
 McpProtocolConfig(AgentApi agents,AgentCatalogService catalog,SqlQueryService sql,
                   PolicyEngine policy,Audit audit,SecurityControls controls,ObjectMapper jackson){
  this.agents=agents;this.catalog=catalog;this.sql=sql;
  this.policy=policy;this.audit=audit;this.controls=controls;this.jackson=jackson;
 }

 @Bean FilterRegistrationBean<OncePerRequestFilter> mcpAuthenticationFilter(){
  var result=new FilterRegistrationBean<OncePerRequestFilter>();
  result.setFilter(new OncePerRequestFilter(){
   @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
       throws ServletException,IOException {
    // Strict Origin checking on every method, including probes. Absent Origin is normal for desktop clients.
    String origin=request.getHeader("Origin");
    if(origin!=null){
     Set<String> trusted=new HashSet<>();
     for(String v:System.getenv().getOrDefault("DG_ALLOWED_MCP_ORIGINS","").split(",")){
      if(!v.isBlank())trusted.add(v.strip());
     }
     if(!trusted.contains(origin)){response.setStatus(403);return;}
    }
    String version=request.getHeader("MCP-Protocol-Version");
    if(version!=null&&!Set.of("2025-03-26","2025-06-18","2025-11-25").contains(version)){
     response.setStatus(400);return;
    }
    String authorization=request.getHeader("Authorization");
    if(authorization==null||!authorization.regionMatches(true,0,"Bearer ",0,7)){
     response.setHeader("WWW-Authenticate","Bearer");
     response.setStatus(401);return;
    }
    try{
     Map<String,Object> agent=agents.resolve(authorization.substring(7));
     controls.chargeAgent(String.valueOf(agent.get("id")),0);
     request.setAttribute(AGENT,agent);
     chain.doFilter(request,response);
    }catch(ResponseStatusException ex){
     if(!response.isCommitted()){
      int status=ex.getStatusCode().value();
      if(status==401)response.setHeader("WWW-Authenticate","Bearer");
      if(status==429)response.setHeader("Retry-After","60");
      response.setStatus(status);
     }
    }
   }
  });
  result.addUrlPatterns(PATH);
  result.setOrder(-110);
  return result;
 }

 @Bean WebMvcStatelessServerTransport mcpTransport(ObjectMapper mapper){
  return WebMvcStatelessServerTransport.builder()
    .jsonMapper(new JacksonMcpJsonMapper(mapper))
    .messageEndpoint(PATH)
    .contextExtractor(request -> {
     Object agent=request.servletRequest().getAttribute(AGENT);
     if(!(agent instanceof Map<?,?>))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Missing Agent identity");
     return McpTransportContext.create(Map.of(AGENT,agent));
    })
    .build();
 }

 @Bean RouterFunction<ServerResponse> mcpRouter(WebMvcStatelessServerTransport transport){
  return transport.getRouterFunction();
 }

 private McpSchema.Tool tool(String name,String description,Map<String,Object> props,List<String> required){
  return McpSchema.Tool.builder().name(name).description(description)
   .inputSchema(new McpSchema.JsonSchema("object",props,required,false,null,null))
   .build();
 }

 private Map<String,Object> schema(String type){return Map.of("type",type);}
 private McpSchema.CallToolResult reply(Object body,boolean isError){
  try{
   return McpSchema.CallToolResult.builder()
      .addTextContent(jackson.writeValueAsString(body)).isError(isError).build();
  }catch(Exception ex){throw new IllegalStateException("MCP result encoding failed");}
 }
 @SuppressWarnings("unchecked")
 private Map<String,Object> principal(McpTransportContext context){
  Object p=context.get(AGENT);
  if(!(p instanceof Map<?,?>))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Missing Agent identity");
  return (Map<String,Object>)p;
 }
 private McpSchema.CallToolResult execute(McpTransportContext context,String name,Map<String,Object> args){
  Map<String,Object> agent=principal(context);
  String id=String.valueOf(agent.get("id"));
  try{
   Object data=switch(name){
    case "list_datasources" -> catalog.datasources(id);
    case "get_schema" -> catalog.tables(id,(String)required(args,"sourceId"));
    case "describe_table" -> catalog.describeTable(id,(String)required(args,"sourceId"),(String)required(args,"tableName"));
    case "get_access_request" -> policy.status(agent,(String)required(args,"requestId"));
    case "validate_sql" -> sql.validate(agent,args);
    case "execute_sql_readonly" -> sql.execute(agent,args);
    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported MCP tool");
   };
   return reply(data,false);
  }catch(PolicyEngine.Pending pending){
   return reply(Map.of("status","PENDING","requestId",pending.requestId,
    "message","Human approval required; check get_access_request, then retry the exact SQL and params."),false);
  }catch(ResponseStatusException ex){
   audit.log(String.valueOf(agent.get("name")),"MCP_DENIED",null,name,"DENIED","MCP tool request rejected");
   int status=ex.getStatusCode().value();
   return reply(Map.of("status","ERROR","code",switch(status){
    case 400 -> "INVALID_ARGUMENT";case 401 -> "UNAUTHORIZED";case 403 -> "ACCESS_DENIED";
    case 404 -> "NOT_FOUND";case 409 -> "CONFLICT";case 429 -> "RATE_LIMITED";
    case 502 -> "DATASOURCE_FAILURE";default -> "QUERY_FAILED";
   },"message",Objects.toString(ex.getReason(),"MCP request rejected")),true);
  }catch(RuntimeException ex){
   audit.log(String.valueOf(agent.get("name")),"MCP_ERROR",null,name,"ERROR","Unexpected MCP tool failure");
   return reply(Map.of("status","ERROR","code","QUERY_FAILED","message","Request failed"),true);
  }
 }
 private Object required(Map<String,Object> args,String name){
  Object v=args.get(name);
  if(!(v instanceof String s)||s.isBlank())
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid required tool argument: "+name);
  return v;
 }

 @Bean(destroyMethod="close")
 McpStatelessSyncServer mcpServer(WebMvcStatelessServerTransport transport){
  var propsSql=new LinkedHashMap<String,Object>();
  propsSql.put("sourceId",schema("string"));
  propsSql.put("sql",schema("string"));
  propsSql.put("params",Map.of("type","array","items",Map.of("type",List.of("string","number","boolean","null"))));
  var spec=McpServer.sync(transport)
    .serverInfo("DataGuard","2.0.0")
    .instructions("Read-only approved SQL gateway. List sources, inspect granted schema, validate then execute SQL. Approvals require get_access_request and retrying identical parameters.")
    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
    .immediateExecution(true);
  spec.toolCall(tool("list_datasources","List data sources granted to this Agent",Map.of(),List.of()),
    (ctx,request)->execute(ctx,"list_datasources",request.arguments()));
  spec.toolCall(tool("get_schema","List authorized tables for a source",
      Map.of("sourceId",schema("string")),List.of("sourceId")),
    (ctx,request)->execute(ctx,"get_schema",request.arguments()));
  spec.toolCall(tool("describe_table","Describe authorized table columns and physical types",
      Map.of("sourceId",schema("string"),"tableName",schema("string")),List.of("sourceId","tableName")),
    (ctx,request)->execute(ctx,"describe_table",request.arguments()));
  spec.toolCall(tool("validate_sql","Validate query syntax, columns, functions and grants without executing",
      propsSql,List.of("sourceId","sql")),
    (ctx,request)->execute(ctx,"validate_sql",request.arguments()));
  spec.toolCall(tool("execute_sql_readonly","Run an authorized, resource-bounded SELECT; may request one-time approval",
      propsSql,List.of("sourceId","sql")),
    (ctx,request)->execute(ctx,"execute_sql_readonly",request.arguments()));
  spec.toolCall(tool("get_access_request","Inspect one human review request and its decision",
      Map.of("requestId",schema("string")),List.of("requestId")),
    (ctx,request)->execute(ctx,"get_access_request",request.arguments()));
  return spec.build();
 }
}