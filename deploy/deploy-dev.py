#!/usr/bin/env python3
"""Idempotent dev-host provisioning: isolated metadata DB, TLS trust, systemd service.
Run as root AFTER building frontend and Maven jar. Never prints passwords.
Not a production deployment script.
"""
import base64
import os
import pathlib
import secrets
import subprocess
import sys

if os.geteuid()!=0:
 sys.exit("Run as root")
src=pathlib.Path("/opt/dataguard")
if not (src/"target/dataguard-1.0.0-SNAPSHOT.jar").is_file():
 sys.exit("Missing Maven build; build frontend first, then mvn package")
cfg=pathlib.Path("/etc/dataguard-new")
cfg.mkdir(mode=0o700,parents=True,exist_ok=True)
os.chmod(cfg,0o700)
envfile=cfg/"standalone.env"
passwordfile=cfg/"mysql-app-password"
keyfile=cfg/"aes-key"
bootstrap=pathlib.Path("/root/dataguard-new-initial-admin.txt")

def secret(path, generator):
 if not path.exists():
  with path.open("x") as f:
   f.write(generator()+"\n")
  path.chmod(0o600)
 return path.read_text().strip()

password=secret(passwordfile,lambda: secrets.token_hex(28))
master=secret(keyfile,lambda: base64.b64encode(secrets.token_bytes(32)).decode("ascii"))
first=secret(bootstrap,lambda: "Aa1!"+secrets.token_urlsafe(20))
sql="""CREATE DATABASE IF NOT EXISTS dataguard_v1 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'dg_v1_app'@'%' IDENTIFIED BY '%s' REQUIRE SSL;
ALTER USER 'dg_v1_app'@'%' IDENTIFIED BY '%s' REQUIRE SSL;
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON dataguard_v1.* TO 'dg_v1_app'@'%%';
FLUSH PRIVILEGES;"""%(password,password)
subprocess.run([
 "docker","exec","-i","dataguard-mysql-test","sh","-c",
 'MYSQL_PWD="$(cat /run/secrets/mysql-root-password)" exec mysql -uroot'
],input=sql,text=True,check=True,stdout=subprocess.DEVNULL)
store=cfg/"mysql-trust.p12"
if not store.exists():
 subprocess.run([
  "/opt/dataguard-toolchain/java21/bin/keytool","-importcert","-noprompt",
  "-alias","dataguard-local-mysql-ca","-file",
  "/opt/mcp-dataguard-work/secure-test-secrets/mysql84/tls/ca.pem",
  "-keystore",str(store),"-storetype","PKCS12","-storepass","changeit"
 ],check=True,stdout=subprocess.DEVNULL)
store.chmod(0o644)
values={
 "DG_BIND":"127.0.0.1",
 "DG_PORT":"8080",
 "DG_SECURE_COOKIE":"false",
 "DG_MYSQL_URL":"jdbc:mysql://127.0.0.1:3307/dataguard_v1?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/dataguard-new/mysql-trust.p12&trustCertificateKeyStorePassword=changeit",
 "DG_MYSQL_USER":"dg_v1_app",
 "DG_MYSQL_PASSWORD":password,
 "DG_ENCRYPTION_KEY":master,
 "DG_INITIAL_ADMIN_PASSWORD":first,
 "DG_ALLOWED_SOURCE_HOSTS":"127.0.0.1,localhost"
}
with envfile.open("w") as f:
 for k,v in values.items():
  f.write(k+"="+v+"\n")
envfile.chmod(0o600)
subprocess.run(["install","-d","-m","0700","-o","dataguard","-g","dataguard","/var/lib/dataguard-new"],check=True)
subprocess.run(["install","-m","0644",str(src/"target/dataguard-1.0.0-SNAPSHOT.jar"),str(src/"app.jar")],check=True)
subprocess.run(["install","-m","0644",str(src/"deploy/dataguard-new.service"),"/etc/systemd/system/dataguard-new.service"],check=True)
subprocess.run(["systemctl","daemon-reload"],check=True)
subprocess.run(["systemctl","enable","dataguard-new.service"],check=True)
subprocess.run(["systemctl","restart","dataguard-new.service"],check=True)
print("DataGuard new project installed; local health endpoint http://127.0.0.1:8080/actuator/health")
print("Root-only first-login password file:",bootstrap)
print("Database metadata: dataguard_v1; preserved all previous MySQL/StarRocks data")
