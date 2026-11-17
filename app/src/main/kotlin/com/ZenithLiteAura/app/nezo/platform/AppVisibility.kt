package com.ZenithLiteAura.app.nezo.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用前后台状态。
 * 用途：应用在前台时由 UI 自己弹提示，不必再发一条系统通知（避免重复打扰）。
 */
object AppVisibility {

    private val startedCount = AtomicInteger(0)

    val isForeground: Boolean get() = startedCount.get() > 0

    fun attach(app: Application) {
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedCount.incrementAndGet()
                }

                override fun onActivityStopped(activity: Activity) {
                    if (startedCount.decrementAndGet() < 0) startedCount.set(0)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
    }
}