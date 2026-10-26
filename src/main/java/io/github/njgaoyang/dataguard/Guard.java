package io.github.njgaoyang.dataguard;

import java.util.regex.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

final class Guard {
 private Guard(){}
 static void admin(Authentication auth){
  if(auth==null||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator permission required");
 }
 static void reviewer(Authentication auth){
  if(auth==null||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_APPROVER")||a.getAuthority().equals("ROLE_ADMIN")))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Approver permission required");
 }
 static String id(String value){
  try{return UUID.fromString(value).toString();}
  catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid UUID");}
 }
 static String identifier(String value){
  if(value==null||!Pattern.matches("[A-Za-z][A-Za-z0-9_]{0,63}",value))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid SQL identifier");
  return value;
 }
 static void password(String password){
  if(password==null||password.length()<12||password.length()>72||
   !password.matches("(?s).*[A-Z].*")||!password.matches("(?s).*[a-z].*")||
   !password.matches("(?s).*[0-9].*")||!password.matches("(?s).*[^A-Za-z0-9].*"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password requires 12-72 chars, upper/lowercase, number and symbol");
 }
 static String role(String value){
  if(!java.util.Set.of("ADMIN","APPROVER","READER").contains(value))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid role");
  return value;
 }
 static String required(String s,int max){
  if(s==null||s.isBlank()||s.length()>max)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid required text");
  return s.strip();
 }
 static String nullableId(String id){return id==null||id.isBlank()?null:id(id);}
}