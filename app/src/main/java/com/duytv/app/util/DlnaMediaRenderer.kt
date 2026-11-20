package com.duytv.app.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Bộ phát hiện và quảng bá DLNA Digital Media Renderer (DMR) & mDNS siêu nhẹ.
 * Giúp điện thoại Samsung (Smart View, Gallery "Truyền"), Xiaomi, iPhone (DLNA), Windows
 * tự động nhìn thấy "DuyTV (Tivi Sharp)" trong danh sách thiết bị khả dụng trên cùng Wi-Fi.
 */
object DlnaMediaRenderer {
    private const val TAG = "DlnaMediaRenderer"
    private const val SSDP_MULTICAST_IP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    const val UDN = "uuid:duytv-sharp-aquos-renderer"

    private var appContext: Context? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var job: Job? = null
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "DLNA coroutine error (recovered via SupervisorJob): ${throwable.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    fun getLocalIpv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getUdn(): String = appContext?.let { HardwareOptimizer.getUdn(it) } ?: UDN

    fun start(context: Context) {
        if (job?.isActive == true) return
        appContext = context.applicationContext

        // 1. Chỉ kích hoạt MulticastLock trên TV để nhận gói tin SSDP UDP 1900 (tránh hao pin trên điện thoại)
        if (HardwareOptimizer.isTvDevice(context)) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (multicastLock == null && wm != null) {
                    multicastLock = wm.createMulticastLock("DuyTV:DlnaMulticastLock").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    Log.i(TAG, "Đã kích hoạt MulticastLock cho Wi-Fi")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể acquire MulticastLock: ${e.message}")
            }
        }

        // 2. Đăng ký mDNS Native Service chuẩn HTTP WebHub (KHÔNG giả mạo _googlecast._tcp. tránh treo Google Home)
        registerMdns(context)

        // 3. Khởi chạy luồng SSDP Multicast Receiver & Alive Announcer
        job = scope.launch {
            launch { runSsdpResponder() }
            launch { runPeriodicAliveAnnouncer() }
        }
    }

    private fun registerMdns(context: Context) {
        try {
            nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val friendlyName = HardwareOptimizer.getDeviceFriendlyName(context).replace(Regex("[^a-zA-Z0-9_-]"), "-")
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = friendlyName.ifBlank { "DuyTV-WebHub" }
                serviceType = "_http._tcp."
                port = LanMediaServer.PORT
            }

            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo?) {
                    Log.i(TAG, "mDNS Service đã đăng ký thành công: ${info?.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "mDNS đăng ký thất bại mã lỗi: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registerService gặp lỗi: ${e.message}")
        }
    }

    private suspend fun runSsdpResponder() {
        while (scope.isActive) {
            var socket: MulticastSocket? = null
            try {
                val group = InetAddress.getByName(SSDP_MULTICAST_IP)
                socket = try {
                    MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(SSDP_PORT))
                        joinGroup(group)
                        timeToLive = 4
                    }
                } catch (be: Exception) {
                    Log.w(TAG, "Port 1900 đang được chia sẻ, mở socket đa hướng phụ: ${be.message}")
                    MulticastSocket(0).apply {
                        reuseAddress = true
                        joinGroup(group)
                        timeToLive = 4
                    }
                }

                Log.i(TAG, "DuyTV SSDP DMR đang lắng nghe tại $SSDP_MULTICAST_IP:$SSDP_PORT")
                val buffer = ByteArray(2048)

                while (scope.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    if (data.startsWith("M-SEARCH", ignoreCase = true)) {
                        handleMSearch(socket, packet, data)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "SSDP Socket exception: ${e.message}. Tự khởi động lại sau 3s...")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
            delay(3000)
        }
    }

    private fun handleMSearch(socket: MulticastSocket, packet: DatagramPacket, rawData: String) {
        val lines = rawData.split("\r\n", "\n")
        var st = ""
        for (line in lines) {
            if (line.startsWith("ST:", ignoreCase = true)) {
                st = line.substring(3).trim()
                break
            }
        }

        val shouldRespond = st.equals("ssdp:all", ignoreCase = true) ||
                st.equals("upnp:rootdevice", ignoreCase = true) ||
                st.contains("device:MediaRenderer", ignoreCase = true) ||
                st.contains("service:AVTransport", ignoreCase = true) ||
                st.contains("service:RenderingControl", ignoreCase = true) ||
                st.contains("service:ConnectionManager", ignoreCase = true) ||
                st.contains("service:dial", ignoreCase = true)

        if (shouldRespond) {
            val localIp = getLocalIpv4Address()
            val targetSt = if (st.isNotBlank()) st else "urn:schemas-upnp-org:device:MediaRenderer:1"
            val response = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: http://$localIp:${LanMediaServer.PORT}/upnp/description.xml\r\n" +
                    "SERVER: Linux/4.4 UPnP/1.0 DuyTV/${com.duytv.app.BuildConfig.VERSION_NAME}\r\n" +
                    "ST: $targetSt\r\n" +
                    "USN: ${getUdn()}::$targetSt\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n"

            val resBytes = response.toByteArray(Charsets.UTF_8)
            val resPacket = DatagramPacket(resBytes, resBytes.size, packet.socketAddress)
            try {
                socket.send(resPacket)
                Log.d(TAG, "Đã gửi SSDP 200 OK phản hồi tới ${packet.address.hostAddress} cho ST: $targetSt")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể gửi phản hồi SSDP: ${e.message}")
            }
        }
    }

    private suspend fun runPeriodicAliveAnnouncer() {
        val group = InetAddress.getByName(SSDP_MULTICAST_IP)
        while (scope.isActive) {
            try {
                val localIp = getLocalIpv4Address()
                if (localIp != "127.0.0.1") {
                    val socket = MulticastSocket().apply { timeToLive = 4 }
                    val targets = listOf(
                        "upnp:rootdevice",
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:schemas-upnp-org:service:AVTransport:1"
                    )

                    for (target in targets) {
                        val notifyMsg = "NOTIFY * HTTP/1.1\r\n" +
                                "HOST: $SSDP_MULTICAST_IP:$SSDP_PORT\r\n" +
                                "CACHE-CONTROL: max-age=1800\r\n" +
                                "LOCATION: http://$localIp:${LanMediaServer.PORT}/upnp/description.xml\r\n" +
                                "NT: $target\r\n" +
                                "NTS: ssdp:alive\r\n" +
                                "SERVER: Linux/4.4 UPnP/1.0 DuyTV/${com.duytv.app.BuildConfig.VERSION_NAME}\r\n" +
                                "USN: ${getUdn()}::$target\r\n" +
                                "BOOTID.UPNP.ORG: 1\r\n" +
                                "CONFIGID.UPNP.ORG: 1\r\n\r\n"

                        val bytes = notifyMsg.toByteArray(Charsets.UTF_8)
                        val packet = DatagramPacket(bytes, bytes.size, group, SSDP_PORT)
                        socket.send(packet)
                    }
                    socket.close()
                }
            } catch (_: Exception) {}
            delay(20000) // Thông báo mỗi 20 giây một lần
        }
    }

    fun stop() {
        try { job?.cancel() } catch (_: Exception) {}
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        try {
            nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
    }
}
