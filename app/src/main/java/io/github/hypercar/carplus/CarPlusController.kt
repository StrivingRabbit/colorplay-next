package io.github.hypercar.carplus

import android.app.Activity
import android.app.ActivityOptions
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

/** Only opens a source entry after CONTROL_READY; OEM protocol and the receiver session remain untouched. */
class CarPlusController(context: Context, private val log: (String) -> Unit) {
    private val context = context.applicationContext
    private val policy = CarPlusLaunchPolicy()
    private var scope: CoroutineScope? = null
    private var launchJob: Job? = null
    private var manualJob: Job? = null
    private var request: Request? = null

    private data class Request(
        val ticket: CarPlusLaunchPolicy.Ticket,
        val canLaunch: () -> Boolean,
        val sourceAwaiting: () -> Boolean,
    )

    @Synchronized fun begin(attempt: Long) {
        scope?.cancel()
        policy.begin(attempt)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        launchJob = null
        manualJob = null
        request = null
        CarPlusDiagnostics.begin(attempt)
        log("CARPLUS_BOOT: ${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} strategy=${CarPlusSource.REVISION} attempt=$attempt")
    }

    @Synchronized fun connected(
        attempt: Long, engine: Long, canLaunch: () -> Boolean, sourceAwaiting: () -> Boolean,
    ) {
        val ticket = policy.connected(attempt, engine) ?: return
        if (request?.ticket == ticket && launchJob != null) return
        launchJob?.cancel()
        manualJob?.cancel()
        val next = Request(ticket, canLaunch, sourceAwaiting)
        request = next
        launchJob = scope?.launch { launchEntry(next, context, manual = false) }
    }

    /** Must be called by a visible Activity, not a background service or a generic launcher. */
    private fun foregroundReady(activity: Activity): Boolean =
        !activity.isFinishing && !activity.isDestroyed &&
            (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
                ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true

    @Synchronized fun launchFromForeground(activity: Activity) {
        if (!foregroundReady(activity) || manualJob?.isActive == true) return
        val next = request ?: return // No CONTROL_READY ticket: the button cannot start the source early.
        launchJob?.cancel()
        manualJob = scope?.launch { launchEntry(next, activity, manual = true) }
    }

    private suspend fun launchEntry(next: Request, launchContext: Context, manual: Boolean) {
        val ticket = next.ticket
        fun allowed() = policy.current(ticket) && next.canLaunch() && next.sourceAwaiting()
        fun report(message: String) {
            if (!policy.current(ticket)) return
            CarPlusDiagnostics.record(ticket.attempt, ticket.engine, message)
            log("Car+：$message")
        }
        try {
            if (!allowed()) {
                report("未提交入口：会话已变化或当前引擎已有视频，请检查首页状态")
                return
            }
            report("控制已就绪；正在寻找 CarLife 投屏入口（不使用智慧车载总设置页）")
            val entry = resolveEntry { report(it) }
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!allowed()) return
            if (entry == null) {
                report("没有可访问的 CarLife 投屏 Activity；请在智慧车载中点「百度 CarLife+」进入连接页。总设置页不等于引擎已启动")
                return
            }
            val failure = withContext(Dispatchers.Main) {
                ensureActive()
                val eligible = {
                    allowed() && (launchContext !is Activity || foregroundReady(launchContext))
                }
                val submit = {
                    runCatching {
                        val intent = Intent().setComponent(entry)
                        if (launchContext !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Mirrors ColorPlay MainActivity.Q1: explicit CarlifeActivity on the phone display.
                        val options = ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()
                        launchContext.startActivity(intent, options)
                    }
                }
                if (manual) policy.dispatchManual(ticket, eligible, next.sourceAwaiting, submit)
                else policy.dispatch(ticket, eligible, next.sourceAwaiting, submit)
            } ?: return
            kotlin.coroutines.coroutineContext.ensureActive()
            if (failure.isSuccess) {
                report("已${if (manual) "前台" else "自动"}请求 ${entry.flattenToShortString()} / display=0；尚未证明源已送流，若未打开请用前台启动按钮")
            } else {
                report("投屏入口启动失败 ${failure.exceptionOrNull()?.javaClass?.simpleName}：${entry.flattenToShortString()}；未绕过系统权限")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            report("入口解析失败 ${error.javaClass.simpleName}；请保留此诊断")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveEntry(report: (String) -> Unit): ComponentName? {
        val pm = context.packageManager
        val candidates = mutableListOf<Pair<ComponentName, Int>>()
        for (pkg in CarPlusSource.packages) {
            val activities = runCatching {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty().toList()
            }.getOrElse { report("包 $pkg 不可查询：${it.javaClass.simpleName}"); emptyList() }
            for (activity in activities) {
                val score = CarPlusSource.entryScore(pkg, activity.name)
                if (score <= 0) continue
                val component = ComponentName(pkg, activity.name)
                val permissionOk = activity.permission.isNullOrEmpty() ||
                    context.checkSelfPermission(activity.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val accessible = activity.enabled && activity.applicationInfo.enabled && activity.exported && permissionOk
                report("候选 ${component.flattenToShortString()} score=$score enabled=${activity.enabled && activity.applicationInfo.enabled} exported=${activity.exported} permission=$permissionOk")
                if (accessible) candidates += component to score
            }
        }
        return candidates.sortedWith(compareByDescending<Pair<ComponentName, Int>> { it.second }
            .thenBy { it.first.flattenToShortString() }).firstOrNull()?.first
    }

    @Synchronized fun disconnected(attempt: Long, engine: Long) {
        if (!policy.disconnected(attempt, engine)) return
        launchJob?.cancel()
        manualJob?.cancel()
        launchJob = null
        manualJob = null
        request = null
        CarPlusDiagnostics.record(attempt, engine, "控制连接已失效，取消待启动入口")
    }

    @Synchronized fun stop(attempt: Long) {
        if (!policy.stop(attempt)) return
        scope?.cancel()
        scope = null
        launchJob = null
        manualJob = null
        request?.ticket?.let { CarPlusDiagnostics.record(attempt, it.engine, "会话已停止，不再打开源入口") }
        request = null
    }
}
