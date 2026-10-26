package io.github.njgaoyang.dataguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CsrfLoginIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate db;
 @Autowired ObjectMapper json;
 @Autowired PasswordEncoder encoder;

 @Test @Transactional
 void loginRotatesCsrfAndFirstPasswordChangeMustUseFreshToken() throws Exception {
  String oldPassword="CurrentTest12!Secure";
  String newPassword="ChangedTest34!Secure";
  String id=UUID.randomUUID().toString(),username="csrf_"+id.substring(0,8);
  db.update("""
    INSERT INTO dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password)
    VALUES(?,?,?,?,'READER',1,1)
    """,id,username,username,encoder.encode(oldPassword));

  MvcResult before=mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
  String original=json.readTree(before.getResponse().getContentAsString()).path("token").asText();
  MockHttpSession session=(MockHttpSession)before.getRequest().getSession(false);
  assertNotNull(session);
  MvcResult signedIn=mvc.perform(post("/login").session(session)
      .param("username",username).param("password",oldPassword)
      .header("X-CSRF-TOKEN",original))
    .andExpect(status().isOk()).andReturn();
  MockHttpSession afterLogin=(MockHttpSession)signedIn.getRequest().getSession(false);
  assertNotNull(afterLogin);

  MvcResult token=mvc.perform(get("/api/csrf").session(afterLogin))
    .andExpect(status().isOk()).andReturn();
  String rotated=json.readTree(token.getResponse().getContentAsString()).path("token").asText();
  assertNotEquals(original,rotated,"Spring Security must rotate token after login");

  String change=json.writeValueAsString(java.util.Map.of(
    "currentPassword",oldPassword,"newPassword",newPassword));
  mvc.perform(post("/api/password").session(afterLogin)
    .header("X-CSRF-TOKEN",original)
    .contentType("application/json").content(change))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/password").session(afterLogin)
    .header("X-CSRF-TOKEN",rotated)
    .contentType("application/json").content(change))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.reauthenticate").value(true));

  assertEquals(0,db.queryForObject("SELECT must_change_password FROM dg_users WHERE id=?",Integer.class,id));
  String hash=db.queryForObject("SELECT password_hash FROM dg_users WHERE id=?",String.class,id);
  assertTrue(encoder.matches(newPassword,hash));
 }
}
