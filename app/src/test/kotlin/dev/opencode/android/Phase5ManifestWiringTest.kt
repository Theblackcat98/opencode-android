package dev.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the app declares for Phase 5 (plan §6, "Attachments from the phone").
 *
 * **The camera and the share target are declarations, not calls**, and a declaration that is missing
 * fails at runtime with no stack trace: a `TakePicture` intent with no provider to write into is a
 * camera that silently does nothing, and a share-sheet target that is not declared never appears in
 * the chooser at all. The provider's path is asserted too, because a provider that exposed a durable
 * file path would hand a capture to every other app on the phone.
 *
 * The two files are read as sources rather than as merged artefacts, because the merge is a build
 * output and a test that depends on one fails for the wrong reason.
 */
class Phase5ManifestWiringTest {

    private val app = parse("src/main/AndroidManifest.xml")
    private val paths = parse("src/main/res/xml/file_paths.xml")

    @Test
    fun `the camera is optional so the app installs on a phone without one`() {
        val camera = app.elements("uses-feature").single { it.getAttribute("android:name") == "android.hardware.camera" }
        assertEquals("false", camera.attribute("required"))
    }

    @Test
    fun `the camera is a declared permission, which the app asks for on demand`() {
        assertTrue(usesPermission("android.permission.CAMERA"))
    }

    @Test
    fun `a capture provider exists, is not exported, and grants only what a camera needs`() {
        val provider = declarations("provider").single { it.attribute("name").endsWith("FileProvider") }
        // An exported provider here is a place any installed app could write a file this app would
        // then read and send to a server.
        assertEquals("false", provider.attribute("exported"))
        assertEquals("true", provider.attribute("grantUriPermissions"))
        assertEquals("\${applicationId}.captures", provider.attribute("authorities"))
        val meta = provider.elements("meta-data").first()
        assertEquals("@xml/file_paths", meta.attribute("resource"))
    }

    @Test
    fun `the provider exposes one cache path and no durable one`() {
        assertEquals("one cache path, and only one", 1, paths.elements("cache-path").size)
        assertTrue(
            "a durable path would outlive the prompt",
            paths.elements("external-path").isEmpty() && paths.elements("files-path").isEmpty(),
        )
    }

    @Test
    fun `the app is a share target for text and for an image in one filter`() {
        // "Send to OpenCode" (features doc §38). One filter carrying both types, so the chooser shows
        // the app once rather than twice.
        val sendFilters = mainActivity()
            .elements("intent-filter")
            .filter { it.elements("action").any { a -> a.attribute("name") == "android.intent.action.SEND" } }
        val withMime = sendFilters.filter { it.elements("data").isNotEmpty() }
        val types = withMime.flatMap { filter -> filter.elements("data").map { it.attribute("mimeType") } }
        assertTrue("text/plain" in types)
        assertTrue("image/*" in types)
    }

    @Test
    fun `the app still claims the pairing links`() {
        val prefixes = mainActivity().elements("data").map { it.attribute("pathPrefix") }
        assertTrue("/auth/connect/" in prefixes)
    }

    private fun mainActivity(): Element = declarations("activity").single { it.attribute("name").endsWith("MainActivity") }

    private fun usesPermission(name: String): Boolean = app.elements("uses-permission").any { it.getAttribute("android:name") == name }

    private fun declarations(tag: String): List<Element> = app.elements(tag)

    private fun parse(path: String): Element {
        val file = java.io.File(path)
        assertTrue("$path is missing, so the test is asserting nothing", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }
}

/**
 * An attribute of this element, with or without its `android:` prefix.
 *
 * Read from the attribute list rather than through `getAttribute`, because whether a prefixed
 * attribute answers to its qualified name depends on whether the parser was told about namespaces,
 * and a test that fails on that is a test about the parser.
 */
private fun Element.attribute(name: String): String {
    val attributes = this.attributes
    (0 until attributes.length).forEach { index ->
        val item = attributes.item(index)
        if (item.nodeName == "android:$name" || item.nodeName == name) return item.nodeValue.orEmpty()
    }
    return ""
}

/** Every descendant element named [tag], so a declaration nested in the application is found. */
private fun Element.elements(tag: String): List<Element> {
    val found = mutableListOf<Element>()
    collect(this, tag, found)
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
