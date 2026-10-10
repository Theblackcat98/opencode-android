package dev.opencode.android.feature.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrTest {

    private val link = "http://192.168.1.50:4096/auth/connect/abc_123"

    @Test
    fun theMatrixIsSquare() {
        val matrix = qrMatrix(link)

        assertTrue(matrix.isNotEmpty())
        matrix.forEach { row -> assertEquals(matrix.size, row.size) }
    }

    @Test
    fun itsSideIsAQrVersionSize() {
        // A version-v QR code is 17 + 4v modules on a side, and version 1 is the smallest.
        val side = qrMatrix(link).size

        assertTrue(side >= 21)
        assertEquals(0, (side - 17) % 4)
    }

    @Test
    fun hasTheThreeFinderPatternsInTheCorners() {
        val matrix = qrMatrix(link)
        val side = matrix.size

        assertFinderAt(matrix, column = 0, row = 0)
        assertFinderAt(matrix, column = side - 7, row = 0)
        assertFinderAt(matrix, column = 0, row = side - 7)
    }

    @Test
    fun theSameLinkGivesTheSameMatrix() {
        assertEquals(qrMatrix(link).map { it.toList() }, qrMatrix(link).map { it.toList() })
    }

    @Test
    fun aDifferentCodeGivesADifferentMatrix() {
        assertNotEquals(
            qrMatrix(link).map { it.toList() },
            qrMatrix("http://192.168.1.50:4096/auth/connect/xyz_789").map { it.toList() },
        )
    }

    /** A finder pattern is a 7 by 7 square: a dark ring, a light ring inside it, and a dark 3 by 3 centre. */
    private fun assertFinderAt(matrix: Array<BooleanArray>, column: Int, row: Int) {
        for (dy in 0 until 7) {
            for (dx in 0 until 7) {
                val onRing = dx == 0 || dx == 6 || dy == 0 || dy == 6
                val inCentre = dx in 2..4 && dy in 2..4
                val expected = onRing || inCentre
                assertEquals("module at ($column+$dx, $row+$dy)", expected, matrix[row + dy][column + dx])
            }
        }
        // The separator just outside the pattern is light, so the pattern is isolated.
        if (column > 0) assertFalse(matrix[row + 3][column - 1])
        if (row > 0) assertFalse(matrix[row - 1][column + 3])
    }
}
