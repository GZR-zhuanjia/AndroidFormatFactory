# 架构说明

## 数据流

```text
系统文件选择器 → SourceInfo / ConversionOptions
                         ↓
                   ConversionService
                         ↓
       MediaTransformer / WavConverter / ImageConverter
                         ↓
             私有临时文件 → 私有结果文件
                         ↓
                历史状态 → Compose 界面
                         ↓
        ResultExporter 保存副本 / FileProvider 分享
```

## 模块职责

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | Compose 工作台、参数、进度、记录及系统选择器交互 |
| `Models.kt` | 转换参数、状态、格式映射、尺寸计算与文件名净化 |
| `MediaFiles.kt` | 按内容与实际媒体轨道读取源信息 |
| `ConversionService.kt` | 前台服务、单任务约束、取消与任务结果提交 |
| `MediaTransformer.kt` | Media3 视频 MP4 与纯音轨 M4A 转换 |
| `WavConverter.kt` / `WavHeader.kt` | 平台解码至 PCM16、流式写入及 RIFF 头校验 |
| `ImageConverter.kt` | 采样解码、EXIF 方向、缩小与图片编码 |
| `ConverterApp.kt` | 应用级状态、历史持久化与中断任务整理 |
| `ResultExporter.kt` | 独立于界面重建的 SAF 结果复制状态 |

## 生命周期与输出一致性

转换由非导出的前台服务持有；界面订阅应用级状态，不依赖 Activity 生命周期执行转换。Android 15 使用 `mediaProcessing` 类型，较早支持服务类型的系统使用 `dataSync`。通知提供进度与取消入口，缺少通知授权时仍遵循系统前台服务规则。

新任务首先登记运行状态，并在私有目录创建临时文件。转换成功且未取消时才提交至结果目录和成功历史；失败或取消清理临时输出。任务标识与单个活动任务约束防止重复启动。服务不承诺系统杀进程后的自动续传；应用下次启动会整理遗留状态与临时文件。

外部保存由应用级 `ResultExporter` 执行，旋转界面不会重复启动复制。复制按块响应取消，失败时尝试删除未完成的目标文档；第三方文档提供方是否接受删除不能由应用保证。分享仅开放结果目录的特定文件 URI，附加临时读授权。

## 编解码与资源约束

视频请求 H.264 与 AAC，并指定目标码率；输出效果受设备编码器能力影响。Media3 在指定 MIME 内允许尺寸/码率 fallback，用于处理尺寸对齐等约束，不承诺码率或像素尺寸逐项精确匹配。所选高度为上限，不放大原本更小的视频。

WAV 使用 `MediaExtractor` 选择音轨、`MediaCodec` 解码，校验输出为 PCM16 后写入标准 WAV。校验采样数据对齐与 RIFF 32 位长度，超出边界会失败，避免生成长度溢出的文件；不实现 RF64。

图片先读取尺寸并采样，限制解码像素，之后调整 EXIF 方向、按比例缩小；JPG 需要时铺白底。PNG 无损、WebP 有损；重新编码不复制原始 EXIF 元数据。位图及时回收，但系统内存与平台编解码能力仍限制可处理的输入。

## 安全与兼容性边界

清单不包含 `INTERNET`、`MANAGE_EXTERNAL_STORAGE` 或广泛媒体读取权限；源文件通过 SAF 授权获取。文件名只保留受限字符并添加随机后缀，输入与输出目录分离。应用不自动覆盖源文件，不备份私有数据。

Media3/Android 平台决定可解码的容器与编码集合，扩展名和 MIME 提示不能替代轨道检查。特殊 HDR、多声道、加密流、损坏文件及厂商编码器需实际设备确认。前台服务提高进程优先级，不能保证锁屏、电池限制或系统终止条件下持续完成。

## 验证状态

本地 Debug 编译与 5 项 JVM 单元测试通过。Lint 报告的唯一错误已修复，最终结果未复核；设备测试未完成，现有测试源码不等于已验证行为。详见 [验证记录](VALIDATION.md) 与 [评审记录](REVIEW.md)。
