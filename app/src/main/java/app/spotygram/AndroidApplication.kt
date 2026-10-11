package app.spotygram

import android.app.Application

class AndroidApplication : Application() {
    lateinit var model: SpotygramApp
        private set
    val scope get() = model.scope
    val notices get() = model.notices

    override fun onCreate() {
        super.onCreate()
        model = SpotygramApp(AndroidPlatform(this))
        model.start()
    }
}
