package io.github.njgaoyang.dataguard;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlAuditTemplateTest {
 @Test void suppressesSensitiveQuotedStringsNumericsAndRetainsSchema(){
  String raw="SELECT order_id, amount FROM orders WHERE status = 'SECRET-PHRASE' AND amount > 98765.43 LIMIT 25";
  String safe=SqlAuditTemplate.redact(raw);
  assertTrue(safe.contains("SELECT order_id, amount FROM orders"));
  assertTrue(safe.contains("status = '[REDACTED]'"));
  assertFalse(safe.contains("SECRET-PHRASE"));
  assertFalse(safe.contains("98765.43"));
  assertFalse(safe.contains("LIMIT 25"));
  assertTrue(safe.contains("LIMIT ?"));
 }
 @Test void handlesEscapedQuotesAndNumberedColumns(){
  String raw="SELECT metric2026 FROM orders WHERE name='ab''secret' AND numeric_key=1e10 LIMIT 3";
  String safe=SqlAuditTemplate.redact(raw);
  assertTrue(safe.contains("metric2026"));
  assertFalse(safe.contains("secret"));
  assertFalse(safe.contains("1e10"));
  assertTrue(safe.contains("numeric_key=?"));
 }
 @Test void neverReplacesBoundValuesOrExposesTheirContent(){
  String sql="SELECT COUNT(*) AS total FROM orders WHERE phone=? AND amount>? LIMIT 50";
  String safe=SqlAuditTemplate.redact(sql);
  assertTrue(safe.contains("phone=?"));
  assertTrue(safe.contains("amount>?"));
  assertTrue(safe.contains("LIMIT ?"));
  assertThrows(IllegalArgumentException.class,()->SqlAuditTemplate.redact("x".repeat(12001)));
 }
}
