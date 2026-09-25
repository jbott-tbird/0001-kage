package org.foxred.kage

import android.app.Application
import org.foxred.kage.di.AppContainer

class KageApplication : Application() {
    val container by lazy { AppContainer(this) }
}
