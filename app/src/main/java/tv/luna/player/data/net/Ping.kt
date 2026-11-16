package tv.luna.player.data.net

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** TCP connect time to Cloudflare (Android apps can't send ICMP): ms, or -1 when it fails or takes over 3 s. */
fun tcpPingMs(host: String = "1.1.1.1", port: Int = 443): Int = runCatching {
    Socket().use { s ->
        val start = System.nanoTime()
        s.connect(InetSocketAddress(host, port), 3_000)
        ((System.nanoTime() - start) / 1_000_000).toInt()
    }
}.getOrDefault(-1)

/** A ping every 30 s while collected. */
fun pings(): Flow<Int> = flow { while (true) { emit(tcpPingMs()); delay(30_000) } }.flowOn(Dispatchers.IO)
