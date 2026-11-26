package io.github.hypercar.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.UpstreamBase
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.components.GlassPanel
import io.github.hypercar.ui.components.InfoRow
import io.github.hypercar.ui.components.SectionLabel
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary
import io.github.hypercar.update.UpdateCenter
import io.github.hypercar.update.UpdateUiState
import kotlinx.coroutines.launch

/**
 * 「关于」页（独立 Tab，位于诊断之后）：版本信息、作者、仓库、以及更新检查。
 *
 * 更新检查逻辑在 [UpdateCenter]，这里只负责呈现：空闲 / 检查中 / 已最新 / 有新版本 / 失败。
 * 启动时的自动检查与弹窗仍在 MainActivity（与页面无关）。
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appIcon = remember(context) {
        context.applicationInfo.loadIcon(context.packageManager).toBitmap(192, 192).asImageBitmap()
    }
    val scope = rememberCoroutineScope()
    val state by UpdateCenter.state.collectAsStateWithLifecycle()
    val checking = state is UpdateUiState.Checking
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastVersionTap by remember { mutableLongStateOf(0L) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(io.github.hypercar.ui.components.floatingChromeTopPadding()))
        // ---- 品牌头 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                bitmap = appIcon,
                contentDescription = "ColorPlay Next 应用启动图标",
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "ColorPlay Next",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = TextPrimary,
                )
                Text(
                    text = "Android 无线 CarPlay 互联\n支持 OPPO Car+ 与系统 CarLife+",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        // ---- 版本 ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = "版本")
            GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                InfoRow(label = "当前版本", value = UpdateCenter.currentVersion)
                InfoRow("versionCode", UpdateCenter.currentBuild.toString(), emphasizeClick = false) {
                    if (!SettingsStore.developerModeEnabled) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastVersionTap > 2_000L) versionTaps = 0
                        lastVersionTap = now
                        versionTaps++
                        if (versionTaps == 10) {
                            SettingsStore.enableDeveloperMode()
                            Toast.makeText(context, "开发者模式已开启", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                InfoRow(label = "核心层", value = "Rust · arm64-v8a")
            }
        }

        // ---- 关于 ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = "关于")
            GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                InfoRow(label = "开源来源", value = "基于 HyperCar")
                InfoRow(label = "原项目作者", value = "fw867")
                InfoRow(label = "底座导入版本", value = "${UpstreamBase.VERSION} / code ${UpstreamBase.CODE}")
                InfoRow(
                    label = "底座导入提交",
                    value = UpstreamBase.COMMIT.take(12),
                    onClick = { openUrl(context, "${UpstreamBase.REPO}/commit/${UpstreamBase.COMMIT}") },
                )
                InfoRow(
                    label = "上游开源仓库",
                    value = UpstreamBase.REPO.removePrefix("https://"),
                    onClick = { openUrl(context, UpstreamBase.REPO) },
                )
                InfoRow(label = "开源许可证", value = "GNU GPL v3")
                Text(
                    text = "ColorPlay Next 基于 HyperCar 修改，保留原项目作者与 GPL 归属。本应用仅用于个人研究，与 BMW / Apple / 高德无隶属关系；按 GPL 许可证提供，不作任何担保。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        // ---- 更新 ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = "更新")
            GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "检查更新仅更新 ColorPlay Next APK，不会直接安装或更新 HyperCar 底座。新底座需由开发者合并上游代码、完成测试并重新签名发布；可通过上方链接查看上游仓库与已导入提交。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(10.dp))
                when (val s = state) {
                    is UpdateUiState.Available -> {
                        Text(
                            text = "发现新版本 ${s.info.version}（当前 ${UpdateCenter.currentVersion}）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MintGreen,
                        )
                        if (s.info.notes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = s.info.notes.take(400),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        CapsuleButton(
                            text = if (s.info.apkUrl != null) "下载新版本" else "打开 Release 页面",
                            onClick = { openUrl(context, s.info.apkUrl ?: s.info.pageUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            filled = true,
                            containerColor = MintGreen,
                        )
                    }

                    is UpdateUiState.UpToDate -> Text(
                        text = "已是最新版本（${s.latest}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )

                    is UpdateUiState.Failed -> Text(
                        text = "检查失败：${s.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralError,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    UpdateUiState.Checking -> Text(
                        text = "正在检查…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )

                    UpdateUiState.Idle -> Text(
                        text = "ColorPlay Next APK 更新来自本项目的 GitHub Releases。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                CapsuleButton(
                    text = if (checking) "检查中…" else "检查更新",
                    onClick = { scope.launch { UpdateCenter.check(context) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !checking,
                    filled = false,
                )
            }
        }
        Spacer(modifier = Modifier.navigationBarsPadding().height(120.dp))
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
