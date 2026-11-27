package io.github.hypercar.update

import android.content.Context
import io.github.hypercar.BuildConfig
import io.github.hypercar.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * 自动更新：读 GitHub Releases API 比较版本。
 *
 * 本地和 CI 均读取 version.properties，Release tag 为 v<versionName>。
 * 显示版本和安装编号独立递增；更新比较使用显示版本，不从版本名推算安装编号。
 * 重复开发构建保留当前版本，发布新代码前统一升级版本文件。
 *
 * 不引第三方 HTTP/JSON 依赖：`HttpURLConnection` + `org.json` 足够，
 * 也避免为了一个检查功能把 APK 撑大（本项目刚把体积压到 3.3MB）。
 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
    val publishedAt: String,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latest: String) : UpdateUiState
    data class Available(val info: ReleaseInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

object UpdateCenter {

    private const val PREFS = "hypercar_update"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_DISMISSED = "dismissed_tag"

    /** 自动检查间隔：6 小时（手动检查不受限）。 */
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state

    /** 当前安装版本（来自共享版本文件）。 */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    /** 启动时是否该自动检查（距上次检查超过 6 小时）。 */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    /** 用户已经点过"稍后"的版本不再自动弹窗（仍可在设置里手动检查）。 */
    fun isDismissed(context: Context, tag: String): Boolean =
        prefs(context).getString(KEY_DISMISSED, null) == tag

    fun dismiss(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_DISMISSED, tag).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val checkLock = Any()
    private var checkGeneration = 0L

    suspend fun setReceiveAlphaUpdates(context: Context, enabled: Boolean) {
        synchronized(checkLock) {
            SettingsStore.receiveAlphaUpdates = enabled
            checkGeneration++
            _state.value = UpdateUiState.Idle
        }
        check(context, clearDismissed = true)
    }

    /** 手动检查不受自动检查间隔限制；渠道切换使旧请求立即失效。 */
    suspend fun check(context: Context, clearDismissed: Boolean = false) {
        val (generation, receiveAlpha) = synchronized(checkLock) {
            (++checkGeneration to SettingsStore.receiveAlphaUpdates).also {
                _state.value = UpdateUiState.Checking
            }
        }
        withContext(Dispatchers.IO) {
            val editor = prefs(context).edit()
            synchronized(checkLock) {
                if (generation != checkGeneration) return@withContext
                editor.putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                if (clearDismissed) editor.remove(KEY_DISMISSED)
                editor.apply()
            }
            val result = try {
                val latest = fetchLatest(context, receiveAlpha)
                when {
                    latest == null -> UpdateUiState.Failed("所选渠道暂无可用的 Release")
                    isNewer(latest.version, currentVersion) -> UpdateUiState.Available(latest)
                    else -> UpdateUiState.UpToDate(latest.version)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                UpdateUiState.Failed(e.message ?: "检查失败")
            }
            synchronized(checkLock) {
                if (generation == checkGeneration) _state.value = result
            }
        }
    }

    private suspend fun fetchLatest(context: Context, receiveAlpha: Boolean): ReleaseInfo? = withContext(Dispatchers.IO) {
        // 会话期间进程被绑到车机热点（无外网）——必须挑一条真有外网的网络，
        // 否则「检查更新」100% 失败（2026-10-25 验证）。
        val conn = io.github.hypercar.net.InternetRoute.open(context, releaseApiUrl(receiveAlpha)).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub 要求带 User-Agent，否则 403
            setRequestProperty("User-Agent", "ColorPlay-Next/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 404) return@withContext null
            if (code == 403) {
                // 未认证请求 60 次/小时；超限时给出可读提示
                throw IllegalStateException("GitHub API 限流（稍后再试）")
            }
            if (code !in 200..299) throw IllegalStateException("GitHub 返回 HTTP $code")

            parseLatestRelease(conn.inputStream.bufferedReader().use { it.readText() }, receiveAlpha)
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(latest: String, current: String): Boolean =
        compareReleaseVersions(latest, current)?.let { it > 0 } ?: false
}

internal fun isAlphaVersion(version: String): Boolean =
    Regex("-alpha\\.[0-9]+(?:\\+.*)?$").containsMatchIn(version)

internal fun resolveReceiveAlphaUpdates(stored: Boolean?, currentVersion: String): Boolean =
    stored ?: isAlphaVersion(currentVersion)

internal fun releaseApiUrl(receiveAlpha: Boolean): String {
    val releases = "https://api.github.com/repos/StrivingRabbit/colorplay-next/releases"
    return if (receiveAlpha) "$releases?per_page=100" else "$releases/latest"
}

private val alphaTag = Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-alpha\\.(0|[1-9][0-9]*)")
private val stableTag = Regex("[vV]?[0-9]+(?:\\.[0-9]+)*")

private fun eligibleRelease(json: JSONObject, receiveAlpha: Boolean): Boolean {
    if (json.optBoolean("draft")) return false
    val tag = json.optString("tag_name")
    return if (json.optBoolean("prerelease")) receiveAlpha && alphaTag.matches(tag)
    else stableTag.matches(tag)
}

/** 开启测试版时比较所有合法可下载的正式版和 Alpha；关闭时只接受 latest 正式版。 */
internal fun parseLatestRelease(payload: String, receiveAlpha: Boolean): ReleaseInfo? {
    val json = if (receiveAlpha) {
        val releases = JSONArray(payload)
        (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
            .filter {
                eligibleRelease(it, receiveAlpha = true) && releaseApk(it, requireUploaded = true) != null
            }
            .maxWithOrNull { a, b ->
                compareReleaseVersions(a.getString("tag_name"), b.getString("tag_name")) ?: 0
            }
    } else {
        JSONObject(payload).takeIf { eligibleRelease(it, receiveAlpha = false) }
    } ?: return null
    val tag = json.optString("tag_name").ifBlank { return null }
    val apk = releaseApk(json, requireUploaded = receiveAlpha)
    return ReleaseInfo(
        tag = tag,
        version = tag.removePrefix("v").removePrefix("V"),
        notes = json.optString("body").trim(),
        pageUrl = json.optString("html_url"),
        apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
        apkSize = apk?.optLong("size") ?: 0L,
        publishedAt = json.optString("published_at"),
    )
}

private fun releaseApk(json: JSONObject, requireUploaded: Boolean): JSONObject? =
    json.optJSONArray("assets")?.let { assets ->
        (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull {
                it.optString("name").endsWith(".apk", ignoreCase = true) &&
                    (!requireUploaded || (it.optString("state") == "uploaded" &&
                        it.optString("browser_download_url").isNotBlank()))
            }
    }

internal data class ParsedReleaseVersion(val core: List<BigInteger>, val prerelease: List<String>)

internal fun parseReleaseVersion(version: String): ParsedReleaseVersion? {
    val value = version.removePrefix("v").removePrefix("V").substringBefore('+')
    val core = value.substringBefore('-')
    if (!Regex("[0-9]+(?:\\.[0-9]+)*").matches(core)) return null
    val suffix = value.substringAfter('-', "")
    if ('-' in value && !Regex("[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*").matches(suffix)) return null
    return ParsedReleaseVersion(
        core.split('.').map { it.toBigInteger() },
        if (suffix.isEmpty()) emptyList() else suffix.split('.'),
    )
}

/** 保留旧多段数字版本；同 core 下正式版高于预发布，数字序号按数值比较。 */
internal fun compareReleaseVersions(latest: String, current: String): Int? {
    val a = parseReleaseVersion(latest) ?: return null
    val b = parseReleaseVersion(current) ?: return null
    for (i in 0 until maxOf(a.core.size, b.core.size)) {
        val result = a.core.getOrElse(i) { BigInteger.ZERO }
            .compareTo(b.core.getOrElse(i) { BigInteger.ZERO })
        if (result != 0) return result
    }
    if (a.prerelease.isEmpty() || b.prerelease.isEmpty()) {
        return b.prerelease.size.compareTo(a.prerelease.size)
    }
    for (i in 0 until minOf(a.prerelease.size, b.prerelease.size)) {
        val x = a.prerelease[i]
        val y = b.prerelease[i]
        val xn = x.toBigIntegerOrNull()
        val yn = y.toBigIntegerOrNull()
        val result = when {
            xn != null && yn != null -> xn.compareTo(yn)
            xn != null -> -1
            yn != null -> 1
            else -> x.compareTo(y)
        }
        if (result != 0) return result
    }
    return a.prerelease.size.compareTo(b.prerelease.size)
}
