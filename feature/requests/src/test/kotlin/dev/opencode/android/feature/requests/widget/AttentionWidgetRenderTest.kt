package dev.opencode.android.feature.requests.widget

import android.content.Context
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.opencode.android.feature.requests.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The widget's layout, inflated the way a launcher inflates it (matrix J8).
 *
 * **[AttentionWidgetPolicyTest] covers the decision and this covers the half it cannot reach.** A `RemoteViews` that names
 * a view the layout does not have, or a string that does not resolve, fails only when a launcher draws the widget, with no
 * stack trace in the app. Inflating the same views here and reading back what a user would read catches that in the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AttentionWidgetRenderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `the counts read in order, waiting first, and the status line is hidden`() {
        val root = inflate(WidgetCounts(running = 1, waiting = 2))
        assertEquals("2 waiting for you", text(root, R.id.widget_line_one))
        assertEquals("1 running", text(root, R.id.widget_line_two))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_line_two).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_status).visibility)
    }

    @Test
    fun `the zero state reads as nothing running or waiting, with no count lines`() {
        val root = inflate(WidgetCounts(running = 0, waiting = 0))
        assertEquals("Nothing running or waiting", text(root, R.id.widget_status))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_status).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_line_one).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_line_two).visibility)
    }

    @Test
    fun `with no counts the widget asks for the app to be opened`() {
        val root = inflate(null)
        assertEquals("Open OpenCode to see sessions", text(root, R.id.widget_status))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_line_one).visibility)
    }

    @Test
    fun `a tap anywhere on the card is wired to the app`() {
        assertTrue(inflate(null).hasOnClickListeners())
    }

    private fun inflate(counts: WidgetCounts?): View =
        attentionWidgetViews(context, counts).apply(context, FrameLayout(context))

    private fun text(root: View, id: Int): String = root.findViewById<TextView>(id).text.toString()
}
