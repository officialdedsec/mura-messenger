package com.mura.mesh.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mura.mesh.ui.LocalInTab
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.theme.Mura
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class Tab(val icon: ImageVector, val label: String)
private val TABS = listOf(Tab(MIcons.Send, "Chats"), Tab(MIcons.Status, "Status"), Tab(MIcons.Phone, "Calls"), Tab(MIcons.Mesh, "Mesh"), Tab(MIcons.User, "Me"))

/**
 * Home: Chats · Status · Calls · Mesh · Me in one pager, like WhatsApp — swipe between tabs or tap them.
 * The pill behind the active tab follows your finger, and every tab keeps its scroll position.
 */
@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val pager = rememberPagerState(initialPage = nav.homeTab) { TABS.size }
    val scope = rememberCoroutineScope()

    // Tab chosen elsewhere (a button, a notification) → glide there.
    LaunchedEffect(nav.homeTab) {
        if (pager.currentPage != nav.homeTab) pager.animateScrollToPage(nav.homeTab, animationSpec = tween(320))
    }
    // Swiped → remember it.
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { nav.homeTab = it } }
    // Back from another tab goes to Chats first (like WhatsApp), then leaves the app.
    BackHandler(enabled = pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }

    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        HorizontalPager(
            state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1, key = { it },
        ) { page ->
            CompositionLocalProvider(LocalInTab provides true) {
                when (page) {
                    0 -> ChatsScreen()
                    1 -> StatusListScreen()
                    2 -> CallsScreen()
                    3 -> MeshScreen()
                    else -> SettingsScreen()
                }
            }
        }
        TabBar(position = { pager.currentPage + pager.currentPageOffsetFraction }, current = pager.targetPage) { i ->
            scope.launch { pager.animateScrollToPage(i, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
        }
    }
}

@Composable
private fun TabBar(position: () -> Float, current: Int, onTab: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().background(Mura.Ink).navigationBarsPadding().padding(top = 6.dp, bottom = 8.dp)) {
        val itemW = maxWidth / TABS.size
        // The pill slides continuously with the pager (no jump at the end of a swipe).
        Box(
            Modifier.offset { IntOffset(((itemW.toPx() * position()) + (itemW.toPx() - 56.dp.toPx()) / 2).roundToInt(), 0) }
                .width(56.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(Mura.AmberDeep),
        )
        Row(Modifier.fillMaxWidth()) {
            TABS.forEachIndexed { i, t ->
                val active = i == current
                val tint by animateColorAsState(if (active) Mura.Amber else Mura.Muted, tween(220), label = "tab$i")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                            if (!active) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTab(i)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) { Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
        }
    }
}
