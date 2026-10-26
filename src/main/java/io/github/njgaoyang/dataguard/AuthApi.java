package io.github.njgaoyang.dataguard;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
class SecurityConfig {
 @Bean PasswordEncoder encoder(){return new BCryptPasswordEncoder(12);}
 @Bean UserDetailsService users(JdbcTemplate db){
  return username->{
   List<Map<String,Object>> r=db.queryForList("SELECT password_hash,role_code,enabled FROM dg_users WHERE username=?",username);
   if(r.size()!=1)throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Account not found");
   if(!((Boolean)r.get(0).get("enabled")))throw new DisabledException("Account disabled");
   return User.withUsername(username).password((String)r.get(0).get("password_hash"))
    .roles((String)r.get(0).get("role_code")).build();
  };
 }
 @Bean SecurityFilterChain security(HttpSecurity http,JdbcTemplate db,SecurityControls controls) throws Exception {
  http.csrf(csrf->csrf.ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/mcp"),PathPatternRequestMatcher.withDefaults().matcher("/api/v1/agent/**")))
   .authorizeHttpRequests(auth->auth
    .requestMatchers("/api/csrf","/mcp","/api/v1/agent/**","/actuator/health","/assets/**","/","/index.html","/favicon.ico",
      "/dashboard","/sources","/agents","/rules","/approvals","/records","/records/*","/agents/*","/sources/*","/rules/*","/approvals/*","/settings").permitAll()
    .anyRequest().authenticated())
   .exceptionHandling(e->e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
      PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
   .formLogin(login->login.loginProcessingUrl("/login")
     .successHandler((req,res,auth)->{controls.successfulLogin(auth.getName());res.setContentType("application/json");res.getWriter().write("{\"ok\":true}");})
     .failureHandler((req,res,ex)->{controls.failedLogin(req.getParameter("username"));res.sendError(401,"Invalid credentials");}).permitAll())
   .logout(logout->logout.logoutUrl("/logout").logoutSuccessHandler((req,res,auth)->res.setStatus(204))
     .invalidateHttpSession(true).deleteCookies("JSESSIONID"))
   .sessionManagement(s->s.sessionFixation(f->f.migrateSession()))
   .addFilterAfter(new InitialPasswordGuard(db),org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
   .addFilterBefore(new LoginLockFilter(controls),org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
  return http.build();
 }
}
class InitialPasswordGuard extends OncePerRequestFilter {
 private final JdbcTemplate db;
 InitialPasswordGuard(JdbcTemplate db){this.db=db;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String path=req.getRequestURI();
  Authentication auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
  if(path.startsWith("/api/")&&!"/api/csrf".equals(path)
      &&auth!=null&&auth.isAuthenticated()&&!(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)){
   java.util.List<Map<String,Object>> users=db.queryForList(
     "SELECT enabled,must_change_password,role_code FROM dg_users WHERE username=?",auth.getName());
   if(users.size()!=1){
    if(req.getSession(false)!=null)req.getSession(false).invalidate();
    res.sendError(401,"Account unavailable");return;
   }
   Map<String,Object> user=users.get(0);
   boolean valid=Boolean.TRUE.equals(user.get("enabled"));
   String role=String.valueOf(user.get("role_code"));
   boolean currentRole=auth.getAuthorities().stream().anyMatch(a->("ROLE_"+role).equals(a.getAuthority()));
   if(!valid||!currentRole){
    if(req.getSession(false)!=null)req.getSession(false).invalidate();
    res.sendError(401,"Account disabled or role changed");return;
   }
   if(!List.of("/api/session","/api/password").contains(path)&&Boolean.TRUE.equals(user.get("must_change_password"))){
    res.sendError(428,"Password change required");return;
   }
  }
  chain.doFilter(req,res);
 }
}
@RestController
class AuthApi {
 private final JdbcTemplate db; private final PasswordEncoder encoder; private final Audit audit;
 AuthApi(JdbcTemplate db,PasswordEncoder encoder,Audit audit){this.db=db;this.encoder=encoder;this.audit=audit;}
 @GetMapping("/api/csrf") Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"header",token.getHeaderName());}
 @GetMapping("/api/session") Map<String,Object> session(Authentication auth){
  if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  return db.queryForMap("SELECT username,display_name AS displayName,role_code AS roleCode,must_change_password AS mustChangePassword FROM dg_users WHERE username=?",auth.getName());
 }
 record ChangePassword(String currentPassword,String newPassword){}
 @PostMapping("/api/password") Map<String,Object> password(Authentication auth,@RequestBody ChangePassword input,HttpServletRequest req){
  if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Guard.password(input.newPassword());
  String hash=db.queryForObject("SELECT password_hash FROM dg_users WHERE username=?",String.class,auth.getName());
  if(!encoder.matches(input.currentPassword(),hash))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Current password incorrect");
  db.update("UPDATE dg_users SET password_hash=?,must_change_password=0 WHERE username=?",encoder.encode(input.newPassword()),auth.getName());
  audit.log(auth.getName(),"PASSWORD_CHANGED",null,"account","SUCCESS","");
  req.getSession().invalidate();
  return Map.of("reauthenticate",true);
 }
 @GetMapping("/api/users") List<Map<String,Object>> users(Authentication auth){
  Guard.admin(auth);
  return db.queryForList("SELECT id,username,display_name AS displayName,role_code AS roleCode,enabled,must_change_password AS mustChangePassword FROM dg_users ORDER BY created_at DESC");
 }
 record NewUser(String username,String displayName,String roleCode,String password){}
 @PostMapping("/api/users") Map<String,Object> create(Authentication auth,@RequestBody NewUser in){
  Guard.admin(auth);if(in==null||!in.username().matches("[A-Za-z0-9_.-]{3,64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Guard.password(in.password());String id=UUID.randomUUID().toString();
  db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code) VALUES(?,?,?,?,?)",
   id,in.username(),Guard.required(in.displayName(),100),encoder.encode(in.password()),Guard.role(in.roleCode()));
  audit.log(auth.getName(),"USER_CREATED",null,id,"SUCCESS","");
  return Map.of("id",id);
 }
 record UserUpdate(String displayName,String roleCode){}
 @PutMapping("/api/users/{id}") Map<String,Object> updateUser(Authentication auth,@PathVariable String id,@RequestBody UserUpdate in){
  Guard.admin(auth);String safe=Guard.id(id);
  if(in==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid user");
  String display=Guard.required(in.displayName(),100),role=Guard.role(in.roleCode());
  int updated=db.update("UPDATE dg_users SET display_name=?,role_code=? WHERE id=? AND username<>'admin' AND username<>?",display,role,safe,auth.getName());
  if(updated!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot edit protected user");
  audit.log(auth.getName(),"USER_UPDATED",null,safe,"SUCCESS","Profile and role updated");return Map.of("updated",true);
 }
 @DeleteMapping("/api/users/{id}")
 @org.springframework.transaction.annotation.Transactional
 Map<String,Object> deleteUser(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  List<Map<String,Object>> found=db.queryForList("SELECT username,enabled FROM dg_users WHERE id=? FOR UPDATE",safe);
  if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  String username=String.valueOf(found.get(0).get("username"));
  if("admin".equals(username)||username.equals(auth.getName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Protected account");
  Object state=found.get(0).get("enabled");
  if(state instanceof Number ? ((Number)state).intValue()!=0 : Boolean.TRUE.equals(state))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Disable user before deletion");
  Long refs=db.queryForObject("""
   SELECT (SELECT COUNT(*) FROM dg_agents WHERE owner_user_id=?) +
    (SELECT COUNT(*) FROM dg_rules WHERE approver_id=?) +
    (SELECT COUNT(*) FROM dg_requests WHERE owner_id=? OR approver_id=?)
   """,Long.class,safe,safe,safe,safe);
  if(refs!=null&&refs>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"User has Agent, rule, or approval history; disable instead");
  db.update("DELETE FROM dg_users WHERE id=?",safe);
  audit.log(auth.getName(),"USER_DELETED",null,safe,"SUCCESS","User deleted without references");
  return Map.of("deleted",true);
 }
 @GetMapping("/api/security/limits") Map<String,Integer> securityLimits(){
  return Map.of("maxRowsPerQuery",Math.max(1,Math.min(200,db.queryForObject(
   "SELECT COALESCE(MAX(setting_value),200) FROM dg_security_settings WHERE setting_key='max_rows_per_query'",Integer.class))));
 }
 @GetMapping("/api/security/settings") Map<String,Integer> securitySettings(Authentication auth){
  Guard.admin(auth);return Map.of(
   "agentCallsPerMinute",db.queryForObject("SELECT setting_value FROM dg_security_settings WHERE setting_key='agent_calls_per_minute'",Integer.class),
   "agentRowsPerMinute",db.queryForObject("SELECT setting_value FROM dg_security_settings WHERE setting_key='agent_rows_per_minute'",Integer.class),
   "maxRowsPerQuery",Math.max(1,Math.min(200,db.queryForObject("SELECT COALESCE(MAX(setting_value),200) FROM dg_security_settings WHERE setting_key='max_rows_per_query'",Integer.class))));
 }
 record SecuritySettings(Integer agentCallsPerMinute,Integer agentRowsPerMinute,Integer maxRowsPerQuery){}
 @PutMapping("/api/security/settings") Map<String,Object> changeSecuritySettings(Authentication auth,@RequestBody SecuritySettings body){
  Guard.admin(auth);
  if(body==null||body.agentCallsPerMinute()==null||body.agentRowsPerMinute()==null||
    body.agentCallsPerMinute()<1||body.agentCallsPerMinute()>60||
    body.agentRowsPerMinute()<1||body.agentRowsPerMinute()>3000||
    body.maxRowsPerQuery()==null||body.maxRowsPerQuery()<1||body.maxRowsPerQuery()>200)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Security quota must not exceed defaults");
  db.update("UPDATE dg_security_settings SET setting_value=? WHERE setting_key='agent_calls_per_minute'",body.agentCallsPerMinute());
  db.update("UPDATE dg_security_settings SET setting_value=? WHERE setting_key='agent_rows_per_minute'",body.agentRowsPerMinute());
  db.update("INSERT INTO dg_security_settings(setting_key,setting_value) VALUES('max_rows_per_query',?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)",body.maxRowsPerQuery());
  audit.log(auth.getName(),"SECURITY_SETTINGS_UPDATED",null,"agent-quotas","SUCCESS","Agent quota updated");
  return Map.of("updated",true);
 }
 @PostMapping("/api/users/{id}/toggle") Map<String,Object> toggle(Authentication auth,@PathVariable String id){
  Guard.admin(auth);String safe=Guard.id(id);
  int n=db.update("UPDATE dg_users SET enabled=NOT enabled WHERE id=? AND username<>'admin' AND username<>?",safe,auth.getName());
  if(n!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot modify this user");
  audit.log(auth.getName(),"USER_TOGGLED",null,safe,"SUCCESS","");
  return Map.of("updated",true);
 }
 @GetMapping("/api/approvers") List<Map<String,Object>> approvers(Authentication auth){
  return db.queryForList("SELECT id,username,display_name AS displayName FROM dg_users WHERE enabled=1 AND must_change_password=0 AND role_code IN ('ADMIN','APPROVER') ORDER BY display_name");
 }
}
