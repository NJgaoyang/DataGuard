package io.github.njgaoyang.dataguard;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {
 @Test void identicalRequestHasDeterministicFingerprint(){
  String a=PolicyEngine.fingerprint("agent","source","orders",List.of("amount","id"),50,"id","123","amount","DESC");
  assertEquals(a,PolicyEngine.fingerprint("agent","source","orders",List.of("amount","id"),50,"id","123","amount","DESC"));
  assertEquals(64,a.length());
  assertEquals(a,PolicyEngine.fingerprint("agent","source","orders",List.of("id","amount"),50,"id","123","amount","DESC"));
  assertNotEquals(a,PolicyEngine.fingerprint("agent","source","orders",List.of("id","amount"),50,"id","123","amount","DESC","READ|10|||"));
 }
 @Test void alteredLimitFilterOrAgentMustNeverShareApproval(){
  String original=PolicyEngine.fingerprint("agent1","source","orders",List.of("amount"),50,"id","123",null,null);
  assertNotEquals(original,PolicyEngine.fingerprint("agent2","source","orders",List.of("amount"),50,"id","123",null,null));
  assertNotEquals(original,PolicyEngine.fingerprint("agent1","source","orders",List.of("amount"),51,"id","123",null,null));
  assertNotEquals(original,PolicyEngine.fingerprint("agent1","source","orders",List.of("amount"),50,"id","456",null,null));
  assertNotEquals(original,PolicyEngine.fingerprint("agent1","source","orders",List.of("secret"),50,"id","123",null,null));
 }
 @Test void identifiersAndPasswordsAreValidated(){
  assertEquals("orders_2026",Guard.identifier("orders_2026"));
  assertThrows(RuntimeException.class,()->Guard.identifier("users;DROP TABLE dg_users"));
  assertThrows(RuntimeException.class,()->Guard.identifier("users.name"));
  assertThrows(RuntimeException.class,()->Guard.password("123456"));
  assertDoesNotThrow(()->Guard.password("ComplexPass123!"));
 }
}
