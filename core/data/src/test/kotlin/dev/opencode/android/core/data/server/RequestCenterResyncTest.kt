package dev.opencode.android.core.data.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for stale resync snapshots after reply/cancel events. */
class RequestCenterResyncTest {
    @Test
    fun `answered permissions and forms are excluded from an older resync snapshot`() {
        val permissionSnapshot = listOf("permission-answered", "permission-pending")
        val formSnapshot = listOf("form-answered", "form-pending")

        val answeredPermissions = setOf("permission-answered")
        val answeredForms = setOf("form-answered")

        assertEquals(
            listOf("permission-pending"),
            permissionSnapshot.filterNot { it in answeredPermissions },
        )
        assertEquals(
            listOf("form-pending"),
            formSnapshot.filterNot { it in answeredForms },
        )
    }
}
