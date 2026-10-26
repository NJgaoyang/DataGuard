#!/usr/bin/env bash
# Once-per-minute host + application verification (no passwords, tokens or query values).
set -uo pipefail
ok=1
if ! systemctl is-active --quiet dataguard-new.service;then ok=0; echo 'dataguard service inactive';fi
if ! systemctl is-active --quiet dataguard-caddy.service;then ok=0; echo 'HTTPS proxy inactive';fi
if ! curl --fail --silent --show-error --max-time 4 http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"';then ok=0; echo 'application health DOWN';fi
if ! docker inspect dataguard-mysql-test --format '{{.State.Running}}' 2>/dev/null | grep -q true;then ok=0; echo 'MySQL stopped';fi
if ! docker inspect dg-v2-starrocks-dev --format '{{.State.Running}}' 2>/dev/null | grep -q true;then ok=0; echo 'StarRocks stopped';fi
space=$(df -P /opt/dataguard | awk 'NR==2 {gsub(/%/,"",$5);print $5}')
if [[ "${space:-100}" -ge 85 ]];then ok=0; echo "Disk utilization over threshold: ${space}%";fi
if [[ $ok -eq 1 ]];then
 logger -t dataguard-monitor 'INFO all services healthy'
 echo 'HEALTHY'
else
 logger -p daemon.err -t dataguard-monitor 'ALERT service or disk threshold breached'
 echo 'ALERT'
 exit 1
fi
