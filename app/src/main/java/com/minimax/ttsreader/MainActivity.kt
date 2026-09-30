package com.minimax.ttsreader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.minimax.ttsreader.model.VoiceConfig
import com.minimax.ttsreader.model.VoiceRegistry
import com.minimax.ttsreader.server.TtsServer
import com.minimax.ttsreader.service.TtsService
import com.minimax.ttsreader.util.ConfigManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message ?: "")
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setNegativeButton("取消") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }
            }
            addJavascriptInterface(WebAppInterface(), "Android")
        }

        setContentView(webView)
        webView!!.loadUrl("file:///android_asset/web/index.html")

        observeServiceState()
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            TtsService.isRunning.collect { running ->
                webView?.post {
                    webView?.evaluateJavascript(
                        "if(typeof onServiceStateChanged==='function'){onServiceStateChanged($running);}", null
                    )
                }
            }
        }

        lifecycleScope.launch {
            TtsService.logs.collect { logs ->
                webView?.post {
                    try {
                        val json = Gson().toJson(logs.take(50))
                        webView?.evaluateJavascript(
                            "if(typeof onLogsUpdate==='function'){onLogsUpdate($json);}", null
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.post {
            val running = TtsService.isRunning.value
            webView?.evaluateJavascript(
                "if(typeof onServiceStateChanged==='function'){onServiceStateChanged($running);}", null
            )
            refreshLogs()
        }
    }

    private fun refreshLogs() {
        try {
            val json = Gson().toJson(TtsService.logs.value.take(50))
            webView?.evaluateJavascript(
                "if(typeof onLogsUpdate==='function'){onLogsUpdate($json);}", null
            )
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    /** 把任意字符串安全地序列化为 JS 字符串字面量（含引号），供 evaluateJavascript 拼接 */
    private fun jsStr(s: String?): String = Gson().toJson(s ?: "")

    inner class WebAppInterface {

        @JavascriptInterface
        fun getConfig(): String {
            val config = ConfigManager.getConfig(this@MainActivity)
            return Gson().toJson(config)
        }

        @JavascriptInterface
        fun saveConfig(json: String) {
            try {
                val config = Gson().fromJson(json, VoiceConfig::class.java)
                ConfigManager.saveConfig(this@MainActivity, config)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ===== 多配置管理 =====

        @JavascriptInterface
        fun getConfigList(): String {
            return Gson().toJson(ConfigManager.getConfigList(this@MainActivity))
        }

        @JavascriptInterface
        fun loadConfigByName(name: String): String {
            return Gson().toJson(ConfigManager.getConfigByName(this@MainActivity, name))
        }

        @JavascriptInterface
        fun saveConfigByName(name: String, json: String) {
            try {
                val config = Gson().fromJson(json, VoiceConfig::class.java)
                ConfigManager.saveConfigByName(this@MainActivity, name, config)
                ConfigManager.setActiveConfigName(this@MainActivity, name)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun deleteConfigByName(name: String) {
            ConfigManager.deleteConfigByName(this@MainActivity, name)
        }

        @JavascriptInterface
        fun getActiveConfigName(): String {
            return ConfigManager.getActiveConfigName(this@MainActivity)
        }

        @JavascriptInterface
        fun setActiveConfigName(name: String) {
            ConfigManager.setActiveConfigName(this@MainActivity, name)
        }

        // ===== 全局账户凭证 =====

        @JavascriptInterface
        fun getAccount(): String {
            val apiKey = ConfigManager.getGlobalApiKey(this@MainActivity)
            val groupId = ConfigManager.getGlobalGroupId(this@MainActivity)
            return Gson().toJson(mapOf("apiKey" to apiKey, "groupId" to groupId))
        }

        @JavascriptInterface
        fun saveAccount(apiKey: String, groupId: String) {
            ConfigManager.saveAccount(this@MainActivity, apiKey, groupId)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "保存成功", Toast.LENGTH_SHORT).show()
            }
        }

        // ===== 全局响度处理（v0.6.x） =====

        @JavascriptInterface
        fun getNormalizeMode(): String = ConfigManager.getNormalizeMode(this@MainActivity)

        @JavascriptInterface
        fun setNormalizeMode(mode: String) {
            ConfigManager.setNormalizeMode(this@MainActivity, mode)
        }

        // ===== 双引擎模式（v0.8.0）=====

        /**
         * 是否启用双引擎路由（v0.8.0）。
         *
         * true：DialogueClassifier 把每段分成 DIALOGUE/NARRATION
         *       - DIALOGUE → MiniMax TTS（情绪化声音，需 apiKey/groupId）
         *       - NARRATION → Android 系统 TTS（本地引擎，零 token 成本）
         * false：所有段都走 MiniMax TTS（v0.7.4 旧行为，单引擎模式）
         *
         * 改后需用户重启 TtsService 生效（与 LLM/限速等全局设置一致）。
         */
        @JavascriptInterface
        fun getDualEngineMode(): Boolean = ConfigManager.getDualEngineMode(this@MainActivity)

        @JavascriptInterface
        fun setDualEngineMode(enabled: Boolean) {
            ConfigManager.setDualEngineMode(this@MainActivity, enabled)
        }

        // ===== DRC 高级参数（v0.7.4，针对 MiniMax Speech-2.8-HD 调优） =====

        /**
         * 返回当前 DRC 配置 JSON 字符串（含 5 个字段）。前端展开高级面板时调用。
         * 若用户从未调整过，返回 ConfigManager 内置的默认 DrcConfig（而非空对象），
         * 便于前端判断"当前显示的就是系统默认"—— resetDrcConfig 走 pref.remove() 也会得到默认。
         */
        @JavascriptInterface
        fun getDrcConfig(): String = Gson().toJson(ConfigManager.getDrcConfig(this@MainActivity))

        /**
         * 写入 DRC 配置。前端传完整 5 字段对象；后端会在写入前自动 clamp（ConfigManager.setDrcConfig）。
         * 不返回结果，前端拿不到 error 时用通用 toast 兜底。
         */
        @JavascriptInterface
        fun setDrcConfig(json: String) {
            try {
                val cfg = Gson().fromJson(json, ConfigManager.DrcConfig::class.java)
                ConfigManager.setDrcConfig(this@MainActivity, cfg)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "DRC 配置保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** 重置 DRC 高级参数为系统默认（删除 SharedPreferences 键 → getDrcConfig 回退到 DrcConfig()） */
        @JavascriptInterface
        fun resetDrcConfig() {
            ConfigManager.resetDrcConfig(this@MainActivity)
        }

        // ===== 全局 LLM 设置 =====

        @JavascriptInterface
        fun getLlmSettings(): String {
            val settings = mapOf(
                "llmEnabled" to ConfigManager.getLlmEnabled(this@MainActivity),
                "llmModel" to ConfigManager.getLlmModel(this@MainActivity),
                "llmPrompt" to ConfigManager.getLlmPrompt(this@MainActivity),
                "llmMaxTokens" to ConfigManager.getLlmMaxTokens(this@MainActivity),
                "llmTemperature" to ConfigManager.getLlmTemperature(this@MainActivity),
                "llmThinking" to ConfigManager.getLlmThinking(this@MainActivity)
            )
            return Gson().toJson(settings)
        }

        @JavascriptInterface
        fun saveLlmSettings(json: String) {
            try {
                val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
                ConfigManager.setLlmEnabled(this@MainActivity, obj.get("llmEnabled")?.asBoolean ?: false)
                ConfigManager.setLlmModel(this@MainActivity, obj.get("llmModel")?.asString ?: "MiniMax-M2.7-highspeed")
                ConfigManager.setLlmPrompt(this@MainActivity, obj.get("llmPrompt")?.asString ?: "")
                ConfigManager.setLlmMaxTokens(this@MainActivity, obj.get("llmMaxTokens")?.asInt ?: 2048)
                ConfigManager.setLlmTemperature(this@MainActivity, obj.get("llmTemperature")?.asDouble ?: 0.1)
                ConfigManager.setLlmThinking(this@MainActivity, obj.get("llmThinking")?.asString ?: "disabled")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun startService() {
            val intent = Intent(this@MainActivity, TtsService::class.java).apply { action = TtsService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }

        @JavascriptInterface
        fun stopService() {
            val intent = Intent(this@MainActivity, TtsService::class.java).apply { action = TtsService.ACTION_STOP }
            startService(intent)
        }

        @JavascriptInterface
        fun restartService() {
            val intent = Intent(this@MainActivity, TtsService::class.java).apply { action = TtsService.ACTION_RESTART }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }

        @JavascriptInterface
        fun isServiceRunning(): Boolean = TtsService.isRunning.value

        @JavascriptInterface
        fun getVoices(): String = Gson().toJson(VoiceRegistry.PRESET_VOICES)

        /** 可用 LLM 模型列表，供前端下拉 */
        @JavascriptInterface
        fun getLlmModels(): String = Gson().toJson(VoiceRegistry.LLM_MODELS)

        /** 默认 LLM 提示词文本，供前端占位 / 一键填充 */
        @JavascriptInterface
        fun getDefaultLlmPrompt(): String = VoiceRegistry.DEFAULT_LLM_PROMPT

        @JavascriptInterface
        fun getBasicLlmPrompt(): String = VoiceRegistry.BASIC_LLM_PROMPT

        /**
         * 测连接：不走服务，直接用 form 内当前 apiKey/groupId/model 跑一次最小 T2A 探活。
         * 成功回 onTestConnectionResult(true, "连接成功，<额外信息>")；失败回 (false, "错误细节")
         */
        @JavascriptInterface
        fun testConnection(jsonConfig: String) {
            val config = try {
                Gson().fromJson(jsonConfig, VoiceConfig::class.java)
            } catch (e: Exception) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "if(typeof onTestConnectionResult==='function'){onTestConnectionResult(false,${jsStr("配置解析失败: ${e.message}")});}", null
                    )
                }
                return
            }
            if (config.apiKey.isBlank()) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "if(typeof onTestConnectionResult==='function'){onTestConnectionResult(false,${jsStr("请先填写 API Key")});}", null
                    )
                }
                return
            }
            if (config.groupId.isBlank()) {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "if(typeof onTestConnectionResult==='function'){onTestConnectionResult(false,${jsStr("请先填写 GroupId")});}", null
                    )
                }
                return
            }
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val msg = StringBuilder()
                    if (config.llmEnabled && config.llmModel.isNotBlank()) {
                        try {
                            val llm = com.minimax.ttsreader.api.MiniMaxLlmClient()
                            llm.ping(config.apiKey, config.groupId, config.llmModel)
                            msg.append("LLM 直连 OK；")
                        } catch (e: Exception) {
                            msg.append("LLM 探活失败（${e.message?.take(80)}）；")
                        }
                    }
                    val tts = com.minimax.ttsreader.api.MiniMaxTtsClient(
                        rateLimiter = com.minimax.ttsreader.util.RateLimiter(
                            com.minimax.ttsreader.util.ConfigManager.getRateLimitRpm(this@MainActivity)
                        ),
                        audioCache = com.minimax.ttsreader.cache.AudioCache(this@MainActivity)
                    )
                    val probe = config.copy(audioFormat = "wav")
                    val result = tts.synthesize(config.apiKey, config.groupId, "连接测试", probe)
                    msg.append("TTS OK，音频 ${result.audio.size}B")
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "if(typeof onTestConnectionResult==='function'){onTestConnectionResult(true,${jsStr(msg.toString())});}", null
                        )
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "if(typeof onTestConnectionResult==='function'){onTestConnectionResult(false,${jsStr(e.message)});}", null
                        )
                    }
                }
            }
        }

        /** 返回所有下拉选项（模型/情感/语言/音效/采样率等），供前端动态渲染 */
        @JavascriptInterface
        fun getOptions(): String {
            val options = mapOf(
                "models" to VoiceRegistry.MODELS,
                "emotions" to VoiceRegistry.EMOTIONS,
                "languageBoosts" to VoiceRegistry.LANGUAGE_BOOSTS,
                "soundEffects" to VoiceRegistry.SOUND_EFFECTS,
                "sampleRates" to VoiceRegistry.SAMPLE_RATES,
                "bitrates" to VoiceRegistry.BITRATES,
                "formats" to VoiceRegistry.FORMATS,
                "channels" to VoiceRegistry.CHANNELS
            )
            return Gson().toJson(options)
        }

        /** 生成 legado 朗读规则（数组格式），单点实现见 TtsServer.buildLegadoRule */
        @JavascriptInterface
        fun getLegadoRule(): String {
            val config = ConfigManager.getConfig(this@MainActivity)
            return TtsServer.buildLegadoRule(config.serverPort)
        }

        /**
         * 一键导入 legado
         * 用 ACTION_VIEW + legado://import/httpTTS URL scheme 唤起阅读APP
         * 失败回退：复制规则到剪贴板，提示手动粘贴
         */
        @JavascriptInterface
        fun importToLegado() {
            val config = ConfigManager.getConfig(this@MainActivity)
            val port = config.serverPort
            val ruleUrl = "http://localhost:$port/api/legado/rule"
            val importUrl = "legado://import/httpTTS?src=" + Uri.encode(ruleUrl)

            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(importUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    Toast.makeText(this@MainActivity, "正在唤起阅读APP...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // legado 未安装或无法处理，回退复制规则
                    val ruleJson = getLegadoRule()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MiniMax TTS Rule", ruleJson))
                    Toast.makeText(
                        this@MainActivity,
                        "未检测到阅读APP，规则已复制到剪贴板，请在阅读APP朗读引擎中手动粘贴",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun testTts(text: String) {
            if (!TtsService.isRunning.value) {
                runOnUiThread { Toast.makeText(this@MainActivity, "请先启动服务", Toast.LENGTH_SHORT).show() }
                return
            }
            val config = ConfigManager.getConfig(this@MainActivity)
            if (config.apiKey.isBlank()) {
                runOnUiThread { Toast.makeText(this@MainActivity, "请先配置 API Key", Toast.LENGTH_SHORT).show() }
                return
            }
            if (config.groupId.isBlank()) {
                runOnUiThread { Toast.makeText(this@MainActivity, "请先配置 GroupId", Toast.LENGTH_SHORT).show() }
                return
            }

            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val finalText = com.minimax.ttsreader.util.TextPreprocessor.preprocess(text, config)
                    val client = com.minimax.ttsreader.api.MiniMaxTtsClient(
                        rateLimiter = com.minimax.ttsreader.util.RateLimiter(
                            com.minimax.ttsreader.util.ConfigManager.getRateLimitRpm(this@MainActivity)
                        ),
                        audioCache = com.minimax.ttsreader.cache.AudioCache(this@MainActivity)
                    )
                    // 测试合成统一非流式 WAV，确保 validateAndFixWav 接收的是 WAV 数据
                    val testConfig = config.copy(audioFormat = "wav")
                    val result = client.synthesize(config.apiKey, config.groupId, finalText, testConfig)
                    val wavData = com.minimax.ttsreader.util.AudioUtils.validateAndFixWav(
                        result.audio, testConfig.sampleRate, testConfig.channel
                    )
                    val file = java.io.File.createTempFile("tts_test_", ".wav", cacheDir)
                    file.writeBytes(wavData)
                    file.deleteOnExit()
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "if(typeof onTestResult==='function'){onTestResult(${jsStr("file://${file.absolutePath}")});}", null
                        )
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "if(typeof onTestError==='function'){onTestError(${jsStr(e.message)});}", null
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun requestBatteryOptimization() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${packageName}")
                )
                runOnUiThread { startActivity(intent) }
            }
        }

        @JavascriptInterface
        fun getLogs(): String = Gson().toJson(TtsService.logs.value.take(50))

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun getServerUrl(): String {
            val config = ConfigManager.getConfig(this@MainActivity)
            return "http://localhost:${config.serverPort}"
        }
    }
}