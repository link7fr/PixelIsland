package com.pixelisland

import android.app.Application
import com.pixelisland.data.IslandSettings
import com.pixelisland.media.MediaRepository
import com.pixelisland.system.SystemRepository

class PixelIslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IslandSettings.init(this)
        SystemRepository.register(this)
        MediaRepository.init(this)
    }
}
