package io.github.njgaoyang.dataguard;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** REST adapter around the existing MCP tool dispatch and its access policy. */
@RestController
@RequestMapping("/api/v1/agent")
class AgentRestController {
 private final AgentApi agents;
 private final McpGateway gateway;
 private final SecurityControls controls;
 AgentRestController(AgentApi agents,McpGateway gateway,SecurityControls controls){this.agents=agents;this.gateway=gateway;this.controls=controls;}
 private Map<String,Object> identity(String bearer){
  if(bearer==null||!bearer.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Agent token required");
  try{return agents.resolve(bearer.substring(7));}
  catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Agent token");}
 }
 private Object call(String bearer,String tool,Map<String,Object> params,int requestedRows){
  var agent=identity(bearer);
  controls.chargeAgent(String.valueOf(agent.get("id")),requestedRows);
  try{return gateway.invokeRest(agent,tool,params);}
  catch(PolicyEngine.Pending pending){return Map.of("status","PENDING","requestId",pending.requestId,"message","Human approval required; retry identical query after approval");}
 }
 @GetMapping("/datasources") Object sources(@RequestHeader(value="Authorization",required=false) String bearer){return call(bearer,"list_datasources",Map.of(),0);}
 @GetMapping("/datasources/{id}/tables") Object tables(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable String id){return call(bearer,"get_schema",Map.of("sourceId",id),0);}
 @GetMapping("/datasources/{id}/tables/{table}/columns") Object columns(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable String id,@PathVariable String table){return call(bearer,"describe_table",Map.of("sourceId",id,"tableName",table),0);}
 @PostMapping("/queries") Object query(@RequestHeader(value="Authorization",required=false) String bearer,@RequestBody Map<String,Object> request){
  if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Query required");
  Object raw=request.getOrDefault("limit",10);
  if(!(raw instanceof Integer limit)||limit<1||limit>controls.setting("max_rows_per_query",200))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Limit exceeds configured per-query maximum");
  return call(bearer,"execute_readonly",request,limit);
 }
 @GetMapping("/requests/{id}") Object request(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable String id){return call(bearer,"get_access_request",Map.of("requestId",id),0);}
}
