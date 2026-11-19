package io.github.twinsen81.lustro.internal

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One address of one network interface of the device. */
internal class InterfaceAddress(val interfaceName: String, val address: InetAddress)

/**
 * Works out the hosts that a client can connect to, from the address the
 * server binds. Only literal addresses are recognised: a host name is never
 * resolved, because a DNS lookup can block.
 */
internal object LanAddresses {
    private const val LOOPBACK = "127.0.0.1"
    private val WILDCARDS = setOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0", "[::]")
    private val LOOPBACKS = setOf("localhost", "::1", "0:0:0:0:0:0:0:1", "[::1]")

    // The interfaces of Wi-Fi and Ethernet, which a computer on the same network reaches,
    // come before the others, such as a cellular one.
    private val LAN_INTERFACE_PREFIXES = listOf("wlan", "eth")

    fun isWildcard(bindAddress: String): Boolean = bindAddress.trim() in WILDCARDS

    fun isLoopback(bindAddress: String): Boolean = bindAddress.trim().let { it in LOOPBACKS || it.startsWith("127.") }

    /** The host that a client on the device, or through `adb forward`, connects to. */
    fun clientHost(bindAddress: String): String = if (isWildcard(bindAddress)) LOOPBACK else bindAddress.trim().removeSurrounding("[", "]")

    /**
     * The hosts that a client on the same network connects to. [addresses] is called only for a
     * wildcard bind. IPv6 addresses only when the device has no IPv4 one: a device often has
     * several IPv6 addresses for each interface, which make the list long, and a computer on the
     * same Wi-Fi reaches the IPv4 one.
     */
    fun lanHosts(bindAddress: String, addresses: () -> List<InterfaceAddress>): List<String> =
        when {
            isWildcard(bindAddress) -> {
                val usable = addresses().filter { it.address.isUsableFromLan() }
                val ipv4 = usable.filterNot { it.address is Inet6Address }
                (ipv4.ifEmpty { usable })
                    .sortedBy { entry -> LAN_INTERFACE_PREFIXES.none { entry.interfaceName.startsWith(it) } }
                    .map { it.address.hostAddress.orEmpty().substringBefore('%') }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }
            isLoopback(bindAddress) -> emptyList()
            else -> listOf(bindAddress.trim().removeSurrounding("[", "]"))
        }

    /** `http://<address>:<port>`, with an IPv6 address in brackets. */
    fun url(address: String, port: Int): String = if (':' in address) "http://[$address]:$port" else "http://$address:$port"

    /** The addresses of the device's interfaces that are up. Empty when the platform refuses to list them. */
    fun deviceAddresses(): List<InterfaceAddress> =
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().map { InterfaceAddress(nif.name, it) } }
        } catch (_: Exception) {
            emptyList()
        }

    private fun InetAddress.isUsableFromLan(): Boolean =
        !isLoopbackAddress && !isLinkLocalAddress && !isAnyLocalAddress && !isMulticastAddress
}
