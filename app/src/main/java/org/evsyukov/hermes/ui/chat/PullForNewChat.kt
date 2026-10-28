package org.evsyukov.hermes.ui.chat

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Pulling the chat up past its last message starts a new chat, as an overscroll of the list: the
 * drag the list cannot use any more grows [distance], at half speed so the pull feels stiff, and
 * releasing it beyond [threshold] calls [onTrigger]. Dragging back down first shrinks the pull.
 */
@Stable
class PullForNewChat(private val threshold: Float, private val onTrigger: () -> Unit, private val onArmed: () -> Unit) : NestedScrollConnection {
    var distance by mutableFloatStateOf(0f)
        private set
    var enabled = true

    /** 0 at rest, 1 once releasing starts a new chat. */
    val progress: Float get() = (distance / threshold).coerceIn(0f, 1.5f)
    private var armed = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || distance <= 0f || available.y <= 0f) return Offset.Zero
        val used = minOf(available.y, distance * 2)
        pull(distance - used / 2)
        return Offset(0f, used)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        pull(distance - available.y / 2)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (distance <= 0f) return Velocity.Zero
        if (distance >= threshold) onTrigger()
        armed = false
        animate(distance, 0f) { value, _ -> distance = value }
        return available
    }

    private fun pull(value: Float) {
        distance = value.coerceAtLeast(0f)
        if (!armed && distance >= threshold) {
            armed = true
            onArmed()
        } else if (armed && distance < threshold) {
            armed = false
        }
    }
}
