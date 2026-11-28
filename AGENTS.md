# 项目约定

## 本地编译、安装与版本号

- 产品名固定 `ColorPlay Next`，小写形式 `colorplay-next`。Android 原生 Compose，不跨平台；目标一加16 / ColorOS17，首版范围导航、音乐、触摸、重连、方向盘键。
- 安装身份固定 `com.emxxme.colorplaynext`，沿用旧证书与图标；内部 namespace/JNI 保留 `io.github.hypercar`。签名材料绝不入库。
- **核心维护要求：方便更新 HyperCar 底座。** 保留完整上游历史，以 `upstream` 追踪 `https://github.com/fw867/hypercar.git`；在独立文件封装OPPO策略、品牌、玻璃组件，减少上游核心改动。`UpstreamBase.kt`集中记录已验收的导入版本/提交，不把fetch到最新误标为已经升级；更新流程先审查diff、合并、回归、原签名构建再升底座记录，禁止应用内下载HyperCar APK冒充底座更新。
- 版本格式 `X.Y.Z`、`X.Y.Z-beta.N` 或实验线 `X.Y.Z-alpha.N`，统一读取根 `version.properties`。重建起点 `0.2.0/200`，当前候选 `0.2.15/216`；用户已安装 `0.2.14/215`，后续实质变更不能复用该安装编号；已发布 Alpha `0.2.13-alpha.1/213`，不同渠道版本以各自远端 version.properties 为准。已装机后实质改动必须升号；未装机同批修正可保持版本。
- **同一批未提交改动只升级一次。** 编译前对照 `HEAD` 检查该文件，包括暂存区和未暂存的修改；两项版本值已经升级，就沿用当前版本。该批改动提交前，即使继续修改代码、修复编译错误、多次编译或覆盖安装，也不再加号。仅修改注释不算已经升级版本。
- 当前改动提交后，下一批需要编译安装的新改动再升级一次。仅修改文档、运行测试或 CI 重跑，不因此增加版本号。
- 编译验证通过后自动覆盖安装到已连接的 HyperCar 手机，保留应用数据和配对记录。手机未连接时明确说明安装未完成。
- 发布时随代码一起提交版本文件；正式 CI 不自行递增版本，也不覆盖已由其它提交使用的发布标签。用户授权的 alpha-upstream 工作流例外：只在 alpha 候选分支自动递增 X.Y.Z-alpha.N / versionCode，测试/签名通过后才推送与预发布，禁止自动合入 main。正式发布的 versionCode 必须高于 alpha，显示版本应不低于 Alpha 的目标核心版本；严禁把预发布标记为 latest。main 定制变更同步到 alpha 由人工确认，不自动吞并。
- 2026-11-27 用户额外授权 `official-upstream-release.yml` 正式通道：每小时检测上游官方正式 Release，仅从精确 tag 提交合并到 main；首次仅登记历史基线。源码冲突中止，Rust/Android/Lint/原签名及包身份全部通过后才更新 main、创建新正式标签并发布；安装编号高于已发布正式与 Alpha。与 Alpha 共享发布锁，不自动改动 alpha 分支，不下载上游 APK冒充升级。历史回补必须显式指定 tag 并允许回补；不完整 Release 不标记完成，标签/分支并发变化停止人工处理。
- Car+ 适配保持独立于上游协议：`carplus/` 负责普通导出入口、CONTROL_READY 后延迟启动和 attempt/engine 门禁；禁止预热或旧 onReopen 提前拉起，不用 CMD 已连接冒充画面证据，不用特权 shell 绕过非导出入口。
- 未经用户明确要求，不自行提交或推送代码。
