# 转格式

一个使用 Kotlin 与 Jetpack Compose 编写的中文 Android 本地多媒体转换工具。支持 Android 8.0（API 26）及以上，输入文件由系统相册或文件选择器授权，转换过程在手机上完成。

项目当前为源码交付：没有发布下载链接，也不提供现成安装包或源码压缩包。源码仓库：<https://github.com/GZR-zhuanjia/AndroidFormatFactory>。

## 支持的转换

| 类型 | 输出 | 可选参数 |
| --- | --- | --- |
| 视频 | MP4，H.264 或 H.265 / HEVC 视频 / AAC 音频 | 默认原尺寸、自动高码率；最高 2160p / 1080p / 720p / 480p；目标 2 / 4 / 8 / 16 / 24 / 40 / 60 / 100 Mbps；AAC 128 / 192 / 256 / 320 kbps，默认 320 kbps |
| 音频、视频提取声音 | M4A，AAC；MP3，LAME | 128 / 192 / 256 / 320 kbps，默认 320 kbps，最多双声道 |
| 音频、视频提取声音 | FLAC | 按源位深（最高 24 位）或手选 16 / 24 位整数，保留声道 |
| 音频、视频提取声音 | WAV，PCM | 按源位深或手选 16 / 24 位整数、32 位浮点，保留声道 |
| 图片 | JPG、PNG、WebP | 默认原尺寸、JPG 质量 100；最长边 4096 / 3840 / 2560 / 1920 / 1280 px；有损质量 80 / 90 / 95 / 100；WebP 可选无损（Android 11+，此版本上界面默认选择无损） |

视频保留源帧率、按比例缩小，不主动放大。自动高码率按源尺寸、帧率（缺失时按 30 fps）、源码率估算，范围 8–100 Mbps；设备可能调整尺寸和码率。H.264 为默认兼容编码，HEVC 仅在设备报告编码器时允许选择；完成后检查实际输出编码，不会把回退成其他编码的结果当作成功。高码率仍为有损重编码，不承诺源画质；HDR 使用 OpenGL 色调映射为 SDR，不保留原 HDR 效果。

PNG 与 Android 11+ 的 WebP 无损模式保留处理后的解码像素；WebP 有损模式质量 100 不等于无损。JPG 质量 100 仍是有损编码，不支持透明，透明区域填白。图片纠正 EXIF 方向、转为 8 位 sRGB；调整尺寸会改变像素，重新编码不保留原始 EXIF / 位置信息、宽色域、动画或源文件结构。保留 2400 万解码像素的内存安全边界，超过时明确提示选择较小尺寸，不会自动缩小原图；实际可用内存不足时也会失败。

视频 MP4 保留 Media3 / Android 平台编解码路径；音频输出由直接集成的 FFmpeg / LAME 经 NDK/JNI 完成，不依赖 FFmpegKit 或手机 shell。图片仍使用 Android Bitmap。可读取常见音频与 MP4、MOV、MKV、WebM 等容器中的音轨，扩展名不能保证可转换。暂不提供字幕保留、批量队列、剪辑、网络下载或加密媒体处理，也不承诺 HDR、所有声道布局或厂商编码均支持。

MP3 必要时调整为编码器支持的采样率；MP3/M4A 最多双声道。FLAC 编码本身无损，浮点或高于 24 位的输入会转为 24 位整数，因此不承诺所有输入逐样本无损；WAV 可按源位深保留整数精度（最高 32 位），浮点源输出为 32 位浮点，也可手选 16 / 24 位整数或 32 位浮点。默认保留原采样率，可选 44.1 / 48 / 96 kHz；MP3/AAC 必要时调整到编码器支持的值。提高码率可减少重编码损失，提高采样率或位深不能恢复源文件已丢失的音质。音频源文件上限 8GiB，输出上限 8GiB，标准 WAV 约 4GiB，不使用 RF64。

## 使用

1. 在“工作台”选择视频、音频或图片。视频/音频页可直接“从相册选视频”，图片页可“从相册选图片”；音频页会仅提取声音。原系统文件选择入口仍可选择音频、视频等文件；旧系统由 AndroidX 自动回退到文件选择器。
2. 选择输出格式及参数，点击开始。转换进度与取消操作也会显示在前台服务通知中；Android 13 及以上可授权通知。
3. 在转换完成卡片或“转换记录”中点“保存到手机 / U盘”，按引导在系统文件窗口打开位置菜单，选择手机存储或系统已识别的 OTG U盘并保存；U盘是否可见取决于手机 OTG 和系统文件提供方支持，无需全盘权限。保存可取消；拔盘/写入失败可重试，应用内结果保留。也可通过系统分享面板分享。删除记录会同时删除应用内的结果文件。

源文件不会被覆盖。音频在读取元数据和开始转换时分别从 SAF 复制到应用私有临时文件，支持不可直接寻址的文件提供方；两次复制完成后均会清理。请为输入副本和输出预留足够空间，复制时保留至少 64MiB 空间，实际输出仍可能遇到存储不足。转换先写临时文件，成功后进入应用私有结果目录；保存操作会另外创建外部副本。卸载应用会删除应用内结果，请先保存重要文件。分享会把所选结果临时授权给接收应用；系统文件提供方和分享目标的行为由其自身决定。

一次仅运行一个转换。旋转界面不会主动终止任务；如果进程被系统结束，重新打开时会将遗留任务标记为中断并清理临时文件，需要重新转换。前台服务可提高任务存活优先级，仍受系统限制及厂商电池策略影响。

## 隐私与权限

应用没有声明联网权限，也不申请全盘存储权限。文件访问来自系统相册或文件选择器的单文件授权；分享使用 FileProvider。通知与前台服务权限用于在转换期间展示进度和取消入口，没有账号、广告或统计 SDK。应用私有数据不参加 Android 自动备份。

## 本地开发

建议使用 Linux、Android Studio、JDK 17、Android SDK Platform 35、NDK 28.2.13676358、CMake 3.22.1。安装构建工具 `build-essential curl xz-utils pkg-config nasm`。原生依赖源码不随仓库提交，首次构建需访问源站、Google Maven、Maven Central 与 Gradle 发行站点。

```sh
git clone https://github.com/GZR-zhuanjia/AndroidFormatFactory.git
cd AndroidFormatFactory
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
# 使用 Android Studio 配置 SDK，或在 local.properties 设置 sdk.dir。
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./scripts/build-native.sh --abis arm64-v8a,x86_64 --jobs 2
# 仅编译验证，不打包：
./gradlew :app:checkNativeDependencies :app:compileDebugKotlin :app:externalNativeBuildDebug
# 如自行需要调试安装包，再手动运行 ./gradlew assembleDebug。
```

默认支持 arm64-v8a 与 x86_64；可用 `-PnativeAbis=arm64-v8a` 仅构建 ARM64。缺少任一所选 ABI 的 FFmpeg/LAME 库时构建会明确失败，不会静默跳过音频引擎。脚本固定源码版本、校验 SHA-256 并记录配置；详见 [原生依赖与许可](docs/NATIVE_AUDIO.md)。不提交 SDK、预编译动态库或生成包。

这是自行构建的 Debug 版本，默认使用本机调试密钥；若未来正式发布，需要单独配置发行签名。不要提交签名密钥、口令或本地 SDK 路径。

| 工具 | 版本 |
| --- | --- |
| Kotlin / Compose 编译插件 | 2.1.20 |
| Android Gradle Plugin | 8.9.2 |
| Gradle | 8.13 |
| Compose BOM | 2025.04.01 |
| AndroidX Media3 | 1.6.1 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

验证范围与历史结果见 [验证记录](docs/VALIDATION.md)。本轮仅做必要的 Kotlin / NDK 编译验证，不运行模拟器，不生成交付 APK/ZIP。GitHub Actions 会从固定源码编译双 ABI 音频依赖及应用代码，不自动发布或上传安装包。

## 代码与许可

设计与任务状态见 [实施计划](docs/PLAN.md)，模块与生命周期说明见 [架构文档](docs/ARCHITECTURE.md)，仓库操作见 [推送说明](docs/PUBLISHING.md)。

本项目自有代码采用 [MIT License](LICENSE)。AndroidX/Compose、Media3 及提取的九个 Material 图标适用其各自的 Apache License 2.0，详见 [NOTICE](NOTICE) 与 [完整许可证](docs/licenses/Apache-2.0.txt)。

FFmpeg 和 LAME 适用各自的 LGPL 条款，不能因本项目采用 MIT 而忽略其义务。源码定位、构建配置、动态库替换与发行注意事项见 [原生音频说明](docs/NATIVE_AUDIO.md)。动态链接本身不自动满足全部许可义务。
