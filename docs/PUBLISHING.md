# 源码仓库与发布

源码仓库为 <https://github.com/GZR-zhuanjia/AndroidFormatFactory>，原版本已推送到 `main`。本轮 FFmpeg 改动按当前会话沿用的授权完成本地提交并正常推送到 `main`，不自动创建 Release，也不交付 APK/ZIP。

推送前核对当前分支、远端和未提交改动，仅向已确认的用户仓库正常推送，不使用强制推送。SDK、NDK、原生依赖下载/构建目录、签名密钥、凭据和生成的应用包不提交。默认 CI 从固定源码构建两个 ABI，执行 Kotlin/NDK 编译，不上传安装包。

未来如分发应用二进制，应先处理发行签名、设备兼容性和第三方许可对应源码/库替换义务，详见 [原生音频说明](NATIVE_AUDIO.md)。
