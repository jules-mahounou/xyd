package tech.xydhub.xyd

import android.app.Application
import tech.xydhub.xyd.data.Repo

class XydApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
