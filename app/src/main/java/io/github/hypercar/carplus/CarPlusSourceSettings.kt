package io.github.hypercar.carplus

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.core.LinkState
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.ui.components.CapsuleButton
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarSourceSelector(
    current: String?,
    shizukuReady: Boolean,
    carWithInstalled: Boolean,
    carPlusInstalled: Boolean,
    connected: Boolean,
    onSelect: (String) -> Unit,
) {
    val choices = carSourceChoices(current, shizukuReady, carWithInstalled, carPlusInstalled, connected)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice.selected,
                enabled = choice.enabled,
                onClick = { onSelect(choice.key) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MintGreen.copy(alpha = 0.16f),
                    activeContentColor = MintGreen,
                    activeBorderColor = MintGreen.copy(alpha = 0.55f),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = TextSecondary,
                    inactiveBorderColor = StrokeGlass,
                    disabledActiveContainerColor = MintGreen.copy(alpha = 0.10f),
                    disabledActiveContentColor = MintGreen,
                    disabledActiveBorderColor = MintGreen.copy(alpha = 0.35f),
                ),
                icon = {},
                label = {
                    Text(choice.label, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

@Composable
internal fun CarPlusConnectionHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text("连接帮助", color = TextSecondary)
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "收起连接帮助" else "展开连接帮助", tint = TextSecondary)
    }
    if (expanded) {
        val status by CarPlusDiagnostics.status.collectAsStateWithLifecycle()
        val session by HyperCarRepo.snapshots.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text("未显示画面时可尝试前台启动，或在手机 Car+ 中打开「百度 CarLife+」。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            CapsuleButton("前台启动 Car+ 画面",
                onClick = { activity?.let(HyperCarRepo::launchCarPlusFromForeground) },
                enabled = activity != null && session.link is LinkState.Connected,
                modifier = Modifier.fillMaxWidth(), filled = false)
        }
    }
}

@Composable
internal fun CarPlusSourceCard(current: String?, installed: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val selected = CarPlusSource.matches(current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Car+ 车联", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Text(if (installed) "连接就绪后自动启动 Car+ 画面，无需 Shizuku。"
            else "未检测到可用的 Car+，请先安装或启用手机自带车联。",
            color = if (installed) TextSecondary else AmberWarning, style = MaterialTheme.typography.bodySmall)
        if (!selected) {
            CapsuleButton("选择 Car+ 车联", onClick = onSelect, enabled = enabled && installed,
                modifier = Modifier.fillMaxWidth(), filled = false)
        } else {
            Text("当前画面来源", color = MintGreen, style = MaterialTheme.typography.labelMedium)
            CarPlusConnectionHelp()
        }
    }
}
