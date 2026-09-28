package com.minimax.ttsreader

import android.app.Application
import android.content.Context

class App : Application() {

    companion object {
        /** 静态 applicationContext：供 LlmLogger 等无 Context 的组件取用（如取文件目录） */
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }
}
