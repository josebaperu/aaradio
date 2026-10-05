package com.josebaperu.ytmaa

import android.app.Application
import com.josebaperu.ytmaa.audio.EffectsStore
import com.josebaperu.ytmaa.audio.EqStore
import com.josebaperu.ytmaa.data.YtLibrary
import com.josebaperu.ytmaa.ui.theme.ThemePrefs

class YTMapp : Application() {
    override fun onCreate() {
        super.onCreate()
        YtLibrary.init(this)
        EqStore.init(this)
        EffectsStore.init(this)
        ThemePrefs.init(this)
    }
}
