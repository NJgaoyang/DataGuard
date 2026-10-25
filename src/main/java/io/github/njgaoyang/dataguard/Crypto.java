package io.github.njgaoyang.dataguard;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
class Crypto {
 private final byte[] key;
 Crypto(){
  String raw=System.getenv("DG_ENCRYPTION_KEY");
  if(raw==null||raw.isBlank())throw new IllegalStateException("DG_ENCRYPTION_KEY must be configured as 32 random bytes in base64");
  try{key=Base64.getDecoder().decode(raw);}catch(IllegalArgumentException ex){throw new IllegalStateException("Invalid encryption key format");}
  if(key.length!=32)throw new IllegalStateException("Encryption key must be 256 bits");
 }
 String encrypt(String plain){
  try{
   byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
   return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new IllegalStateException("Credential encryption failure");}
 }
 String decrypt(String value){
  try{
   String[] parts=value.split(":",-1);
   if(parts.length!=2)throw new IllegalArgumentException();
   byte[] iv=Base64.getDecoder().decode(parts[0]);
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
   return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
  }catch(Exception e){throw new IllegalStateException("Unable to decrypt datasource credential");}
 }
}
