package io.github.njgaoyang.dataguard;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Enable vetted SQL functions without changing DataGuard Java code. */
@RestController
@RequestMapping("/api/sql/functions")
class SqlFunctionApi {
 private final JdbcTemplate db;
 private final Audit audit;
 SqlFunctionApi(JdbcTemplate db,Audit audit){this.db=db;this.audit=audit;}
 @GetMapping List<Map<String,Object>> list(Authentication auth){
  Guard.admin(auth);
  return db.queryForList("SELECT function_name AS functionName,enabled FROM dg_sql_functions ORDER BY function_name");
 }
 record FunctionPolicy(String functionName,boolean enabled){}
 @PutMapping Map<String,Object> update(Authentication auth,@RequestBody FunctionPolicy body){
  Guard.admin(auth);
  if(body==null||body.functionName()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Function required");
  String name=body.functionName().strip().toUpperCase(Locale.ROOT);
  if(!name.matches("[A-Z][A-Z0-9_]{0,63}")||Set.of("SLEEP","BENCHMARK","LOAD_FILE",
    "GET_LOCK","RELEASE_LOCK","IS_FREE_LOCK","IS_USED_LOCK","MASTER_POS_WAIT","UUID_SHORT",
    "CONNECTION_ID","CURRENT_USER","SESSION_USER","USER","DATABASE","SCHEMA","VERSION","SYS_EVAL").contains(name))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Forbidden SQL function");
  db.update("INSERT INTO dg_sql_functions(function_name,enabled) VALUES(?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",
    name,body.enabled());
  audit.log(auth.getName(),"SQL_FUNCTION_POLICY",null,name,"SUCCESS",body.enabled()?"ENABLED":"DISABLED");
  return Map.of("functionName",name,"enabled",body.enabled());
 }
}