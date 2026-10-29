#!/usr/bin/env bash
# Latency per route from the request-timing log lines.
# Usage: railway logs --environment development --service <id> --lines 5000 | scripts/latency-report.sh
#        scripts/latency-report.sh < shifa-backend.log
set -euo pipefail
grep '"evt":"http"' | \
  sed -E 's/.*"evt":"http","method":"([A-Z]+)","route":"([^"]*)","status":([0-9]+),"ms":([0-9]+)\}.*/\1 \2 \3 \4/' | \
  sort -k1,1 -k2,2 -k4,4n | \
  { printf "%-7s %-55s %6s %7s %7s %7s %5s\n" "METHOD" "ROUTE" "COUNT" "P50" "P95" "MAX" "5xx"; awk '
    { key=$1" "$2; n[key]++; v[key, n[key]]=$4; if ($3>=500) err[key]++; if ($4>max[key]) max[key]=$4 }
    END {
      for (k in n) {
        c=n[k]; p50=v[k, int((c+1)*0.50)]; p95=v[k, int((c+1)*0.95)]; if (p95=="") p95=v[k, c];
        split(k, parts, " "); printf "%-7s %-55s %6d %7d %7d %7d %5d\n", parts[1], parts[2], c, p50, p95, max[k], err[k]+0;
      }
    }' | sort -k5,5nr; }
