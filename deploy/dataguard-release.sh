#!/usr/bin/env bash
# Controlled DataGuard monolithic-JAR deployment and reversible rollback.
# Usage: dataguard-release.sh deploy /path/to/verified.jar COMMIT_SHA
#        dataguard-release.sh rollback
set -Eeuo pipefail
[[ $(id -u) -eq 0 ]] || { echo 'root required'; exit 1; }
exec 9>/run/lock/dataguard-release.lock
flock -n 9 || { echo 'Release already in progress'; exit 1; }
app=/opt/dataguard/app.jar
release=/var/lib/dataguard-new/releases
mkdir -p "$release"
chmod 0700 "$release"
verify(){
 local success=0
 for try in $(seq 1 24); do
  if systemctl is-active --quiet dataguard-new.service &&
     curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"';then success=1;break;fi
  sleep 2
 done
 [[ $success -eq 1 ]]
}
rollback(){
 [[ -s "$release/previous.jar" ]] || { echo 'No saved predecessor'; exit 1;}
 cp "$release/previous.jar" "$release/.candidate.jar"
 chmod 0644 "$release/.candidate.jar"
 mv -f "$release/.candidate.jar" "$app"
 systemctl restart dataguard-new.service
 verify
 if [[ -s "$release/previous-git-sha" ]]; then
  cp "$release/previous-git-sha" "$release/current-git-sha"
else
  printf '%s\n' "unverified-previous" > "$release/current-git-sha"
fi
printf '%s\\tROLLBACK\\t%s\n' "$(date -u +%Y%m%dT%H%M%SZ)" "$(sha256sum "$app" | awk '{print $1}')" >> "$release/deployments.tsv"
echo 'Rollback successful'
}
case "${1:-}" in
 rollback)
  rollback
  ;;
 status)
  echo "Release Git SHA: $(cat "$release/current-git-sha" 2>/dev/null || echo unknown)"
  sha256sum "$app"
  systemctl is-active dataguard-new.service
  ;;
 deploy)
  [[ $# -eq 3 && -s "$2" ]] || { echo 'Usage: deploy JAR COMMIT_SHA'; exit 2;}
  [[ "$3" =~ ^[a-f0-9]{40}$ ]] || { echo 'Commit must be 40-character Git SHA'; exit 2;}
  expected=$(sha256sum "$2" | awk '{print $1}')
  test -r "$app"
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  cp "$app" "$release/previous.jar"
  if [[ -s "$release/current-git-sha" ]]; then
    cp "$release/current-git-sha" "$release/previous-git-sha"
  else
    printf '%s\n' "unverified-previous" > "$release/previous-git-sha"
  fi
  cp "$2" "$release/.candidate.jar"
  chmod 0600 "$release/previous.jar"
  chmod 0644 "$release/.candidate.jar"
  actual=$(sha256sum "$release/.candidate.jar" | awk '{print $1}')
  [[ "$expected" == "$actual" ]] || { echo 'Staging checksum mismatch'; exit 1;}
  chmod 0644 "$release/.candidate.jar"
  mv -f "$release/.candidate.jar" "$app"
  if ! systemctl restart dataguard-new.service || ! verify;then
   echo 'Health gate failed: restoring last known-good JAR'
   rollback
   exit 1
  fi
  printf '%s\t%s\t%s\n' "$stamp" "$3" "$actual" >> "$release/deployments.tsv"
  printf '%s\n' "$3" > "$release/current-git-sha"
  cp "$app" "$release/release-$stamp.jar"
  echo "Release successful SHA=$3 binary=$actual"
  ;;
 *)echo 'Usage: deploy JAR COMMIT_SHA | rollback'; exit 2;;
esac
