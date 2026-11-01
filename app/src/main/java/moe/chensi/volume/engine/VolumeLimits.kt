package moe.chensi.volume.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** Global, Compose-observable limits for the per-app volume sliders. */
object VolumeLimits {
    /** 1 = 100%. Raised to 3 (300%) when the session effect engine is active. */
    var max by mutableFloatStateOf(1f)
}
