package com.miniplayer.app

import android.app.Application
import com.miniplayer.app.core.CoreRegistry
import com.miniplayer.app.core.MiniPlayerCoreProvider

class MiniPlayerApplication : Application() {
    val core: MiniPlayerCoreProvider by lazy { CoreRegistry.get(this) }
}
