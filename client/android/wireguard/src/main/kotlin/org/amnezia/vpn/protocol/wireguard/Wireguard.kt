package org.amnezia.vpn.protocol.wireguard

import android.net.VpnService.Builder
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.amnezia.awg.GoBackend
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.Statistics
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.util.LibraryLoader.loadSharedLibrary
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.asSequence
import org.amnezia.vpn.util.net.InetEndpoint
import org.amnezia.vpn.util.net.InetNetwork
import org.amnezia.vpn.util.net.parseInetAddress
import org.amnezia.vpn.util.optStringOrNull
import org.json.JSONObject

private const val TAG = "Wireguard"

open class Wireguard : Protocol() {

    private var tunnelHandle: Int = -1
    protected open val ifName: String = "amn0"

    override val statistics: Statistics
        get() {
            if (tunnelHandle == -1) return Statistics.EMPTY_STATISTICS
            val config = GoBackend.awgGetConfig(tunnelHandle) ?: return Statistics.EMPTY_STATISTICS
            return Statistics.build {
                var optsCount = 0
                config.splitToSequence("\n").forEach { line ->
                    with(line) {
                        when {
                            startsWith("rx_bytes=") -> setRxBytes(substring(9).toLong()).also { ++optsCount }
                            startsWith("tx_bytes=") -> setTxBytes(substring(9).toLong()).also { ++optsCount }
                            else -> {}
                        }
                    }
                    if (optsCount == 2) return@forEach
                }
            }
        }

    override fun internalInit() {
        if (!isInitialized) loadSharedLibrary(context, "wg-go")
    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        val wireguardConfig = parseConfig(config)
        val startTime = System.currentTimeMillis()
        start(wireguardConfig, vpnBuilder, protect)
        waitForConnection(startTime)
        state.value = CONNECTED
    }

    private suspend fun waitForConnection(startTime: Long) {
        Log.d(TAG, "Waiting for connection")
        withContext(Dispatchers.IO) {
            val time = String.format(Locale.ROOT,"%.3f", startTime / 1000.0)
            try {
                delay(1000)
                var log = getLogcat(time)
                Log.d(TAG, "First waiting log: $log")
                // check that there is a connection log,
                // to avoid infinite connection
                if (!log.contains("Attaching to interface")) {
                    Log.w(TAG, "Logs do not contain a connection log")
                    return@withContext
                }
                while (!log.contains("Received handshake response")) {
                    delay(1000)
                    log = getLogcat(time)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to get logcat: $e")
            }
        }
    }

    private fun getLogcat(time: String): String =
        ProcessBuilder("logcat", "--buffer=main", "--format=raw", "*:S AmneziaWG/awg0", "-t", time)
            .redirectErrorStream(true)
            .start()
            .inputStream.reader().readText()

    protected open fun parseConfig(config: JSONObject): WireguardConfig {
        val configData = config.getJSONObject("wireguard_config_data")
        return WireguardConfig.build {
            configWireguard(config, configData)
            configSplitTunneling(config)
            configAppSplitTunneling(config)
        }
    }

    protected fun WireguardConfig.Builder.configWireguard(config: JSONObject, configData: JSONObject) {
        configData.getString("client_ip").split(",").map { address ->
            InetNetwork.parse(address.trim())
        }.forEach(::addAddress)

        config.optStringOrNull("dns1")?.let { dns ->
            addDnsServer(parseInetAddress(dns.trim()))
        }

        config.optStringOrNull("dns2")?.let { dns ->
            addDnsServer(parseInetAddress(dns.trim()))
        }

        val defRoutes = hashSetOf(
            InetNetwork("0.0.0.0", 0),
            InetNetwork("::", 0)
        )
        val routes = hashSetOf<InetNetwork>()
        configData.getJSONArray("allowed_ips").asSequence<String>().map { route ->
            InetNetwork.parse(route.trim())
        }.forEach(routes::add)
        // if the allowed IPs list contains at least one non-default route, disable global split tunneling
        if (routes.any { it !in defRoutes }) disableSplitTunneling()
        addRoutes(routes)

        configData.optStringOrNull("mtu")?.let { setMtu(it.toInt()) }

        val host = configData.getString("hostName").let { parseInetAddress(it.trim()) }
        val port = configData.getInt("port")
        setEndpoint(InetEndpoint(host, port))

        configData.optStringOrNull("persistent_keep_alive")?.let { setPersistentKeepalive(it.toInt()) }
        configData.getString("client_priv_key").let { setPrivateKeyHex(it.base64ToHex()) }
        configData.getString("server_pub_key").let { setPublicKeyHex(it.base64ToHex()) }
        configData.optStringOrNull("psk_key")?.let { setPreSharedKeyHex(it.base64ToHex()) }
    }

    private fun start(config: WireguardConfig, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        if (tunnelHandle != -1) {
            Log.w(TAG, "Tunnel already up")
            return
        }

        buildVpnInterface(config, vpnBuilder)

        vpnBuilder.establish().use { tunFd ->
            if (tunFd == null) {
                throw VpnStartException("Create VPN interface: permission not granted or revoked")
            }
            Log.i(TAG, "awg-go backend ${GoBackend.awgVersion()}")
            tunnelHandle = GoBackend.awgTurnOn(ifName, tunFd.detachFd(), config.toWgUserspaceString())
        }

        if (tunnelHandle < 0) {
            tunnelHandle = -1
            throw VpnStartException("Wireguard tunnel creation error")
        }

        if (!protect(GoBackend.awgGetSocketV4(tunnelHandle)) || !protect(GoBackend.awgGetSocketV6(tunnelHandle))) {
            GoBackend.awgTurnOff(tunnelHandle)
            tunnelHandle = -1
            throw VpnStartException("Protect VPN interface: permission not granted or revoked")
        }
    }

    override fun stopVpn() {
        if (tunnelHandle == -1) {
            Log.w(TAG, "Tunnel already down")
            return
        }
        val handleToClose = tunnelHandle
        tunnelHandle = -1
        GoBackend.awgTurnOff(handleToClose)
        state.value = DISCONNECTED
    }

    override fun reconnectVpn(vpnBuilder: Builder) {
        state.value = CONNECTED
    }
}
