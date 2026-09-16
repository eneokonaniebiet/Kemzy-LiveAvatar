package com.kemzy.liveavatar

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class KemzyApplication : Application() {
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob()) }
}
