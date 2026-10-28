# FFmpeg 原生音频依赖

音频输出 MP3、FLAC、M4A、WAV 直接经 JNI 调用 libavformat、libavcodec、libswresample；没有引入已退役的 FFmpegKit，不安装或调用手机 shell/ffmpeg 可执行文件。视频 MP4 继续由 Media3 使用设备编解码器，图片继续使用 Bitmap。

## 固定源码与构建

| 依赖 | 源码 | SHA-256 |
| --- | --- | --- |
| FFmpeg 7.1.5 | <https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n7.1.5.tar.gz> | `e3963a50831c985933e1a625ed566ec4c7adb5c012c34fa9f84438e1d61bdacc` |
| LAME 3.100 | <https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz> | `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e` |

7.1.5 是实现时查询到的 FFmpeg 7.1 系列维护版本。固定版本和校验值用于复现源码与构建配置，不承诺不同主机上生成的 ELF 字节完全相同。后续安全更新需审查、更新固定版本/校验值并重新构建。

构建主机为 Linux x86_64，使用 Android NDK 28.2.13676358、API 26、CMake 3.22.1、JDK 17。需要 Bash、make、C/C++ 构建工具、curl、tar、xz、pkg-config 等。操作步骤见根 README；核心命令：

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./scripts/build-native.sh --abis arm64-v8a,x86_64 --jobs 2
./gradlew :app:checkNativeDependencies :app:compileDebugKotlin :app:externalNativeBuildDebug
```

下载及校验后的原始源码保存在 `native-deps/src/`，构建目录在 `native-deps/build/`，供链接的头文件和动态库在 `native-deps/<abi>/include` 与 `lib`。构建配置、许可证副本保存在各 ABI 的 `licenses/`。这些目录均忽略提交，不以二进制替代源码构建过程。CMake 和 Gradle 都会在依赖缺失时明确报错。CI 从固定源码构建两个 ABI，然后编译 Kotlin 与 JNI，不上传安装包。

具体启用的 decoder、demuxer、parser 和 muxer 以 `scripts/build-native.sh` 为准。仅启用音频解码与四种输出所需编码器，不启用软件 H.264 编码器；支持常见 MP4/MOV、Matroska/WebM、Ogg、MP3、FLAC、WAV 等容器。FFmpeg 禁用网络与外部自动探测，协议仅启用本地 `file`/`pipe`，JNI 只接受绝对本地文件路径。应用未声明 INTERNET 权限。

## 转换边界

MP3 使用 LAME，M4A 使用 FFmpeg 原生 AAC，支持 128 / 192 / 256 / 320 kbps，音频默认 320 kbps，均最多双声道。采样率默认原始，可选 44.1 / 48 / 96 kHz，必要时调整至编码器支持的值。WAV 可手选 PCM16、PCM24、32 位浮点；自动模式保留整数精度（最高 32 位），浮点源输出为 32 位浮点，不使用 RF64。FFmpeg 构建启用对应 pcm_s16le / pcm_s24le / pcm_s32le / pcm_f32le 编码器，修改后须重建依赖。FLAC 可按源位深（最高 24 位）或手选 16 / 24 位整数，浮点/32 位输入转为 24 位，因此“FLAC 无损”指编码方式，不承诺所有输入逐样本无损。声道布局、重采样、精度转换和有损输入都可能影响结果。只输出所选音轨，不保留视频、封面或源元数据。

SAF 输入在元数据检查与转换时分别复制到私有可寻址文件，每次都在 finally 中删除；源文件不覆盖。输入最多 8GiB，输出最多 8GiB，WAV 上限略小于 4GiB。复制前后检查可用空间，至少预留 64MiB；输出大小受实际时长/格式影响，仍可能因空间不足而失败。native 使用独立 session 的取消标记、FFmpeg interrupt callback、分块检查及输出限额；错误、取消或回调异常会关闭并删除半成品。协程等待 native worker 完成清理后再结束服务。

## 许可与动态库替换

自有 Kotlin/JNI/构建脚本仍按 MIT 提供。FFmpeg 在本配置下使用 LGPL 2.1 或更高版本；LAME 的库使用 GNU Library GPL 2 或更高版本。脚本明确关闭 `gpl`、`nonfree` 与 `version3`，LAME 作为 LGPL 库动态链接。上游源文件不修改；脚本在独立构建目录生成派生导出表，移除已禁用的 `hip_*`、`lame_decode*` 与不存在的 `lame_init_old` 导出，并调整生成的 libtool Makefile 使用 `-avoid-version`，得到 Android 可加载的无版本 SONAME `libmp3lame.so`。这些构建调整完整记录在脚本中。FFmpeg 与 JNI 也使用无版本动态库名与 16KiB ELF 页对齐。完整上游文本在 [FFmpeg LGPL](licenses/FFmpeg-LGPL-2.1.txt) 和 [LAME LGPL](licenses/LAME-LGPL-2.0.txt)，并作为应用 assets 打包；版权说明见根 NOTICE。

替换方式：取得本仓库与上述源码，用兼容 API/ABI 的修改版本重新运行脚本，或把相应 ABI 的 `libavformat.so`、`libavcodec.so`、`libavutil.so`、`libswresample.so`、`libmp3lame.so` 与匹配头文件放入 `native-deps/<abi>/`，再从应用源码重新链接 JNI、构建并自行签名安装。库间 ABI 必须一致，SONAME 必须无版本后缀；NDK r28 和脚本的 16KiB 页参数应保留。仓库不限制为调试用户修改这些库而进行必要的逆向分析。

动态链接不自动完成 LGPL 的全部义务。实际分发安装包前，发行者应按适用条款向接收者提供准确对应的库源码（包含修改）、构建/替换所需材料、许可证及版权说明，并保留用户修改/替换库的能力。不能仅用项目 MIT 声明覆盖这些第三方义务，也不能把上游链接等同于已经履行所需的源码提供安排。本轮只提交源码，不发布应用二进制；正式发行时需结合签名、商店条款及具体分发方式复核。
