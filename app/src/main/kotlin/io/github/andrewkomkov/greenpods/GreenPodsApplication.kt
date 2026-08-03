package io.github.andrewkomkov.greenpods

import android.app.Application
import io.github.andrewkomkov.greenpods.core.data.PodRepository

/**
 * Manual dependency container.
 *
 * GreenPods has a handful of singletons and no need for compile-time DI; a plain
 * lazy container keeps the build free of annotation processors, which matters more
 * here than the ergonomics of constructor injection.
 */
class GreenPodsApplication : Application() {
    val podRepository: PodRepository by lazy { PodRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GreenPodsApplication
            private set
    }
}
