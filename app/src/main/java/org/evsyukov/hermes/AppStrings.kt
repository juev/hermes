package org.evsyukov.hermes

import android.content.res.Resources
import androidx.annotation.StringRes

/** Resolves string resources for code that has no Context, such as the controllers and the network layer. */
class AppStrings(private val resources: Resources) {
    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)
}
