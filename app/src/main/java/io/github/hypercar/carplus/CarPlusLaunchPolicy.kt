package io.github.hypercar.carplus

internal object CarPlusSource {
    const val VALUE = "carplus"
    const val REVISION = "carplus-r4-yield-screen"
    val packages = listOf("com.oplus.ocar", "com.oplus.carplus", "com.coloros.carplus")
    const val COMPONENT = "com.oplus.ocar/.CarlifeActivity"
    fun matches(source: String?) = source == VALUE

    fun entryScore(packageName: String, activity: String, launcher: Boolean = false): Int {
        if (!validComponent("$packageName/$activity")) return 0
        val name = activity.removePrefix(packageName).lowercase(java.util.Locale.ROOT)
        if (listOf("setting", "permission", "guide", "help", "about", "download", "privacy",
                "agreement", "intro", "splash", "web", "error", "fail", "instruction", "tips", "notice")
                .any(name::contains)) return 0
        return when {
            // ColorPlay MainActivity.Q1 starts this exact component on display 0, not the OEM launcher.
            packageName == "com.oplus.ocar" && name == ".carlifeactivity" -> 1000
            name.contains("carlife") && name.contains("connect") -> 300
            name.contains("carlife") -> 150
            else -> 0 // A generic launcher is a settings hub, not evidence of a projection entry.
        }
    }

    fun validComponent(value: String): Boolean {
        val parts = value.split('/')
        if (parts.size != 2 || parts[0] !in packages) return false
        val activity = parts[1]
        if (!Regex("\\.?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(activity)) return false
        return activity.startsWith('.') || activity.startsWith(parts[0] + ".")
    }
}

/** Bounded local source diagnostics remain exportable even when verbose recording is disabled. */
object CarPlusDiagnostics {
    private val mutableStatus = kotlinx.coroutines.flow.MutableStateFlow("尚未开始 Car+ 会话")
    val status: kotlinx.coroutines.flow.StateFlow<String> = mutableStatus
    private var owner = 0L
    private var newestEngine = 0L
    private val lines = ArrayDeque<String>()

    @Synchronized fun begin(attempt: Long) {
        owner = attempt
        newestEngine = 0L
        lines.clear()
        record(attempt, 0L, "等待 CarPlay 控制就绪；CarLife 拨号任务已准备不等于源已连接")
    }

    @Synchronized fun record(attempt: Long, engine: Long, message: String) {
        if (owner != attempt || engine < newestEngine) return
        newestEngine = engine
        mutableStatus.value = message
        lines.addLast("CARPLUS_DIAG: attempt=$attempt engine=$engine $message")
        while (lines.size > 24) lines.removeFirst()
    }

    @Synchronized fun snapshot(): List<String> = lines.toList()
}

/** Current-engine video writes, not a local CMD connection, prove that a source is already running. */
internal class CarPlusMediaEvidence {
    private val writtenOwner = java.util.concurrent.atomic.AtomicLong()

    fun observe(owner: Long, frames: Long) {
        if (owner > 0 && frames > 0) writtenOwner.accumulateAndGet(owner, ::maxOf)
    }

    fun awaiting(owner: Long) = owner > 0 && writtenOwner.get() != owner
}

/** 每轮连接尝试只提交一次启动，跨引擎重连保留预算，代次使旧任务失效。 */
internal class CarPlusLaunchPolicy {
    data class Ticket(val attempt: Long, val engine: Long, val generation: Long)
    private var owner: Long? = null
    private var engine: Long? = null
    private var generation = 0L
    private var claimed = false
    private var receiverScreenYielded = false
    private var screenOwnerEngine = 0L
    // EngineCallbackGate owners increase monotonically; a watermark also rejects unseen stale events.
    private var newestEngine = 0L

    @Synchronized fun begin(attempt: Long) {
        owner = attempt
        engine = null
        generation++
        claimed = false
        receiverScreenYielded = false
        screenOwnerEngine = 0L
        newestEngine = 0L
    }

    @Synchronized fun connected(attempt: Long, engine: Long): Ticket? {
        if (owner != attempt || engine <= 0L || engine < newestEngine) return null
        if (engine == newestEngine && this.engine != engine) return null
        if (this.engine != engine) {
            // Some native replacements do not deliver a terminal callback. Supersede their ticket here.
            generation++
            this.engine = engine
            if (screenOwnerEngine != engine) receiverScreenYielded = false
            newestEngine = engine
        }
        return Ticket(attempt, engine, generation)
    }

    @Synchronized fun claim(ticket: Ticket, canLaunch: Boolean, sourceAwaiting: Boolean): Boolean {
        if (!current(ticket) || claimed || receiverScreenYielded || !canLaunch || !sourceAwaiting) return false
        claimed = true
        return true
    }

    /** Serialize only final synchronous Intent submission with retirement; never suspend or wait here. */
    @Synchronized fun <T : Any> dispatch(
        ticket: Ticket, canLaunch: () -> Boolean, sourceAwaiting: () -> Boolean, submit: () -> T,
    ): T? {
        if (!claim(ticket, canLaunch(), sourceAwaiting())) return null
        return submit()
    }

    /** Explicit foreground user action may retry after an automatic request was blocked; never revive stale work. */
    @Synchronized fun <T : Any> dispatchManual(
        ticket: Ticket, canLaunch: () -> Boolean, sourceAwaiting: () -> Boolean, submit: () -> T,
    ): T? {
        if (!current(ticket) || receiverScreenYielded || !canLaunch() || !sourceAwaiting()) return null
        claimed = true
        return submit()
    }

    /** A live receiver ownership notification is authoritative; never infer a gear/camera state. */
    @Synchronized fun screenOwnership(attempt: Long, engine: Long, owned: Boolean, initialOnly: Boolean = false): Boolean {
        if (owner != attempt || engine <= 0 || engine < newestEngine ||
            (this.engine != engine && engine == newestEngine) || (initialOnly && screenOwnerEngine == engine)) return false
        val changed = screenOwnerEngine != engine || receiverScreenYielded == owned
        screenOwnerEngine = engine
        receiverScreenYielded = !owned
        return changed
    }

    @Synchronized fun canOpen(ticket: Ticket): Boolean = current(ticket) && !receiverScreenYielded
    @Synchronized fun canAutomaticallyOpen(ticket: Ticket): Boolean = canOpen(ticket) && !claimed

    @Synchronized fun current(ticket: Ticket): Boolean =
        owner == ticket.attempt && engine == ticket.engine && generation == ticket.generation

    @Synchronized fun disconnected(attempt: Long, engine: Long): Boolean {
        if (owner != attempt || this.engine != engine) return false
        this.engine = null
        generation++
        return true
    }

    @Synchronized fun stop(attempt: Long): Boolean {
        if (owner != attempt) return false
        owner = null
        engine = null
        generation++
        return true
    }
}
