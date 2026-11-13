package spw.cue

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Composable
internal fun SongContextMenu(items: () -> List<ContextMenuItem>, content: @Composable () -> Unit) {
    val hostMenu = LocalContextMenuRepresentation.current
    val largerTextMenu = remember(hostMenu) {
        object : ContextMenuRepresentation {
            @Composable
            override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * 1.125f, density.fontScale * 1.2f)) {
                    hostMenu.Representation(state, items)
                }
            }
        }
    }
    CompositionLocalProvider(LocalContextMenuRepresentation provides largerTextMenu) {
        ContextMenuArea(items = items, content = content)
    }
}
