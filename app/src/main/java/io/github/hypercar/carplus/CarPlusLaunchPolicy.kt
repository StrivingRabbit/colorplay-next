package io.github.hypercar.carplus

internal object CarPlusSource {
    const val VALUE = "carplus"
    const val REVISION = "carplus-r2"
    val packages = listOf("com.oplus.ocar", "com.oplus.carplus", "com.coloros.carplus")
    const val COMPONENT = "com.oplus.ocar/.CarlifeActivity"
    fun matches(source: String?) = source == VALUE

    fun entryScore(packageName: String, activity: String, launcher: Boolean = false): Int {
        if (!validComponent("$packageName/$activity")) return 0
        val name = activity.removePrefix(packageName).lowercase(java.util.Locale.ROOT)
        if (name.contains("settings") || name.contains("permission")) return 0
        return when {
            name.contains("carlife") && name.contains("connect") -> 300
            packageName == "com.oplus.ocar" && name == ".carlifeactivity" -> 200
            name.contains("carlife") -> 150
            launcher -> 100
            else -> 0
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
    // EngineCallbackGate owners increase monotonically; a watermark also rejects unseen stale events.
    private var newestEngine = 0L

    @Synchronized fun begin(attempt: Long) {
        owner = attempt
        engine = null
        generation++
        claimed = false
        newestEngine = 0L
    }

    @Synchronized fun connected(attempt: Long, engine: Long): Ticket? {
        if (owner != attempt || engine <= 0L || engine < newestEngine) return null
        if (engine == newestEngine && this.engine != engine) return null
        if (this.engine != engine) {
            // Some native replacements do not deliver a terminal callback. Supersede their ticket here.
            generation++
            this.engine = engine
            newestEngine = engine
        }
        return Ticket(attempt, engine, generation)
    }

    @Synchronized fun claim(ticket: Ticket, canLaunch: Boolean, sourceAwaiting: Boolean): Boolean {
        if (!current(ticket) || claimed || !canLaunch || !sourceAwaiting) return false
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
