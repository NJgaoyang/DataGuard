package io.github.njgaoyang.dataguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
public class DataGuardApplication {
 public static void main(String[] args){SpringApplication.run(DataGuardApplication.class,args);}
 @Bean ApplicationRunner initializeAdmin(JdbcTemplate db,PasswordEncoder encoder){
  return args->{
   Integer users=db.queryForObject("SELECT COUNT(*) FROM dg_users",Integer.class);
   if(users!=null&&users==0){
    String password=System.getenv("DG_INITIAL_ADMIN_PASSWORD");
    Guard.password(password);
    db.update("INSERT INTO dg_users(id,username,display_name,password_hash,role_code,must_change_password) VALUES(?,?,?,?,'ADMIN',1)",
     java.util.UUID.randomUUID().toString(),"admin","系统管理员",encoder.encode(password));
   }
  };
 }
}