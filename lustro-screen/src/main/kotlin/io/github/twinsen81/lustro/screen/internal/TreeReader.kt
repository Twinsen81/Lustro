package io.github.twinsen81.lustro.screen.internal

import android.content.res.Resources
import android.graphics.Rect
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Reads the windows of the app on the main thread: the View tree of each, and the
 * semantics tree of each Compose host in it through [compose], which is `null` in an
 * app without Compose UI. A View that is `GONE` is left out with its children; one
 * that is `INVISIBLE` is in the tree with no visible part.
 */
internal class TreeReader(private val compose: ComposeReader?) {
    fun read(roots: List<View>, source: String, merged: Boolean): ScreenWindows {
        val errors = ArrayList<String>(0)
        val windows = roots.map { window(it, merged, errors) }
        return ScreenWindows(windows, source, errors.firstOrNull())
    }

    private fun window(root: View, merged: Boolean, errors: MutableList<String>): ScreenWindow {
        val nodes = ArrayList<ScreenNode>()
        walk(root, parent = null, merged, nodes, errors)
        val params = root.layoutParams as? WindowManager.LayoutParams
        val onScreen = IntArray(2).also(root::getLocationOnScreen)
        return ScreenWindow(
            id = nodes.firstOrNull()?.id ?: viewId(root),
            type = params?.type,
            kind = windowKind(params?.type),
            title = params?.title?.toString()?.takeIf { it.isNotEmpty() },
            hasWindowFocus = root.hasWindowFocus(),
            visible = root.isShown && root.windowVisibility == View.VISIBLE,
            bounds = ScreenRect(onScreen[0], onScreen[1], onScreen[0] + root.width, onScreen[1] + root.height),
            nodes = nodes,
        )
    }

    private fun walk(view: View, parent: String?, merged: Boolean, out: MutableList<ScreenNode>, errors: MutableList<String>) {
        if (view.visibility == View.GONE) return
        val host = compose?.takeIf { it.isHost(view) }
        val node = viewNode(view, parent, scrolls = host == null)
        out += node
        if (host != null) {
            // A Compose UI that is older or newer than the tab knows can lack a call that
            // the tab makes: the host then has no semantics nodes, and the read goes on.
            val semantics = ArrayList<ScreenNode>()
            try {
                host.read(view, node, merged, semantics)
                out += semantics
            } catch (e: LinkageError) {
                errors += "Could not read the Compose semantics tree: $e"
            } catch (e: RuntimeException) {
                errors += "Could not read the Compose semantics tree: $e"
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let { walk(it, node.id, merged, out, errors) }
            }
        }
    }

    // A Compose host says that it scrolls when its content does; its semantics nodes say how far.
    private fun viewNode(view: View, parent: String?, scrolls: Boolean): ScreenNode {
        val inWindow = IntArray(2).also(view::getLocationInWindow)
        val onScreen = IntArray(2).also(view::getLocationOnScreen)
        val bounds = ScreenRect(inWindow[0], inWindow[1], inWindow[0] + view.width, inWindow[1] + view.height)
        val screenBounds = ScreenRect(onScreen[0], onScreen[1], onScreen[0] + view.width, onScreen[1] + view.height)
        val textView = view as? TextView
        val editable = view is EditText
        val password = textView != null && isPassword(textView)
        val shown = textView?.text?.toString()?.let { if (password) mask(it) else it }
        val actions = ArrayList<String>(2)
        if (view.isClickable) actions += ScreenNode.ACTION_CLICK
        if (view.isLongClickable) actions += ScreenNode.ACTION_LONG_CLICK
        if (editable && view.isEnabled) actions += ScreenNode.ACTION_SET_TEXT
        val horizontal = if (scrolls) scroll(view, horizontal = true) else null
        val vertical = if (scrolls) scroll(view, horizontal = false) else null
        if (horizontal != null || vertical != null) actions += ScreenNode.ACTION_SCROLL_BY
        return ScreenNode(
            id = viewId(view),
            parent = parent,
            kind = NodeKind.VIEW,
            className = view.javaClass.name,
            resourceId = resourceName(view),
            testTag = (view.tag as? CharSequence)?.toString(),
            text = if (!editable && !shown.isNullOrEmpty()) listOf(shown) else emptyList(),
            contentDescription = view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty(),
            editableText = if (editable) shown.orEmpty() else null,
            password = password,
            error = (view as? TextView)?.error?.toString(),
            bounds = bounds,
            screenBounds = screenBounds,
            visibleBounds = visibleBounds(view, onScreen[0] - inWindow[0], onScreen[1] - inWindow[1]),
            enabled = view.isEnabled,
            focused = view.isFocused,
            selected = view.isSelected.takeIf { it },
            toggle = (view as? Checkable)?.let { if (it.isChecked) "on" else "off" },
            actions = actions,
            horizontalScroll = horizontal,
            verticalScroll = vertical,
        )
    }

    private fun visibleBounds(view: View, dx: Int, dy: Int): ScreenRect? {
        if (!view.isShown) return null
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return null
        return ScreenRect(rect.left, rect.top, rect.right, rect.bottom).offset(dx, dy).takeUnless { it.isEmpty }
    }

    private fun scroll(view: View, horizontal: Boolean): ScrollRange? {
        val forward = if (horizontal) view.canScrollHorizontally(1) else view.canScrollVertically(1)
        val backward = if (horizontal) view.canScrollHorizontally(-1) else view.canScrollVertically(-1)
        if (!forward && !backward) return null
        val offset = ScrollMethods.call(view, if (horizontal) ScrollMethods.horizontalOffset else ScrollMethods.verticalOffset)
        val range = ScrollMethods.call(view, if (horizontal) ScrollMethods.horizontalRange else ScrollMethods.verticalRange)
        val extent = ScrollMethods.call(view, if (horizontal) ScrollMethods.horizontalExtent else ScrollMethods.verticalExtent)
        val value = offset ?: if (horizontal) view.scrollX else view.scrollY
        // A View that can scroll but has no range beyond its extent does not say how far.
        val max = if (range != null && extent != null && range > extent) range - extent else null
        return ScrollRange(value.toFloat(), max?.toFloat(), reverse = false, forward = forward, backward = backward)
    }

    private fun resourceName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID || id ushr RESOURCE_PACKAGE_SHIFT == 0) return null
        return try {
            view.resources.getResourceName(id)
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    private fun isPassword(view: TextView): Boolean {
        if (view.transformationMethod is PasswordTransformationMethod) return true
        val type = view.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return type == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            type == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            type == InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /**
     * `View.computeVerticalScrollOffset()` and the others are protected, but every
     * scrolling View implements them: a ScrollView, a RecyclerView, a ListView, and a
     * WebView. They are public SDK methods, so reflection may call them.
     */
    private object ScrollMethods {
        val horizontalOffset: Method? = method("computeHorizontalScrollOffset")
        val horizontalRange: Method? = method("computeHorizontalScrollRange")
        val horizontalExtent: Method? = method("computeHorizontalScrollExtent")
        val verticalOffset: Method? = method("computeVerticalScrollOffset")
        val verticalRange: Method? = method("computeVerticalScrollRange")
        val verticalExtent: Method? = method("computeVerticalScrollExtent")

        fun call(view: View, method: Method?): Int? =
            try {
                method?.invoke(view) as? Int
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: RuntimeException) {
                null
            }

        private fun method(name: String): Method? =
            try {
                View::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: RuntimeException) {
                null
            }
    }

    companion object {
        // Resource ids have the package in their top byte; a generated view id has none.
        private const val RESOURCE_PACKAGE_SHIFT = 24

        // The number of each View that a read has seen, for as long as the View lives. Read
        // and written on the main thread only.
        private val viewNumbers = WeakHashMap<View, Int>()
        private var lastViewNumber = 0

        /**
         * The id of a View node: `v` and a number that the tab gives the View the first time
         * a read sees it, so it stays the same from read to read while the View lives. A
         * Compose host's semantics nodes take it as their prefix.
         */
        fun viewId(view: View): String = "v" + viewNumbers.getOrPut(view) { ++lastViewNumber }

        /** One dot for each character, as the screen shows a password. */
        fun mask(text: String): String = "•".repeat(text.length)

        fun windowKind(type: Int?): String =
            when (type) {
                null -> "other"
                WindowManager.LayoutParams.TYPE_BASE_APPLICATION -> "activity"
                WindowManager.LayoutParams.TYPE_APPLICATION, WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG -> "dialog"
                in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW -> "popup"
                in WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW..WindowManager.LayoutParams.LAST_SYSTEM_WINDOW -> "system"
                else -> "other"
            }
    }
}
