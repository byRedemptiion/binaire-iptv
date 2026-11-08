package com.binaire.iptv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Multi-TV : une TV (« principale ») est la seule à se connecter au fournisseur.
 * Elle garde le flux dans un tampon circulaire sur son stockage et le sert, sur le Wi-Fi,
 * à son propre lecteur et aux autres TV. Un canal de contrôle synchronise lecture / pause / position.
 */
object MultiTv {
    const val SERVICE_TYPE = "_binaire._tcp."
    const val DEFAULT_PORT = 47821

    /** Adresse IPv4 de l'appareil sur le réseau local. */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** Adresse directe d'un flux Xtream en MPEG-TS (le HLS ne se relaie pas aussi simplement). */
    fun relayUrl(item: PlayItem): String =
        if (item.isLive && item.url.substringBefore('?').endsWith(".m3u8")) item.url.substringBefore('?').removeSuffix(".m3u8") + ".ts"
        else item.url
}

/**
 * Tampon circulaire alimenté par une seule connexion au fournisseur.
 * Films / épisodes : la connexion suit les lecteurs (reprend à la bonne position, attend s'ils sont en pause).
 * Direct : la connexion ne s'arrête jamais ; un lecteur trop en retard est décroché.
 */
class RelayCache(file: File, val url: String, val live: Boolean) {

    val id: String = java.lang.Long.toString(System.nanoTime() and 0xFFFFFFFL, 36)
    private val capacity: Long = if (live) 160L shl 20 else 256L shl 20
    private val raf = RandomAccessFile(file.apply { delete() }, "rw")
    private val channel = raf.channel
    private val lock = Object()

    @Volatile private var closed = false
    private var lo = 0L
    private var hi = 0L
    private var total = -1L
    private var contentType: String? = null
    private var started = false
    private var gen = 0
    private var lastRestart = 0L
    private var eof = false
    private var lastError: String? = null
    private val readers = HashMap<Int, Long>()
    private var nextReader = 1
    @Volatile private var call: Call? = null

    init {
        if (live) start(0)
    }

    private fun start(from: Long) {
        if (started) return
        started = true
        lo = from
        hi = from
        Thread({ upstreamLoop() }, "binaire-relay").apply { isDaemon = true }.start()
    }

    /** Position de tête (direct) : là où un nouveau lecteur commence. */
    fun head(): Long = synchronized(lock) { hi - hi % 188 }

    fun type(): String? = synchronized(lock) { contentType }

    fun register(): Int = synchronized(lock) { nextReader++ }

    fun unregister(r: Int) = synchronized(lock) {
        readers.remove(r)
        lock.notifyAll()
    }

    /** Taille totale du film (attend la première réponse du fournisseur). */
    fun awaitTotal(startAt: Long, timeoutMs: Long): Long {
        synchronized(lock) {
            start(startAt)
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (total < 0 && !closed) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) throw IOException(lastError ?: "Le fournisseur ne répond pas")
                lock.wait(left.coerceAtMost(250))
            }
            if (closed) throw IOException("closed")
            return total
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        call?.cancel()
        try { channel.close(); raf.close() } catch (e: IOException) { }
    }

    private fun restartAt(pos: Long) {
        lo = pos
        hi = pos
        eof = false
        gen++
        lastRestart = SystemClock.elapsedRealtime()
        lock.notifyAll()
        call?.cancel()
    }

    /** Lit à partir de [pos] ; bloque jusqu'à ce que les données arrivent. -1 en fin de fichier. */
    fun read(reader: Int, pos: Long, buf: ByteArray, max: Int): Int {
        val n: Int
        synchronized(lock) {
            start(pos)
            readers[reader] = pos
            lock.notifyAll()
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (true) {
                if (closed) throw IOException("closed")
                if (!live && total >= 0 && pos >= total) return -1
                if (pos in lo until hi) break
                if (live && pos < lo) throw IOException("Lecteur trop en retard")
                if (!live && (pos < lo || pos > hi + (4L shl 20))) {
                    // Saut (avance rapide, retour, index en fin de fichier) : on reprend la connexion ailleurs,
                    // mais pas plus d'une fois toutes les 1,5 s pour laisser l'autre TV suivre.
                    if (SystemClock.elapsedRealtime() - lastRestart > 1500) restartAt(pos)
                }
                if (SystemClock.elapsedRealtime() > deadline) throw IOException(lastError ?: "Plus de données")
                lock.wait(250)
            }
            n = minOf(max.toLong(), hi - pos, capacity - pos % capacity).toInt()
        }
        var done = 0
        while (done < n) {
            val r = channel.read(ByteBuffer.wrap(buf, done, n - done), (pos + done) % capacity)
            if (r <= 0) break
            done += r
        }
        // En direct, la zone a pu être réécrite pendant la lecture.
        if (live) synchronized(lock) { if (pos < lo) throw IOException("Lecteur trop en retard") }
        return done
    }

    private fun upstreamLoop() {
        var errors = 0
        val buf = ByteArray(64 * 1024)
        while (!closed) {
            val myGen: Int
            val from: Long
            synchronized(lock) {
                // Film entièrement reçu : on attend un saut éventuel.
                while (!closed && eof) lock.wait(500)
                myGen = gen
                from = hi
            }
            if (closed) break
            val req = Request.Builder().url(url)
                .apply { if (!live && from > 0) header("Range", "bytes=$from-") }
                .build()
            val c = XtreamClient.http.newCall(req)
            call = c
            try {
                c.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Le fournisseur refuse (HTTP ${resp.code})")
                    val body = resp.body ?: throw IOException("Réponse vide")
                    val input = body.byteStream()
                    if (!live) {
                        var skip = 0L
                        synchronized(lock) {
                            if (total < 0) {
                                total = when {
                                    resp.code == 206 -> resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                                    else -> body.contentLength()
                                }
                                if (total < 0) total = Long.MAX_VALUE
                            }
                            contentType = resp.header("Content-Type")
                            if (resp.code == 200 && from > 0) skip = from // le serveur ignore « Range »
                            lock.notifyAll()
                        }
                        while (skip > 0) {
                            val s = input.skip(skip)
                            if (s <= 0) throw IOException("Saut impossible")
                            skip -= s
                        }
                    }
                    errors = 0
                    while (!closed) {
                        synchronized(lock) {
                            // Film : on ne remplit pas plus que ce que les lecteurs peuvent garder.
                            while (!closed && gen == myGen && !live && readers.isNotEmpty() &&
                                hi - readers.values.min() > capacity - (24L shl 20)
                            ) lock.wait(250)
                        }
                        if (closed || synchronized(lock) { gen != myGen }) return@use
                        val n = input.read(buf)
                        if (n < 0) {
                            if (live) throw IOException("Flux interrompu")
                            synchronized(lock) { if (gen == myGen) eof = true; lock.notifyAll() }
                            return@use
                        }
                        val at: Long = synchronized(lock) { if (gen != myGen) -1L else hi }
                        if (at < 0) return@use
                        var off = 0
                        while (off < n) {
                            val p = (at + off) % capacity
                            val len = minOf((n - off).toLong(), capacity - p).toInt()
                            channel.write(ByteBuffer.wrap(buf, off, len), p)
                            off += len
                        }
                        synchronized(lock) {
                            if (gen == myGen) {
                                hi += n
                                if (hi - lo > capacity) lo = hi - capacity
                                lock.notifyAll()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (closed) break
                val restarted = synchronized(lock) { gen != myGen }
                if (!restarted) {
                    errors++
                    synchronized(lock) { lastError = e.message }
                    Thread.sleep(minOf(errors * 1000L, 5000L))
                }
            } finally {
                call = null
            }
        }
    }
}

/** La TV principale : serveur HTTP local (flux + contrôle) et annonce sur le réseau. */
object MultiTvHost {

    interface Listener {
        fun onCommand(action: String, pos: Long)
        fun onGuestJoined()
        fun onGuestsChanged(count: Int)
    }

    private class Guest(val socket: Socket, val out: OutputStream, val name: String)

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private val guests = CopyOnWriteArrayList<Guest>()
    @Volatile private var cache: RelayCache? = null
    @Volatile private var loadMsg: JSONObject? = null
    @Volatile var listener: Listener? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    var port = 0
        private set
    var name = ""
        private set

    val running: Boolean get() = server != null
    val guestCount: Int get() = guests.size
    fun address(): String = "${MultiTv.localIp() ?: "?"}:$port"
    fun localUrl(path: String) = "http://127.0.0.1:$port$path"

    fun start(ctx: Context, deviceName: String) {
        if (server != null) return
        name = deviceName
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(MultiTv.DEFAULT_PORT)) }
        } catch (e: IOException) {
            ServerSocket(0)
        }
        server = s
        port = s.localPort
        Thread({ acceptLoop(s) }, "binaire-host").apply { isDaemon = true }.start()
        register(ctx.applicationContext)
    }

    fun stop() {
        broadcast(JSONObject().put("t", "end"))
        for (g in guests) runCatching { g.socket.close() }
        guests.clear()
        runCatching { server?.close() }
        server = null
        cache?.close()
        cache = null
        loadMsg = null
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        nsdListener = null
    }

    /** Nouveau contenu partagé ; l'ancienne connexion au fournisseur est fermée avant. */
    fun setMedia(newCache: RelayCache, load: JSONObject) {
        val old = cache
        cache = newCache
        if (old !== newCache) old?.close()
        loadMsg = load
        broadcast(load)
    }

    fun currentCache(): RelayCache? = cache

    fun broadcast(o: JSONObject) {
        if (guests.isEmpty()) return
        val line = (o.toString() + "\n").toByteArray()
        Thread {
            for (g in guests) {
                try {
                    synchronized(g.out) { g.out.write(line); g.out.flush() }
                } catch (e: IOException) {
                    drop(g)
                }
            }
        }.start()
    }

    private fun drop(g: Guest) {
        if (guests.remove(g)) {
            runCatching { g.socket.close() }
            val n = guests.size
            main.post { listener?.onGuestsChanged(n) }
        }
    }

    private fun register(ctx: Context) {
        val m = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        val info = NsdServiceInfo().apply {
            serviceName = "Binaire · $name"
            serviceType = MultiTv.SERVICE_TYPE
            port = this@MultiTvHost.port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        nsdListener = l
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (e: IOException) { break }
            Thread({ handle(sock) }, "binaire-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val head = readHead(input) ?: return sock.close()
            val first = head.first().split(" ")
            val target = first.getOrNull(1) ?: "/"
            val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val out = sock.getOutputStream().buffered(64 * 1024)
            when (path) {
                "/info" -> {
                    val body = JSONObject().put("name", name).put("title", loadMsg?.optString("title") ?: "").toString().toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body); out.flush(); sock.close()
                }
                "/control" -> control(sock, input, out, query["name"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "TV")
                "/media" -> media(sock, out, query, headers["range"])
                else -> {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); sock.close()
                }
            }
        } catch (e: Exception) {
            runCatching { sock.close() }
        }
    }

    private fun readHead(input: java.io.InputStream): List<String>? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) return lines
                lines.add(line)
                if (lines.size > 64) return null
            } else if (sb.length < 8192) sb.append(c.toChar())
        }
    }

    private fun control(sock: Socket, input: java.io.InputStream, out: OutputStream, guestName: String) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
        val g = Guest(sock, out, guestName)
        synchronized(out) {
            out.write((JSONObject().put("t", "hello").put("name", name).toString() + "\n").toByteArray())
            out.flush()
        }
        guests.add(g)
        main.post {
            listener?.onGuestsChanged(guests.size)
            listener?.onGuestJoined()
        }
        // En direct, onGuestJoined renvoie un « load » à tout le monde ; sinon on envoie l'actuel.
        loadMsg?.let { if (!it.optBoolean("live")) synchronized(out) { out.write((it.toString() + "\n").toByteArray()); out.flush() } }
        val reader = BufferedReader(InputStreamReader(input))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (o.optString("t")) {
                    "ping" -> {
                        val pong = JSONObject().put("t", "pong").put("c", o.optLong("c")).put("h", SystemClock.elapsedRealtime())
                        synchronized(out) { out.write((pong.toString() + "\n").toByteArray()); out.flush() }
                    }
                    "cmd" -> {
                        val a = o.optString("a")
                        val pos = o.optLong("pos")
                        main.post { listener?.onCommand(a, pos) }
                    }
                }
            }
        } catch (e: IOException) {
        } finally {
            drop(g)
        }
    }

    private fun media(sock: Socket, out: OutputStream, q: Map<String, String>, range: String?) {
        val c = cache
        if (c == null || q["id"] != c.id) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush(); sock.close(); return
        }
        val reader = c.register()
        val buf = ByteArray(64 * 1024)
        try {
            if (c.live) {
                var pos = q["from"]?.toLongOrNull() ?: c.head()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                while (true) {
                    val n = c.read(reader, pos, buf, buf.size)
                    if (n < 0) break
                    out.write(buf, 0, n); out.flush()
                    pos += n
                }
            } else {
                var start = 0L
                var endIncl = -1L
                if (range != null && range.startsWith("bytes=")) {
                    val r = range.removePrefix("bytes=").substringBefore(',')
                    start = r.substringBefore('-').toLongOrNull() ?: 0L
                    endIncl = r.substringAfter('-').toLongOrNull() ?: -1L
                }
                val total = c.awaitTotal(start, 30_000)
                if (total != Long.MAX_VALUE && start >= total) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val known = total != Long.MAX_VALUE
                if (known && (endIncl < 0 || endIncl >= total)) endIncl = total - 1
                val type = c.type() ?: "application/octet-stream"
                val sb = StringBuilder()
                if (range != null && known) {
                    sb.append("HTTP/1.1 206 Partial Content\r\n")
                    sb.append("Content-Range: bytes $start-$endIncl/$total\r\n")
                } else sb.append("HTTP/1.1 200 OK\r\n")
                if (known) sb.append("Content-Length: ${endIncl - start + 1}\r\n")
                sb.append("Content-Type: $type\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n")
                out.write(sb.toString().toByteArray())
                var pos = start
                while (!known || pos <= endIncl) {
                    val want = if (known) minOf(buf.size.toLong(), endIncl - pos + 1).toInt() else buf.size
                    val n = c.read(reader, pos, buf, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
                out.flush()
            }
        } catch (e: Exception) {
        } finally {
            c.unregister(reader)
            runCatching { sock.close() }
        }
    }
}

/** Recherche des TV principales sur le réseau local. */
class MultiTvFinder(ctx: Context, private val onFound: (name: String, host: String, port: Int) -> Unit) {

    private val app = ctx.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val lock = runCatching { wifi?.createMulticastLock("binaire-multitv")?.apply { setReferenceCounted(false) } }.getOrNull()
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var stopped = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            main.post {
                pending.addLast(info)
                next()
            }
        }
    }

    fun start() {
        runCatching { lock?.acquire() }
        runCatching { nsd?.discoverServices(MultiTv.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        stopped = true
        runCatching { nsd?.stopServiceDiscovery(discovery) }
        runCatching { lock?.release() }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || stopped) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        val l = object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; next() }
            }

            override fun onServiceResolved(i: NsdServiceInfo) {
                val host = i.host?.hostAddress
                main.post {
                    resolving = false
                    if (!stopped && host != null) onFound(i.serviceName.removePrefix("Binaire · "), host, i.port)
                    next()
                }
            }
        }
        if (runCatching { nsd?.resolveService(info, l) }.isFailure) resolving = false
    }
}
