package io.github.hypercar.session

/** 只消费源端启动阶段的拒连信号；预算跨自动重连保留，不创建后台重试定时器。 */
internal class SystemCarLifeLaunchPolicy {
    companion object {
        const val OPPO_PACKAGE = "com.oplus.ocar"
        const val OPPO_COMPONENT = "$OPPO_PACKAGE/.CarlifeActivity"
        const val MAX_ATTEMPTS = 2
        const val BACKOFF_MILLIS = 15_000L
        const val NEW_TRIP_GAP_MILLIS = 10 * 60_000L
    }

    private var owner = 0L
    private var stopped = false
    private var attempts = 0
    private var launched = false
    private var inFlight = false
    private var nextAttemptAt = 0L
    private var lastBeginAt: Long? = null

    @Synchronized fun begin(attempt: Long, manual: Boolean, now: Long) {
        val newTrip = lastBeginAt?.let { now - it >= NEW_TRIP_GAP_MILLIS } ?: true
        // begin is called only after the repository's pause/startup gates accept a new session.
        // Retire old commands on stop, but do not permanently poison later accepted sessions.
        if (manual || newTrip || stopped) {
            attempts = 0
            launched = false
            nextAttemptAt = 0
        }
        stopped = false
        owner = attempt
        inFlight = false
        lastBeginAt = now
    }

    @Synchronized fun stop(attempt: Long) {
        if (owner != attempt) return
        stopped = true
        owner = 0
        inFlight = false
    }

    @Synchronized fun claim(
        attempt: Long, source: String, installedPackage: String?, shizukuReady: Boolean,
        startupRefused: Boolean, sourceConnected: Boolean, now: Long,
    ): String? {
        if (owner != attempt || stopped) return "请求已失效或用户已停止"
        if (source != "system_carlife" || installedPackage != OPPO_PACKAGE) return "不是 OPPO 系统 CarLife 来源"
        if (!startupRefused || sourceConnected) return "源端已连接或没有启动阶段拒连证据"
        if (!shizukuReady) return "Shizuku 未就绪，保留手动入口"
        if (launched) return "本轮自动连接已提交启动，不重复打开页面"
        if (inFlight) return "启动命令正在执行"
        if (attempts >= MAX_ATTEMPTS) return "启动预算已耗尽，等待手动连接或下一次行程"
        if (now < nextAttemptAt) return "启动失败退避中，剩余 ${nextAttemptAt - now}ms"
        attempts++
        inFlight = true
        nextAttemptAt = now + BACKOFF_MILLIS
        return null
    }

    @Synchronized fun canStart(attempt: Long) = owner == attempt && !stopped && inFlight && !launched

    @Synchronized fun complete(attempt: Long, success: Boolean, now: Long) {
        if (owner != attempt || !inFlight) return
        inFlight = false
        launched = success
        nextAttemptAt = now + BACKOFF_MILLIS
    }
}
