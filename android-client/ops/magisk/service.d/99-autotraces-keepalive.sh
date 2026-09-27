#!/system/bin/sh
# Magisk service.d fallback keep-alive for com.autotraces.app (Module C optional)
# Install: copy to /data/adb/service.d/99-autotraces-keepalive.sh && chmod 755
# Targets package com.autotraces.app — NOT com.videoded.companion

PKG="com.autotraces.app"
VIDEO_DED_SVC="${PKG}/.videoded.VideoDedDeviceService"
WORKER_SVC="${PKG}/.WorkerService"
PORT=18088
INTERVAL=60

log() {
  echo "autotraces-keepalive: $*" >/dev/kmsg 2>/dev/null || true
}

port_up() {
  # toybox nc may be missing; fallback to /dev/tcp via sh when available
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 1 127.0.0.1 "$PORT" >/dev/null 2>&1
    return $?
  fi
  # Android mksh / toybox often lack /dev/tcp; treat missing probe as "unknown → start"
  return 1
}

start_fgs() {
  am start-foreground-service -n "$1" >/dev/null 2>&1 || \
    am startservice -n "$1" >/dev/null 2>&1 || true
}

# Wait for boot + package ready
sleep 45
log "loop start pkg=$PKG port=$PORT"

while true; do
  if ! pidof "$PKG" >/dev/null 2>&1; then
    log "pid missing → start VideoDed + Worker"
    start_fgs "$VIDEO_DED_SVC"
    start_fgs "$WORKER_SVC"
  elif ! port_up; then
    log "port $PORT down → restart VideoDed FGS"
    start_fgs "$VIDEO_DED_SVC"
  fi
  sleep "$INTERVAL"
done
