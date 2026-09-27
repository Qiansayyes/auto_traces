# Magisk service.d keep-alive（可选，Module C）

机队包名必须是 **`com.autotraces.app`**（`auto_traces` / android-simple），不要写成 `com.videoded.companion`。

## 安装

```sh
adb push android-simple/ops/magisk/service.d/99-autotraces-keepalive.sh /data/adb/service.d/
adb shell su -c 'chmod 755 /data/adb/service.d/99-autotraces-keepalive.sh'
adb reboot
```

## 行为

- 开机约 45s 后循环（默认 60s）
- `pidof com.autotraces.app` 为空 → `am start-foreground-service` 拉起 VideoDed + Worker
- 进程在但 `127.0.0.1:18088` 不通 → 只重拉 VideoDed FGS

应用内已有 `BootReceiver` + Alarm Watchdog；本脚本仅作 OEM 强杀 / force-stop 后的 root 兜底。
