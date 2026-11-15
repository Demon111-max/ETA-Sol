package io.github.mangi.eta.ui.app

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

internal fun ComponentActivity.installStartupSplash(
    isContentReady: () -> Boolean,
) {
    StartupSplash(this, isContentReady).install()
}

private class StartupSplash(
    private val activity: ComponentActivity,
    private val isContentReady: () -> Boolean,
) : DefaultLifecycleObserver, ViewTreeObserver.OnPreDrawListener {
    private val content = activity.findViewById<View>(android.R.id.content)
    private val installedAtMs = SystemClock.uptimeMillis()

    fun install() {
        activity.lifecycle.addObserver(this)
        // 外观配置异步读取完成后才允许首帧，避免系统启动画面先退到空白窗口。
        content.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        // 就绪信号万一永远不来（配置读取异常等），超时后也要放行首帧，不能把用户锁在启动画面。
        val timedOut = SystemClock.uptimeMillis() - installedAtMs >= READY_TIMEOUT_MS
        if (!isContentReady() && !timedOut) return false
        content.viewTreeObserver.removeOnPreDrawListener(this)
        return true
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (content.viewTreeObserver.isAlive) {
            content.viewTreeObserver.removeOnPreDrawListener(this)
        }
        activity.lifecycle.removeObserver(this)
    }

    private companion object {
        const val READY_TIMEOUT_MS = 3_000L
    }
}
