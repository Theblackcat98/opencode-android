package dev.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the app declares for Phase 4 (plan §6).
 *
 * **A manifest is the one thing this phase ships that no test would otherwise look at, and a
 * foreground service is declared rather than called.** A missing `FOREGROUND_SERVICE_DATA_SYNC`, a
 * service without `android:foregroundServiceType`, or a receiver that is exported would each fail at
 * runtime — on a locked phone, with no stack trace and no log — so they are asserted here instead.
 *
 * The two manifests are read as sources rather than as a merged artefact, because the merge is a
 * build output and a test that depends on one is a test that fails for the wrong reason.
 */
class ManifestWiringTest {

    private val app = parse("src/main/AndroidManifest.xml")
    private val requests = parse("../feature/requests/src/main/AndroidManifest.xml")

    @Test
    fun `the notification permission is declared`() {
        // Android 13 and later refuse every notification without it, which is the whole of the
        // "a permission request shows a notification" criterion.
        assertTrue(usesPermission(app, "android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun `the foreground service permissions are declared`() {
        assertTrue(usesPermission(app, "android.permission.FOREGROUND_SERVICE"))
        assertTrue(usesPermission(app, "android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test
    fun `the battery-optimisation permission is deliberately absent`() {
        // It is for apps whose core function breaks under doze. This one stops its service when there
        // is nothing to do, and the guidance sends the user to the system list instead, which needs
        // no permission. Declaring it would be asking for a permission the app cannot justify.
        assertFalse(usesPermission(app, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }

    @Test
    fun `the connection service is declared, unexported, and of the type the spike selected`() {
        val service = declarations(requests, "service").single { it.getAttribute("android:name").endsWith("ConnectionService") }
        assertEquals("false", service.getAttribute("android:exported"))
        assertEquals("dataSync", service.getAttribute("android:foregroundServiceType"))
    }

    @Test
    fun `the action receiver is declared, unexported, and filters one action`() {
        val receiver = declarations(requests, "receiver")
            .single { it.getAttribute("android:name").endsWith("NotificationActionReceiver") }
        // An exported receiver here is a message any installed app could send carrying "answer this
        // permission" as a payload.
        assertEquals("false", receiver.getAttribute("android:exported"))
        val actions = receiver.getElementsByTagName("action")
        assertEquals(1, actions.length)
        val action = actions.item(0) as Element
        assertEquals("dev.opencode.android.action.PERFORM_ATTENTION_ACTION", action.getAttribute("android:name"))
    }

    @Test
    fun `the activity still claims the pairing links, and the manifest is still one application`() {
        val activity = declarations(app, "activity").single()
        val views = activity.getElementsByTagName("data")
        val paths = (0 until views.length).map { (views.item(it) as Element).getAttribute("android:pathPrefix") }
        assertTrue(paths.contains("/auth/connect/"))
        assertEquals(1, declarations(app, "application").size)
    }

    // ------------------------------------------------------------------ xml

    private fun parse(path: String): Element {
        val file = java.io.File(path)
        assertTrue("${path} is missing, so the test is asserting nothing", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

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

    private fun usesPermission(root: Element, name: String): Boolean =
        declarations(root, "uses-permission").any { it.getAttribute("android:name") == name }
}
