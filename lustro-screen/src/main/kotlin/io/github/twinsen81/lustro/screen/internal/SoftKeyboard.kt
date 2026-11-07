package io.github.twinsen81.lustro.screen.internal

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Whether the soft keyboard shows over a window of the app. The keyboard is a window of
 * another process, so the tree does not have it; a window that makes room for it, as a
 * Compose screen with `imePadding()` does, changes its layout some frames after it shows.
 */
internal object SoftKeyboard {
    // The share of the screen's height that the keyboard takes at least; the status bar alone takes far less.
    private const val MIN_SHARE = 0.15

    fun isShown(root: View): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            } else {
                // Before API 30 no call says it: the keyboard takes the bottom of the window's visible frame.
                val frame = Rect().also(root::getWindowVisibleDisplayFrame)
                val height = root.resources.displayMetrics.heightPixels
                height - frame.height() > height * MIN_SHARE
            }
        } catch (_: RuntimeException) {
            false
        }
}
