package io.github.njgaoyang.dataguard;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SourceConnectionSecurityTest {
 private static Map<String,Object> source(String type,String host){
  return Map.of("host",host,"port",9030,"databaseName","analytics","type",type);
 }
 @Test void starrocksLoopbackPlaintextMustBeExplicitlyEnabled(){
  SourceApi strict=new SourceApi(null,null,null,"127.0.0.1,db.internal",false,"","");
  assertTrue(strict.jdbcUrlFor(source("STARROCKS","127.0.0.1")).contains("sslMode=VERIFY_IDENTITY"));
  SourceApi dev=new SourceApi(null,null,null,"127.0.0.1,db.internal",true,"","");
  assertTrue(dev.jdbcUrlFor(source("STARROCKS","127.0.0.1")).contains("sslMode=DISABLED"));
  assertTrue(dev.jdbcUrlFor(source("MYSQL","127.0.0.1")).contains("sslMode=VERIFY_IDENTITY"));
  assertTrue(dev.jdbcUrlFor(source("STARROCKS","db.internal")).contains("sslMode=VERIFY_IDENTITY"));
  assertThrows(RuntimeException.class,()->dev.jdbcUrlFor(source("STARROCKS","other.internal")));
 }
 @Test void mysqlCertificateStoreRemainsConfigured(){
  SourceApi s=new SourceApi(null,null,null,"127.0.0.1",false,
    "file:/etc/example/mysql-ca.p12","changeit");
  String url=s.jdbcUrlFor(source("MYSQL","127.0.0.1"));
  assertTrue(url.contains("sslMode=VERIFY_IDENTITY"));
  assertTrue(url.contains("trustCertificateKeyStoreUrl=file:/etc/example/mysql-ca.p12"));
 }
}
