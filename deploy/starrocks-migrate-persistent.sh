#!/usr/bin/env bash
# One-time live migration: StarRocks all-in-one container -> durable host data.
# Never removes the original container; rollback stays available by original backup name.
set -Eeuo pipefail
old="dg-v2-starrocks-dev"
backup="dg-v2-starrocks-prepersist-20261026"
root="/var/lib/dataguard-starrocks"
image="starrocks/allin1-ubuntu:3.5.21"
[[ $(id -u) -eq 0 ]] || { echo 'Must run as root'; exit 1; }
docker inspect "$old" >/dev/null
if docker inspect "$backup" >/dev/null 2>&1; then
  echo 'Backup container already exists; refusing repeated migration'; exit 1
fi
if [[ -e "$root/COMPLETE" ]];then echo 'Already migrated'; exit 1;fi
old_count=$(docker exec "$old" mysql -uroot -h127.0.0.1 -P9030 -N -e "SELECT COUNT(*) FROM dg_p0_demo.orders" | tr -d '\r')
[[ "$old_count" == 3 ]]|| { echo 'Original demo row count unexpected'; exit 1; }
mkdir -p "$root"/{fe-meta,be-storage,fe-conf,be-conf}
chmod 0700 "$root"
echo 'Step 1: stopping original FE/BE to copy consistent metadata and rowsets'
docker stop -t 80 "$old" >/dev/null
echo 'Step 2: copying FE metadata, BE rowsets and configuration (no deletion)'
docker cp "$old":/data/deploy/starrocks/fe/meta/. "$root/fe-meta/"
docker cp "$old":/data/deploy/starrocks/be/storage/. "$root/be-storage/"
docker cp "$old":/data/deploy/starrocks/fe/conf/. "$root/fe-conf/"
docker cp "$old":/data/deploy/starrocks/be/conf/. "$root/be-conf/"
[[ -s "$root/fe-meta/image/ROLE" ]] || [[ -d "$root/fe-meta/image" ]] || { echo 'FE metadata empty'; docker start "$old"; exit 1; }
[[ -d "$root/be-storage/data" ]]||[[ -d "$root/be-storage/meta" ]]|| { echo 'BE data missing'; docker start "$old"; exit 1; }
sync
echo 'Step 3: retain stopped rollback container and create persisted replacement'
docker rename "$old" "$backup"
rollback(){
 echo 'MIGRATION CHECK FAILED: preserving copied data and restoring original container'
 docker stop -t 20 "$old" >/dev/null 2>&1 || true
 if docker inspect "$old" >/dev/null 2>&1;then docker rename "$old" "dg-v2-starrocks-failed-$(date +%s)"||true;fi
 docker rename "$backup" "$old" ||true
 docker start "$old" >/dev/null || true
}
trap rollback ERR
docker run -d --name "$old" --restart unless-stopped \
 --shm-size 64m --workdir /data/deploy \
 -p 127.0.0.1:9030:9030 -p 127.0.0.1:8030:8030 \
 -v "$root/fe-meta:/data/deploy/starrocks/fe/meta" \
 -v "$root/be-storage:/data/deploy/starrocks/be/storage" \
 -v "$root/fe-conf:/data/deploy/starrocks/fe/conf" \
 -v "$root/be-conf:/data/deploy/starrocks/be/conf" \
 "$image" /bin/sh -c ./entrypoint.sh >/dev/null
echo 'Step 4: waiting for fresh FE/BE with old data'
ok=0
for i in $(seq 1 55);do
 if new_count=$(docker exec "$old" mysql -uroot -h127.0.0.1 -P9030 -N -e "SELECT COUNT(*) FROM dg_p0_demo.orders" 2>/dev/null);then
  if [[ "$new_count" == "$old_count" ]];then ok=1;break;fi
 fi
 sleep 3
done
[[ "$ok" -eq 1 ]] || { echo 'StarRocks did not reopen original data within checks'; false; }
docker exec "$old" mysql -uroot -h127.0.0.1 -P9030 -N -e "SHOW DATABASES" | grep -qx 'dg_p0_demo'
touch "$root/COMPLETE"
chmod 0600 "$root/COMPLETE"
trap - ERR
echo 'SUCCESS: StarRocks preserved old FE metadata and BE rowsets on host mounts'
echo "DEMO_ROWS=$new_count"
docker inspect "$old" --format '{{range .Mounts}}{{.Destination}};{{end}}'
echo "ROLLBACK_CONTAINER=$backup (stopped, retained)"
