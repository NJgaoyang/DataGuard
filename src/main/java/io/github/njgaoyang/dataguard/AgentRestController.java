package io.github.njgaoyang.dataguard;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Agent REST adapter: directly delegates to catalog and SQL services. */
@RestController
@RequestMapping("/api/v1/agent")
class AgentRestController {
 private final AgentApi agents;
 private final AgentCatalogService catalog;
 private final SqlQueryService sql;
 private final PolicyEngine policy;
 private final SecurityControls controls;
 AgentRestController(AgentApi agents,AgentCatalogService catalog,SqlQueryService sql,
                     PolicyEngine policy,SecurityControls controls){
  this.agents=agents;this.catalog=catalog;this.sql=sql;this.policy=policy;this.controls=controls;
 }
 private Map<String,Object> identity(String bearer){
  if(bearer==null||!bearer.regionMatches(true,0,"Bearer ",0,7))
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Agent token required");
  try{return agents.resolve(bearer.substring(7));}
  catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Agent token");}
 }
 private Map<String,Object> discovery(String bearer){
  var agent=identity(bearer);
  controls.chargeAgent(String.valueOf(agent.get("id")),0);
  return agent;
 }
 @GetMapping("/datasources")
 Object sources(@RequestHeader(value="Authorization",required=false) String bearer){
  return catalog.datasources(String.valueOf(discovery(bearer).get("id")));
 }
 @GetMapping("/datasources/{id}/tables")
 Object tables(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable String id){
  return catalog.tables(String.valueOf(discovery(bearer).get("id")),id);
 }
 @GetMapping("/datasources/{id}/tables/{table}/columns")
 Object columns(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable String id,@PathVariable String table){
  return catalog.describeTable(String.valueOf(discovery(bearer).get("id")),id,table);
 }
 @PostMapping("/queries/validate")
 Object validate(@RequestHeader(value="Authorization",required=false) String bearer,@RequestBody Map<String,Object> request){
  if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"SQL validation request required");
  return sql.validate(discovery(bearer),request);
 }
 @PostMapping("/queries")
 Object query(@RequestHeader(value="Authorization",required=false) String bearer,@RequestBody Map<String,Object> request){
  if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"SQL query request required");
  try{return sql.execute(discovery(bearer),request);}
  catch(PolicyEngine.Pending pending){
   return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status","PENDING",
    "requestId",pending.requestId,"message","Approval required; retry identical SQL and params after approval"));
  }
 }
 @GetMapping("/requests/{id}")
 Object request(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable String id){
  return policy.status(discovery(bearer),id);
 }
}

@RestControllerAdvice(assignableTypes=AgentRestController.class)
class AgentRestErrors {
 @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
 ResponseEntity<Map<String,Object>> handle(ResponseStatusException ex){
  int status=ex.getStatusCode().value();
  String code=switch(status){
   case 400 -> "INVALID_ARGUMENT"; case 401 -> "UNAUTHORIZED";case 403 -> "ACCESS_DENIED";
   case 404 -> "NOT_FOUND";case 409 -> "CONFLICT";case 429 -> "RATE_LIMITED";
   case 502 -> "DATASOURCE_FAILURE";default -> "REQUEST_FAILED";
  };
  var response=ResponseEntity.status(ex.getStatusCode());
  if(status==429)response.header("Retry-After","60");
  return response.body(Map.of("status","ERROR","code",code,
   "message",Objects.toString(ex.getReason(),"Request failed")));
 }
}