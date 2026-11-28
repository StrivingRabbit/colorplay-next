package io.github.hypercar.carplus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.hypercar.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 仅在 CarPlay 控制会话就绪后打开手机入口；不更改 CarLife 协议或源端进程。 */
class CarPlusController(context: Context, private val log: (String) -> Unit) {
    private val context = context.applicationContext
    private val policy = CarPlusLaunchPolicy()
    private var scope: CoroutineScope? = null
    private var launchJob: Job? = null
    private var launchTicket: CarPlusLaunchPolicy.Ticket? = null

    @Synchronized fun begin(attempt: Long) {
        scope?.cancel()
        policy.begin(attempt)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        launchJob = null
        launchTicket = null
        log("CARPLUS_BOOT: ${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} strategy=${CarPlusSource.REVISION} attempt=$attempt")
    }

    @Synchronized fun connected(
        attempt: Long, engine: Long, canLaunch: () -> Boolean, sourceAwaiting: () -> Boolean,
    ) {
        val ticket = policy.connected(attempt, engine) ?: return
        if (launchTicket == ticket && launchJob != null) return
        launchJob?.cancel()
        launchTicket = ticket
        launchJob = scope?.launch {
            fun allowed() = policy.current(ticket) && canLaunch() && sourceAwaiting()
            try {
                if (!allowed()) return@launch
                val entry = resolveEntry()
                ensureActive()
                if (!allowed()) return@launch
                if (entry == null) {
                    log("Car+：未找到可用入口，请在 CarPlay 连接后手动打开手机 Car+ 的 CarLife 无线连接页")
                    return@launch
                }
                val opened = withContext(Dispatchers.Main) {
                    ensureActive()
                    if (!allowed()) return@withContext null
                    policy.dispatch(ticket, canLaunch, sourceAwaiting) {
                        runCatching {
                            context.startActivity(Intent().setComponent(entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                    }
                } ?: return@launch
                ensureActive()
                if (!allowed()) return@launch
                if (opened) {
                    log("Car+：已提交手机入口启动；后台启动可能被系统限制，若未打开请手动进入 CarLife 无线连接页，接入需实机验证")
                } else {
                    log("Car+：系统未允许打开入口，请手动进入 CarLife 无线连接页；无需强制授权 Shizuku")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (allowed()) log("Car+：入口启动失败（${error.javaClass.simpleName}），请手动进入 CarLife 无线连接页")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveEntry(): ComponentName? {
        val pm = context.packageManager
        val preferred = ComponentName.unflattenFromString(CarPlusSource.COMPONENT)!!
        fun enabled(info: android.content.pm.ActivityInfo?) =
            info != null && info.enabled && info.applicationInfo.enabled
        fun accessible(info: android.content.pm.ActivityInfo?): Boolean = enabled(info) && info!!.exported &&
            (info.permission.isNullOrEmpty() || context.checkSelfPermission(info.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val info = runCatching { pm.getActivityInfo(preferred, 0) }.getOrNull()
        val candidates = mutableListOf<Pair<ComponentName, Int>>()
        // 只枚举三个白名单包并选择一个入口，不实际轮流启动多个应用。
        for (pkg in CarPlusSource.packages) {
            val launcher = pm.getLaunchIntentForPackage(pkg)?.component?.takeIf { it.packageName == pkg }
            val activities = runCatching {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty().toList()
            }.getOrDefault(emptyList())
            for (activity in activities) {
                if (!accessible(activity)) continue
                val component = ComponentName(pkg, activity.name)
                val score = CarPlusSource.entryScore(pkg, activity.name, component == launcher)
                if (score > 0) candidates += component to score
            }
            if (launcher != null && accessible(runCatching { pm.getActivityInfo(launcher, 0) }.getOrNull())) {
                val score = CarPlusSource.entryScore(pkg, launcher.className, launcher = true)
                if (score > 0) candidates += launcher to score
            }
        }
        if (accessible(info)) candidates += preferred to 200
        candidates.maxByOrNull { it.second }?.let { return it.first }
        return null // Do not automatically use privileged shell to bypass a non-exported OEM entry.
    }

    @Synchronized fun disconnected(attempt: Long, engine: Long) {
        if (!policy.disconnected(attempt, engine)) return
        launchJob?.cancel()
        launchJob = null
        launchTicket = null
    }

    @Synchronized fun stop(attempt: Long) {
        if (!policy.stop(attempt)) return
        scope?.cancel()
        scope = null
        launchJob = null
        launchTicket = null
    }
}
