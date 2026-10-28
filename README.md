# 转格式

一个使用 Kotlin 与 Jetpack Compose 编写的中文 Android 本地多媒体转换工具。支持 Android 8.0（API 26）及以上，输入文件由系统文件选择器授权，转换过程在手机上完成。

项目当前为源码交付：没有发布下载链接，也不提供现成安装包或源码压缩包。源码仓库：<https://github.com/GZR-zhuanjia/AndroidFormatFactory>。

## 支持的转换

| 类型 | 输出 | 可选参数 |
| --- | --- | --- |
| 视频 | MP4，H.264 视频 / AAC 音频 | 原始尺寸或最高 1080p、720p、480p；视频目标码率 8 / 4 / 2 Mbps |
| 音频、视频提取声音 | M4A，AAC；MP3，LAME | 128 / 192 / 256 kbps，最多双声道 |
| 音频、视频提取声音 | FLAC | 16 / 24 位整数无损压缩，保留采样率与声道 |
| 音频、视频提取声音 | WAV，16 位 PCM | 保留解码后的采样率与声道数 |
| 图片 | JPG、PNG、WebP | 原始尺寸或最长边 2560 / 1920 / 1280 px；JPG / WebP 质量 80 / 90 / 100 |

视频按比例缩小，不主动放大；编码器可能在指定编码格式内调整尺寸和码率。PNG 使用无损编码，调整尺寸仍会改变像素；WebP 使用有损编码，即使质量为 100 也不承诺无损。JPG 不支持透明，透明区域填充白色。图片处理 EXIF 方向，重新编码不保留原始 EXIF 等元数据。

视频 MP4 保留 Media3 / Android 平台编解码路径；音频输出由直接集成的 FFmpeg / LAME 经 NDK/JNI 完成，不依赖 FFmpegKit 或手机 shell。图片仍使用 Android Bitmap。可读取常见音频与 MP4、MOV、MKV、WebM 等容器中的音轨，扩展名不能保证可转换。暂不提供字幕保留、批量队列、剪辑、网络下载或加密媒体处理，也不承诺 HDR、所有声道布局或厂商编码均支持。

MP3 必要时调整为编码器支持的采样率；MP3/M4A 最多双声道。FLAC 编码本身无损，浮点或高于 24 位的输入会转为 24 位整数，因此不承诺所有输入逐样本无损；WAV 输出为 16 位 PCM。音频源文件上限 8GiB，输出上限 8GiB，标准 WAV 约 4GiB，不使用 RF64。

## 使用

1. 在“工作台”选择视频、音频或图片，再通过系统文件选择器选择源文件；音频模式也可以选择视频提取声音。
2. 选择输出格式及参数，点击开始。转换进度与取消操作也会显示在前台服务通知中；Android 13 及以上可授权通知。
3. 在转换完成卡片或“转换记录”中保存副本到系统目录，或通过系统分享面板分享。删除记录会同时删除应用内的结果文件。

源文件不会被覆盖。音频在读取元数据和开始转换时分别从 SAF 复制到应用私有临时文件，支持不可直接寻址的文件提供方；两次复制完成后均会清理。请为输入副本和输出预留足够空间，复制时保留至少 64MiB 空间，实际输出仍可能遇到存储不足。转换先写临时文件，成功后进入应用私有结果目录；保存操作会另外创建外部副本。卸载应用会删除应用内结果，请先保存重要文件。分享会把所选结果临时授权给接收应用；系统文件提供方和分享目标的行为由其自身决定。

一次仅运行一个转换。旋转界面不会主动终止任务；如果进程被系统结束，重新打开时会将遗留任务标记为中断并清理临时文件，需要重新转换。前台服务可提高任务存活优先级，仍受系统限制及厂商电池策略影响。

## 隐私与权限

应用没有声明联网权限，也不申请全盘存储权限。文件访问来自系统文件选择器的单文件授权；分享使用 FileProvider。通知与前台服务权限用于在转换期间展示进度和取消入口，没有账号、广告或统计 SDK。应用私有数据不参加 Android 自动备份。

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
