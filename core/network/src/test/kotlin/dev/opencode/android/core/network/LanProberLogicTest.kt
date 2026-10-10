package dev.opencode.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address arithmetic and the signature of the LAN prober, with no network involved. */
class LanProberLogicTest {

    private val info = """
        {
            "version": "2.0.18",
            "pid": 12345,
            "urls": ["http://192.168.1.50:4096"],
            "paths": {"tmp": "/tmp/opencode"}
        }
    """.trimIndent()

    private val unauthorized = """{"_tag":"UnauthorizedError","message":"Authentication required"}"""

    @Test
    fun theSlash24AroundAPhoneIsTwoHundredFiftyThreeHostsWithThePhoneLeftOut() {
        val hosts = LanProber.hostsOf("192.168.1.42")

        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertTrue(hosts.all { it.startsWith("192.168.1.") })
        assertFalse("the phone is not probed", "192.168.1.42" in hosts)
        assertFalse("the network address is not a host", "192.168.1.0" in hosts)
        assertFalse("the broadcast address is not a host", "192.168.1.255" in hosts)
    }

    @Test
    fun aWiderNetworkIsStillScannedAsTheSlash24AroundThePhone() {
        val hosts = LanProber.hostsOf("10.20.30.40")

        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.20.30.") })
        assertFalse("10.20.30.40" in hosts)
    }

    @Test
    fun aPhoneOnTheFirstOrLastAddressOfItsSlash24StillGetsTheWholeSlash24() {
        assertEquals(253, LanProber.hostsOf("172.16.0.1").size)
        assertEquals("172.16.0.2", LanProber.hostsOf("172.16.0.1").first())
        assertEquals(253, LanProber.hostsOf("172.31.255.254").size)
    }

    @Test
    fun aPrefixOtherThanTwentyFourIsRefused() {
        for (prefix in listOf(16, 23, 25, 32)) {
            assertThrows("a /$prefix", IllegalArgumentException::class.java) {
                LanProber.hostsOf("192.168.1.42", prefix)
            }
        }
    }

    @Test
    fun addressesOutsideTheThreePrivateRangesAreRefused() {
        val refused = listOf(
            "8.8.8.8",
            "127.0.0.1",
            "169.254.10.20",
            "100.64.0.9",
            "203.0.113.5",
            "11.0.0.1",
            "172.15.0.1",
            "172.32.0.1",
            "192.169.0.1",
        )
        for (ip in refused) {
            assertFalse("$ip is not private", LanProber.isPrivateIpv4(ip))
            assertThrows("$ip is refused", IllegalArgumentException::class.java) { LanProber.hostsOf(ip) }
        }
    }

    @Test
    fun theThreePrivateRangesAreIncludedAtTheirEdges() {
        for (ip in listOf("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255")) {
            assertTrue("$ip is private", LanProber.isPrivateIpv4(ip))
        }
    }

    @Test
    fun aStringThatIsNotAWellFormedIpv4AddressIsRefused() {
        val malformed = listOf(
            "",
            "fe80::1",
            "::ffff:192.168.1.1",
            "192.168.1",
            "192.168.1.1.1",
            "192.168.1.300",
            "192.168.01.1",
            "192.168.1.-1",
            "192.168.1.+1",
            " 192.168.1.1",
            "192.168.1.1 ",
        )
        for (ip in malformed) {
            assertFalse("'$ip' is not private IPv4", LanProber.isPrivateIpv4(ip))
            assertThrows("'$ip' is refused", IllegalArgumentException::class.java) { LanProber.hostsOf(ip) }
        }
    }

    @Test
    fun aUnauthorizedAnswerCarryingTheTagIsTheSignature() {
        assertTrue(LanProber.fingerprint(401, unauthorized))
        assertTrue("whitespace inside the JSON does not matter", LanProber.fingerprint(401, """{"_tag" : "UnauthorizedError"}"""))
    }

    @Test
    fun aUnauthorizedAnswerWithoutTheTagIsNotTheSignature() {
        assertFalse(LanProber.fingerprint(401, """{"message":"Not you"}"""))
        assertFalse("a page that only mentions the word is not the server", LanProber.fingerprint(401, "<p>UnauthorizedError</p>"))
        assertFalse(LanProber.fingerprint(401, ""))
    }

    @Test
    fun anInfoAnswerThatDecodesAsTheServerInfoIsTheSignature() {
        assertTrue(LanProber.fingerprint(200, info))
    }

    @Test
    fun aSuccessfulAnswerThatIsNotTheServerInfoIsNotTheSignature() {
        assertFalse(LanProber.fingerprint(200, """{"ok":true}"""))
        assertFalse(LanProber.fingerprint(200, "<html>Welcome</html>"))
        assertFalse(LanProber.fingerprint(200, ""))
    }

    @Test
    fun noOtherStatusIsTheSignatureEvenWithTheTagInTheBody() {
        for (status in listOf(302, 403, 404, 500, 503)) {
            assertFalse("status $status", LanProber.fingerprint(status, unauthorized))
        }
    }
}
