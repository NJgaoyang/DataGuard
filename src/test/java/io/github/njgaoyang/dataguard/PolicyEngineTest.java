package io.github.njgaoyang.dataguard;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {
 @Test void queryFingerprintIsDeterministicAndIsolated(){
  String a=PolicyEngine.fingerprint("agent","source","orders",List.of("amount","id"),50,"sql: SELECT 1");
  assertEquals(64,a.length());
  assertEquals(a,PolicyEngine.fingerprint("agent","source","orders",List.of("id","amount"),50,"sql: SELECT 1"));
  assertNotEquals(a,PolicyEngine.fingerprint("agent","source","orders",List.of("id","amount"),50,"sql: SELECT 2"));
  assertNotEquals(a,PolicyEngine.fingerprint("other","source","orders",List.of("amount","id"),50,"sql: SELECT 1"));
  assertNotEquals(a,PolicyEngine.fingerprint("agent","source","orders",List.of("amount","id"),51,"sql: SELECT 1"));
  assertNotEquals(a,PolicyEngine.fingerprint("agent","source","orders",List.of("secret"),50,"sql: SELECT 1"));
 }
 @Test void identifiersAndPasswordsAreValidated(){
  assertEquals("orders_2026",Guard.identifier("orders_2026"));
  assertThrows(RuntimeException.class,()->Guard.identifier("users;DROP TABLE dg_users"));
  assertThrows(RuntimeException.class,()->Guard.identifier("users.name"));
  assertThrows(RuntimeException.class,()->Guard.password("123456"));
  assertDoesNotThrow(()->Guard.password("ComplexPass123!"));
 }
}