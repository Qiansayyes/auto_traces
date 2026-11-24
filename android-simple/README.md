# Android 客户端（主力）

> 当前版本号高于 `android-client/`；服务端 `/api/apk/version` 会在两者中取更高 `versionCode`。总览见仓库根 [`README.md`](../README.md)。

## 当前包含

- 设备绑定页面
- `SharedPreferences` 保存 `device_id` 与 `alias`
- 启动后自动上报 `online`
- 自动拉取云端配置，失败则使用默认配置
- 轮询启动指令 `/api/check_start`
- 运行中按随机 15-35 分钟间隔拉取用户名
- 实时日志页面
- 结果上报失败后，按 5 秒间隔最多重试 3 次，仍失败则进入内存重试队列

## 模式说明（TraceExecutor）

当前访问类模式在“点击用户作品后”的流程差异如下：

- `涨粉模式`（run_mode=`growth`）
  - 在粉丝主页随机点击左/中/右作品位（日志带 `post_slot=left|middle|right`）；
  - 作品位点击高度当前统一使用 `0.80×屏幕高`；
  - 若命中左列，直接视为进入成功并开始互动；
  - 若命中中/右列，则先做“点击前基准帧 vs 点击后帧”的前后帧差判定；点击后等待 `growth_post_check_after_tap_delay_ms`（默认 1000ms），失败后再按 `growth_post_second_check_delay_ms`（默认 650ms）进行二次判定；
  - 中/右列路径下不启用评论框 OCR 兜底；
  - 二次判定失败后：先返回一次，再用 OCR 统计 `Follow` 数量；`Follow < 4` 直接判定页面错乱，重启 TikTok 后台并跳过当前条目；`Follow >= 4` 认为仍在正常 Followers 列表，继续下一条；
  - 粉丝列表处理采用 OCR 识别 `Follow` 行并估算行高后滚动；每轮约滑过 7 行，最多 10 轮，命中“列表到底”标记或 `Follow` 计数为 0 时提前结束；
  - 进入作品页后使用配置项 `video_stay_min` / `video_stay_max` 随机停留再执行互动；
  - 作品互动阶段按当前配置决定执行哪些动作：
    - `action_single=true` 时，在「点赞 / 评论 / 收藏 / 转发」中随机选择 1 个可执行动作；
    - `action_single=false` 时，按配置概率和每日上限筛出可执行动作后依次执行；
    - 动作之间会按 `action_delay_min` / `action_delay_max` 插入间隔。
  - 文本评论：统一使用 `input text` 一次性输入评论文案；
  - 发送评论：使用 `(0.91×宽, 0.57×高)` 的 root tap，失败则 ENTER 兜底。
- `直客访问模式`（run_mode=`direct`）
  - 固定点击左侧第一条作品；
  - 作品点击高度当前使用 `0.80×屏幕高`；
  - **取消进入作品页 OCR 判定步骤**（不再做 `Search`/评论输入区命中判定）；
  - 点击后按配置等待 `direct_post_action_delay_ms`（默认 1500ms，范围会被钳制为 300~8000ms）后，直接进入互动阶段；
  - 互动阶段执行：评论 + 点赞 + 收藏 + 转发（均受每日上限约束）；
  - 收藏与转发在 direct 下均走固定坐标点击（不走 OCR 锚点）：
    - 收藏：`x = 0.92 * 屏幕宽`，`y = 0.75 * 屏幕高`
    - 转发（打开面板）：`x = 0.92 * 屏幕宽`，`y = 0.82 * 屏幕高`
    - 转发（点击 Repost）：`x = 0.1111 * 屏幕宽`，`y = 0.80 * 屏幕高`
  - 评论输入框定位：direct 模式改为固定坐标点击：`x = 0.5 * 屏幕宽`，`y = 0.98 * 屏幕高`；评论文案统一使用 `input text` 一次性输入。
- `主播访问模式`（run_mode=`live`，与网页/服务端下发一致）
  - 点击左侧第一条作品后，走“第一次 + 第二次”判定流程：
    - 优先帧差判断；
    - 兜底 OCR 评论输入区判断。
  - 作品点击高度当前使用 `0.80×屏幕高`。
  - 若连续判定失败，则按恢复策略回退并从下一个用户名继续。

日志方面：
- direct 模式会记录“点击首条作品结果”、`direct_post_action_delay_ms`、以及“到达互动触发时机”的累计耗时；
- live 模式继续记录判定链路中的 OCR/帧差信息，便于排查误判。

- **无障碍**：任务执行以 **Application 上下文 + root 命令** 为主，**不再把「无障碍服务已连接」当作总开关**；无障碍可选（用于 `dispatchGesture` 等，未连接时 `scrollFeed` 等会走 root 兜底）。

## 当前未实现

出于安全边界，这个工程没有实现以下内容：

- 针对第三方应用的无障碍自动化（业务代码以 root 坐标流为主；无障碍为可选增强）
- 模拟点击、模拟滑动、反检测、规避风控
- 强杀第三方应用并恢复执行

我保留了 `TraceExecutor` 抽象，便于你后续接入你自己的合规业务执行器。

## 导入方式

1. 使用 Android Studio 打开 `android-simple` 目录。
2. 等待 Gradle 同步完成（项目已包含 Gradle Wrapper，固定使用 Gradle 8.7，与 AGP 8.5.2 匹配）。
3. 如需真机访问本机服务端，请修改 `app/src/main/res/values/strings.xml` 中的 `server_base_url`。

### Gradle 同步失败排查

若出现 `Could not install Gradle distribution` 或 `Connection refused`：

1. **确认使用 Wrapper**：Android Studio 中打开 `File > Settings > Build, Execution, Deployment > Build Tools > Gradle`，将 `Gradle` 选为 **Gradle Wrapper**（不要选固定版本 9.x）。
2. **国内网络**：`gradle/wrapper/gradle-wrapper.properties` 已配置腾讯云镜像；依赖仓库在 `settings.gradle.kts` 中优先走阿里云镜像。
3. **代理环境**：若公司网络需要代理，在 `gradle.properties` 中取消注释并填写：
   ```properties
   systemProp.http.proxyHost=127.0.0.1
   systemProp.http.proxyPort=7890
   systemProp.https.proxyHost=127.0.0.1
   systemProp.https.proxyPort=7890
   ```
4. **命令行预下载**（可选）：在项目根目录执行 `gradlew.bat --version`，成功后再回到 Android Studio 点 **Sync Now**。

说明：

- 模拟器访问本机 FastAPI 默认使用 `http://10.0.2.2:8000`
- 真机通常需要改成电脑局域网 IP，例如 `http://192.168.1.100:8000`
- 由于当前服务端是明文 HTTP，请确保测试网络可直连，且 `AndroidManifest.xml` 已开启 `usesCleartextTraffic`

## APK 打包

更稳妥的方式是直接在 Android Studio 中使用：

- `Build > Build APK(s)`
- `Build > Generate Signed Bundle / APK`

如果你的机器已安装 Gradle，也可以在 `android-simple` 目录下执行：

```bash
./gradlew assembleDebug
./gradlew assembleRelease
# Windows: gradlew.bat assembleDebug
```

输出目录通常在：

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

## 原生 Android 11+ 适配说明

- 当前 `minSdk=30`，对应 Android 11。
- 如果使用真机测试，请确保系统未限制应用网络权限。
- 若后续加入前台服务、无障碍服务、开机自启等能力，需要额外补充权限、系统设置引导和机型兼容测试。
