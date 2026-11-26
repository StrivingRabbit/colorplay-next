package io.github.hypercar.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun floatingChromeTopPadding(): Dp =
    48.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** 仅用于滚动内容末尾避让，不缩短页面背景或滚动视口。 */
@Composable
fun floatingChromeBottomPadding(): Dp =
    120.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
