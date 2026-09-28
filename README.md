# MiniMax TTS Reader

基于 [MiMoTTSReader](https://github.com/timyang2005/MiMoTTSReader) 架构，为 [MiniMax T2A v2](https://platform.minimaxi.com/) 语音合成服务设计的 Android 应用，通过本地 HTTP 服务为 [Legado](https://github.com/gedoor/legado) 阅读 APP 提供朗读引擎。

> 当前版本：v0.4.0

## ✨ 功能特性

- 🎙️ **MiniMax T2A v2 支持** - speech-2.8-hd/turbo、speech-2.6、speech-02 等全系模型
- 📖 **Legado 集成** - 一键导入朗读规则（规则默认并发数 5，可在阅读APP内调整）
- 🎵 **26 种预置音色** - 有声书、主持人、青年、童声、精品等
- 🎛️ **全参数可调** - 语速/音量/语调/情感/语言优化/声音效果器/音频设置
- 📝 **LLM 文本预处理** - 调用 MiniMax LLM 净化文本（删 Markdown、表情、动作括注），支持高级/基础两套 prompt，按模型自动匹配
- 🧠 **LLM 思考模式** - M3 模型可开启/关闭思考，请求恒带 reasoning_split 防思考内容混入朗读文本
- 📜 **LLM 预处理日志** - 记录每次预处理请求（模型/耗时/输出/思考内容），Web 日志页按日查看，自动保留 7 天
- 🔧 **多配置管理** - 创建/切换/删除独立配置，"默认配置"只读不可删
- 🌐 **全局账户分离** - API Key / GroupId 独立于配置存储，切配置不丢凭证
- 🎚️ **增强滑块** - +/- 按钮（长按连发）、数字输入校验、范围钳位
- 🎨 **Coral 主题 UI** - 暖纸白底 + 墨蓝文字 + 珊瑚点缀，和纸噪点纹理
- 📱 **五页式架构** - 运行 / 配置 / 账户 / LLM / 日志，底部 Tab 导航
- 🛡️ **服务保活** - 前台服务 + 电池优化白名单（建议在系统设置中对该应用关闭电源管控）
- 📱 **纯原生架构** - Kotlin + NanoHTTPD + WebView

## 🏗️ 架构

```
legado 朗读 -> localhost:9966 (NanoHTTPD) -> MiniMaxTtsClient -> api.minimaxi.com/t2a_v2 -> wav
```

- **API**：MiniMax T2A v2 国内版（`api.minimaxi.com` + GroupId + Bearer 认证）
- **音频**：统一 WAV 格式输出，hex 解码
- **LLM 预处理**：TTS 合成前可选调用 MiniMax LLM 净化文本

## 📖 使用

1. 安装 APK
2. 填入 MiniMax **API Key** 和 **GroupId**（platform.minimaxi.com 获取）
3. 选择音色、调整参数
4. 启动服务（`localhost:9966`）
5. 点「一键导入阅读APP」
6. legado 朗读引擎选「MiniMax-TTS」

## 🎨 参数

| 参数 | 范围 |
|------|------|
| 模型 | speech-2.8-hd/turbo 等 8 种 |
| 音色 | 26 种 |
| 语速 speed | 0.5 ~ 2.0 |
| 音量 vol | 0.1 ~ 10 |
| 语调 pitch | -12 ~ 12 |
| 情感 emotion | 11 项（官方枚举 9 项；neutral 为代码额外提供、官方文档未收录，可能不被 API 接受） |
| 语言优化 language_boost | auto + 16 种语言 |
| 声音效果器 voice_modify | 音高/强度/音色(-100~100) + 4 种音效 |
| 音频设置 audio_setting | 采样率/声道（格式固定 wav；比特率仅 mp3 生效，已无 UI 入口） |
| LLM 模型 | MiniMax-M2/M2.1/M2.5/M2.7/M3 系列 + abab6.5s-chat |
| LLM 思考模式 | 关闭（默认）/ 开启 / 不设置；仅 MiniMax-M3 生效，M2.x 及以下不支持关闭思考；请求恒带 reasoning_split，防止思考内容混入朗读文本 |
| LLM max_tokens | 256 ~ 8192 |
| LLM temperature | 0 ~ 1 |

## 📝 LLM 预处理日志

每次 LLM 预处理请求都会记录：请求时间、requestId、模型、prompt 类型（自定义/默认 2.8/基础）、文本长度与预览、耗时、状态（正常/跳过/回退/错误），以及 LLM 输出全文与思考内容（reasoning）。被跳过的请求（如文本无需净化）不包含输出，原因记录在 error 字段。

- **存储**：应用私有数据目录，按天分文件，无需额外权限
- **保留**：自动保留最近 7 天，过期自动清理
- **查看**：应用内「日志」页按日期选择、点击条目展开详情；也可直接访问 `http://127.0.0.1:9966/api/llm-logs?date=YYYY-MM-DD` 查看原始 JSON

## ⚠️ 已知限制

- **请求行长度上限**：本地 HTTP 服务以 GET 请求行传递文本，NanoHTTPD 单请求行上限 8192 字节，约合 850 个中文字符，超长文本可能被截断。当前聚批约 150 字/包，余量充足。
- **仅本机访问**：服务绑定 127.0.0.1，只允许本机访问；电脑或其他设备无法连接。
- **电源管控**：应用不再持有 WakeLock，建议在系统设置中对该应用关闭电源管控（如「耗电保护 → 无限制」），或使用应用内「忽略电池优化」按钮，防止服务被系统回收。

## 🔧 编译

GitHub Actions：仓库 -> Actions -> Build APK -> Run workflow -> 下载 Artifacts。

或本地：
```bash
git clone https://github.com/brunhildzhou/minimax-tts-reader.git
cd minimax-tts-reader
./gradlew assembleDebug
```

环境：JDK 17+ + Android SDK 34，minSdk 24（Android 7.0）。

## 📁 结构

```
app/src/main/
├── assets/web/                    # WebView 前端
│   ├── index.html                 # 五页面 + Tab 导航
│   ├── css/style.css              # Coral 主题
│   └── js/app.js                  # 交互逻辑
├── java/com/minimax/ttsreader/
│   ├── api/MiniMaxTtsClient.kt    # MiniMax T2A v2 客户端
│   ├── model/{MiniMaxModels.kt, VoiceConfig.kt}
│   ├── server/TtsServer.kt        # NanoHTTPD 本地服务
│   ├── service/TtsService.kt      # 前台保活
│   ├── MainActivity.kt            # WebView + JS 桥接
│   └── util/                      # 音频处理 / 配置管理
└── res/                           # 图标 / 颜色资源
```

## 🙏 致谢

本项目基于 [MiMoTTSReader](https://github.com/timyang2005/MiMoTTSReader)（原作者 [@timyang2005](https://github.com/timyang2005)）修改而来。

- [MiniMax](https://platform.minimaxi.com/) - 语音合成 API
- [Legado](https://github.com/gedoor/legado) - 开源阅读 APP
- [MiMoTTSReader](https://github.com/timyang2005/MiMoTTSReader) - 架构基础
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) - 嵌入式 HTTP 服务器

## 📄 License

MIT - 详见 [LICENSE](LICENSE) 文件。
