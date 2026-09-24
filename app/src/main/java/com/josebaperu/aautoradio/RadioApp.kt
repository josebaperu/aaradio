package com.josebaperu.aautoradio

import android.app.Application
import com.josebaperu.aautoradio.audio.EffectsStore
import com.josebaperu.aautoradio.audio.EqStore
import com.josebaperu.aautoradio.data.StationRepository
import com.josebaperu.aautoradio.ui.theme.ThemePrefs

class RadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StationRepository.init(this)
        EqStore.init(this)
        EffectsStore.init(this)
        ThemePrefs.init(this)
    }
}
