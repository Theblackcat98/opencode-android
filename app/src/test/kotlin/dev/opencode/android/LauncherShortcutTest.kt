package dev.opencode.android

import dev.opencode.android.feature.requests.notifications.NotificationIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the two static launcher shortcuts promise (matrix J10).
 *
 * **The XML and the Kotlin have to name the same action strings, and nothing at runtime would notice if
 * they drifted.** A shortcut whose action no activity reads does nothing when tapped, with no error and no
 * log line, so the pairing is asserted here instead.
 */
class LauncherShortcutTest {

    private val shortcuts = parse("src/main/res/xml/shortcuts.xml")
    private val manifest = parse("src/main/AndroidManifest.xml")
    private val strings = parse("src/main/res/values/strings.xml")

    @Test
    fun `each shortcut action maps to its destination, and no other action maps to a target`() {
        assertEquals(
            ShortcutDestination.SERVERS,
            LauncherShortcuts.targetOf(LauncherShortcuts.ACTION_SERVERS)?.destination,
        )
        assertEquals(
            ShortcutDestination.NEW_SESSION,
            LauncherShortcuts.targetOf(LauncherShortcuts.ACTION_NEW_SESSION)?.destination,
        )
        assertEquals(
            ShortcutDestination.INBOX,
            LauncherShortcuts.targetOf(LauncherShortcuts.ACTION_INBOX)?.destination,
        )
        assertNull(LauncherShortcuts.targetOf(null))
        // A notification tap has its own action, and it must not be taken for a shortcut.
        assertNull(LauncherShortcuts.targetOf(NotificationIntents.ACTION_OPEN_SESSION))
    }

    @Test
    fun `the shortcut file declares the three ids, each with the action the activity reads`() {
        val declared = declarations(shortcuts, "shortcut").associate { shortcut ->
            val intent = declarations(shortcut, "intent").single()
            shortcut.getAttribute("android:shortcutId") to intent.getAttribute("android:action")
        }
        assertEquals(
            mapOf(
                "servers" to LauncherShortcuts.ACTION_SERVERS,
                "new_session" to LauncherShortcuts.ACTION_NEW_SESSION,
                "shortcut_inbox" to LauncherShortcuts.ACTION_INBOX,
            ),
            declared,
        )
    }

    @Test
    fun `each shortcut is an explicit intent to the main activity`() {
        // An implicit intent would be resolved by whichever app answers the action, and an explicit one is
        // the only form that cannot be answered by another app.
        declarations(shortcuts, "shortcut").forEach { shortcut ->
            val intent = declarations(shortcut, "intent").single()
            assertEquals("dev.opencode.android.MainActivity", intent.getAttribute("android:targetClass"))
        }
    }

    @Test
    fun `every shortcut label is a string the app declares`() {
        val names = declarations(strings, "string").map { it.getAttribute("name") }.toSet()
        val labels = declarations(shortcuts, "shortcut").flatMap { shortcut ->
            listOf("android:shortcutShortLabel", "android:shortcutLongLabel").map { shortcut.getAttribute(it) }
        }
        assertEquals(6, labels.size)
        labels.forEach { label ->
            assertTrue("'$label' is not a string resource", label.startsWith("@string/"))
            assertTrue("'$label' is not declared in the app's strings.xml", label.removePrefix("@string/") in names)
        }
    }

    @Test
    fun `the launcher activity points at the shortcut file`() {
        val activity = declarations(manifest, "activity").single {
            it.getAttribute("android:name") == ".MainActivity"
        }
        val meta = declarations(activity, "meta-data").single {
            it.getAttribute("android:name") == "android.app.shortcuts"
        }
        assertEquals("@xml/shortcuts", meta.getAttribute("android:resource"))
    }

    // ------------------------------------------------------------------ xml

    private fun parse(path: String): Element {
        val file = File(path)
        assertTrue("$path is missing, so the test is asserting nothing", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    /** Every descendant of [root] named [tag], so a declaration nested in a parent is found. */
    private fun declarations(root: Element, tag: String): List<Element> {
        val found = mutableListOf<Element>()
        collect(root, tag, found)
        return found
    }

    private fun collect(node: Element, tag: String, into: MutableList<Element>) {
        val children = node.childNodes
        (0 until children.length).forEach { index ->
            val child = children.item(index) as? Element ?: return@forEach
            if (child.tagName == tag) into.add(child)
            collect(child, tag, into)
        }
    }
}
