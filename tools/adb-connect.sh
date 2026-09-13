#!/bin/bash
# ============================================================
# adb 无线连接助手（副机 vivo V2131A / PD2131）
# 用法：  bash adb-connect.sh          → 输出可用 SERIAL 并保持连接
#         bash adb-connect.sh -q       → 只输出 SERIAL（静默）
# 兜底顺序：已有设备 → 固定端口 5555/5556 → mDNS 发现 → 端口扫描(30000-50000)
# 环境变量：TG_PHONE_IP 可覆盖手机 IP（默认 10.112.227.105）
# ============================================================
ADB="C:/Users/Hasee/.workbuddy/tools/platform-tools/adb.exe"
ADB_WIN='C:\Users\Hasee\.workbuddy\tools\platform-tools\adb.exe'
# ⚠️ 手机 IP 会变（换 WiFi / DHCP 续租）→ **默认从 mDNS 读当前地址**；
#    也支持 TG_PHONE_IP 手动覆盖（无 mDNS 时用）
IP="${TG_PHONE_IP:-}"
QUIET=0
[ "$1" = "-q" ] && QUIET=1
log() { [ "$QUIET" = "0" ] && echo "$@" >&2; return 0; }

# ── 1) 确保 adb server 常驻 ─────────────────────────────────
# 沙箱里 adb 客户端拉起的 server 会随命令结束被回收 → 必须用 WMI 方式
# （父进程 WmiPrvSE 持久，server 脱离沙箱存活，监听默认 5037）
if "$ADB" devices 2>&1 | grep -q "daemon not running"; then
  log "· adb server 不在，用 WMI 拉起常驻实例…"
  wmic process call create "\"$ADB_WIN\" start-server" >/dev/null 2>&1
  sleep 3
fi

pick() { "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}'; }

# ── 2) 已有可用设备直接用 ───────────────────────────────────
SER=$(pick)
[ -n "$SER" ] && { log "✓ 已连接：$SER"; echo "$SER"; exit 0; }

# ── 3) 固定端口（已 tcpip 固化过；手机重启后失效） ──────────
for P in 5555 5556; do
  log "· 尝试固定端口 $P …"
  timeout 12 "$ADB" connect "$IP:$P" >/dev/null 2>&1
  SER=$(pick); [ -n "$SER" ] && { log "✓ 连上：$SER"; echo "$SER"; exit 0; }
done

# ── 4) mDNS 发现：**直接取 IP:端口**（手机 IP 会变，这一步是关键兜底） ──
MDNS_OUT=$("$ADB" mdns services 2>/dev/null)
# 优先 _adb._tcp（tcpip 5555 固定端口），其次 _adb-tls-connect._tcp
MDNS_ADDR=$(echo "$MDNS_OUT" | awk '/_adb\._tcp/ {print $3; exit}')
[ -z "$MDNS_ADDR" ] && MDNS_ADDR=$(echo "$MDNS_OUT" | awk '/_adb-tls-connect\._tcp/ {print $3; exit}')
if [ -n "$MDNS_ADDR" ]; then
  log "· mDNS 发现当前地址：$MDNS_ADDR"
  # 顺带把 IP 更新成当前值（后面扫描兜底也要用）
  [ -z "$IP" ] && IP="${MDNS_ADDR%%:*}"
  timeout 15 "$ADB" connect "$MDNS_ADDR" >/dev/null 2>&1
  SER=$(pick); [ -n "$SER" ] && { log "✓ 连上：$SER"; echo "$SER"; exit 0; }
fi

# 无 IP 又无 mDNS → 没法继续
if [ -z "$IP" ]; then
  log "✗ 手机 IP 未知且 mDNS 无结果。可显式指定：TG_PHONE_IP=x.x.x.x bash adb-connect.sh"
  exit 1
fi

# ── 5) 端口扫描兜底（无线调试端口每次开关都会变） ───────────
log "· 端口漂移，扫描 $IP:30000-50000 …"
SCAN_PY="$(dirname "$0")/../.tmp-data/adb_portscan.py"
if [ ! -f "$SCAN_PY" ]; then
  SCAN_PY="D:/idol/X/Z/Q/资料/其他/For-workBoddy-work/task-guide/.tmp-data/adb_portscan.py"
fi
PORTS=$("C:/Users/Hasee/.workbuddy/binaries/python/versions/3.13.12/python.exe" "$SCAN_PY" "$IP" 30000 50000 2>/dev/null \
        | sed -n 's/^开放端口: \[\(.*\)\]$/\1/p' | tr -d ' ' | tr ',' ' ')
for P in $PORTS; do
  log "· 试连扫描到的 $P …"
  timeout 12 "$ADB" connect "$IP:$P" >/dev/null 2>&1
  SER=$(pick); [ -n "$SER" ] && { log "✓ 连上：$SER"; echo "$SER"; exit 0; }
done

log "✗ 未能连上副机。请确认：① 无线调试已开 ② 手机与本机同网段 ③ 配对未被清除"
exit 1
