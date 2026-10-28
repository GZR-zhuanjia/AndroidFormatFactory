# 验证状态

2026-10-28，在本地 JDK 17 / Android SDK 35 环境执行了构建验证。

- `:app:assembleDebug`：通过，生成的 debug 构建仅用于验证，未作为发行包交付。
- `:app:testDebugUnitTest`：5 项通过，0 失败。覆盖文件名安全、尺寸计算、格式参数与 WAV 文件头/边界。
- `:app:lintDebug`：停止后台验证时发现报告已写入；唯一错误是基础主题引用 API 27 的导航栏属性而最低支持 API 26，已移除该属性。按用户要求未重新运行 lint，未宣称最终通过。
- `:app:assembleDebugAndroidTest`：设备测试源码已编译，完整测试构建与设备执行未完成。
- 设备测试：已有 7 项转换/服务用例与 1 项界面用例，未宣称通过。无 KVM 的软件模拟器虽然被 ADB 识别，但 shell 未及时响应；已按用户要求停止模拟器与辅助容器。

最后的界面滚动、保存按钮自适应、图片元数据方向展示与主题属性修复未再次运行完整构建。源码附有现成测试，用户需要时可按 README 在 Android Studio 或真机上继续验证。

原版本源码随后已推送到 `main`。本轮 FFmpeg 改动不制作交付 ZIP/APK，不启动模拟器。

## FFmpeg 集成本轮

引擎新增直接 FFmpeg 7.1.5 / LAME 3.100 JNI 音频路径；旧平台 WAV 与设备测试的历史通过结果不能代表此路径已验证。固定源码及校验值、依赖构建和双 ABI CI 已加入。

本地完成必要编译：`./gradlew --offline --no-daemon -PnativeAbis=arm64-v8a :app:checkNativeDependencies :app:compileDebugKotlin :app:externalNativeBuildDebug`，`BUILD SUCCESSFUL in 1m 19s`，18 项任务中 11 执行、7 已为最新。FFmpeg/LAME arm64 依赖从固定源码实际编译完成；JNI 成功链接，Kotlin 编译通过。实际 FFmpeg 配置确认 GPL/NONFREE/VERSION3/NETWORK 均关闭，仅启用 FILE/PIPE 协议；五个共享库 SONAME 无版本后缀，LOAD 段为 16KiB 对齐。

构建前仅调整了沙箱内 Gradle/Android 用户目录与本地 socket 权限；编译器有 CoroutineStart.ATOMIC 的 Delicate API 提示（已用注释说明避免 session 泄漏的必要性），以及现有 Gradle 9 兼容性提示。没有因此展开额外测试。x86_64 路径由脚本和 CI 提供，未在本地验证；本轮不做真机/模拟器转换测试，不运行新增单元测试，不生成应用安装包或源码压缩包。
