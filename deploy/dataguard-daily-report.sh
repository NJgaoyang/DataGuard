#!/usr/bin/env bash
set -euo pipefail
dir=/var/lib/dataguard-new/monitor
hist="$dir/availability.tsv"
[[ -s "$hist" ]] || exit 0
previous=$(date -u -d 'yesterday' +%F)
awk -F '\t' -v day="$previous" '$1 ~ ("^"day) {all++; if($2=="OK") good++} END {if(all>0) printf "%s\t%d\t%d\t%.2f%%\n",day,all,good,100*good/all}' "$hist" >> "$dir/daily.tsv"
chmod 0600 "$dir/daily.tsv"
