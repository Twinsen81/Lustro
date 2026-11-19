package io.github.twinsen81.lustro.internal

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class LanAddressesTest {
    private fun address(interfaceName: String, literal: String) = InterfaceAddress(interfaceName, InetAddress.getByName(literal))

    private val device =
        listOf(
            address("rmnet0", "10.20.30.40"),
            address("wlan0", "fe80::1%1"),
            address("wlan0", "2001:db8::5"),
            address("wlan0", "192.168.1.23"),
            address("lo", "127.0.0.1"),
            address("dummy0", "224.0.0.1"),
        )

    @Test
    fun `a wildcard bind lists the device's IPv4 addresses, Wi-Fi first, without loopback or multicast`() {
        assertEquals(listOf("192.168.1.23", "10.20.30.40"), LanAddresses.lanHosts("0.0.0.0") { device })
        assertEquals(listOf("192.168.1.23", "10.20.30.40"), LanAddresses.lanHosts("::") { device })
        assertEquals("127.0.0.1", LanAddresses.clientHost("0.0.0.0"))
    }

    @Test
    fun `without an IPv4 address, a wildcard bind lists the IPv6 ones that are not link-local`() {
        val ipv6Only = listOf(address("wlan0", "fe80::1%1"), address("wlan0", "2001:db8::5"), address("lo", "::1"))
        assertEquals(listOf("2001:db8:0:0:0:0:0:5"), LanAddresses.lanHosts("0.0.0.0") { ipv6Only })
    }

    @Test
    fun `a loopback bind has no LAN hosts and does not list the interfaces`() {
        for (bind in listOf("127.0.0.1", "localhost", "::1")) {
            assertEquals(bind, emptyList<String>(), LanAddresses.lanHosts(bind) { error("not called") })
        }
        assertEquals("127.0.0.1", LanAddresses.clientHost("127.0.0.1"))
    }

    @Test
    fun `a bind to one address is that address for every client`() {
        assertEquals(listOf("192.168.1.23"), LanAddresses.lanHosts("192.168.1.23") { error("not called") })
        assertEquals("192.168.1.23", LanAddresses.clientHost("192.168.1.23"))
    }

    @Test
    fun `urls put an IPv6 address in brackets`() {
        assertEquals("http://192.168.1.23:8080", LanAddresses.url("192.168.1.23", 8080))
        assertEquals("http://[2001:db8::5]:8080", LanAddresses.url("2001:db8::5", 8080))
    }
}
