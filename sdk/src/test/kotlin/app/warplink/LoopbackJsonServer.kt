package app.warplink

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A one-shot loopback HTTP stub for tests that need a real 200 JSON body from the
 * API (an unreachable endpoint only ever exercises the failure path).
 *
 * A raw socket rather than an embedded HTTP server: `com.sun.net.httpserver` is
 * not on the Android unit-test compile classpath.
 */
internal class LoopbackJsonServer(
    private val body: String,
    /**
     * The status line to answer with. Defaults to 200; a refusal test sets it,
     * because a refusal reaches the client on `errorStream` rather than
     * `inputStream` and a stub that can only answer 200 cannot exercise it.
     */
    private val status: Int = 200
) {

    private val socket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))

    /** Counts down once the stub has read a full request. */
    val requestReceived = CountDownLatch(1)

    /**
     * The request body as it arrived on the wire, empty until one does. Written
     * on the stub's thread and read on the test's, hence @Volatile.
     */
    @Volatile
    var requestBody: String = ""
        private set

    /** Every request body the stub has read, in arrival order. */
    val requestBodies = CopyOnWriteArrayList<String>()

    /**
     * Every request line the stub has read, in arrival order, so a test can
     * assert on the path and query the client actually sent rather than only on
     * the body.
     */
    val requestLines = CopyOnWriteArrayList<String>()

    /** Every request's header lines, in arrival order, so a test can assert on
     *  what the client actually sent rather than only on the path. */
    val requestHeaders = CopyOnWriteArrayList<List<String>>()

    val baseUrl: String get() = "http://$LOOPBACK:${socket.localPort}"

    /**
     * Answer exactly one request. When [gate] is given the response is held
     * until it fires, so a test can act while the request is still in flight.
     */
    fun serveOnce(gate: CountDownLatch? = null) {
        thread(isDaemon = true, name = "warplink-loopback-stub") {
            val connection = try {
                socket.accept()
            } catch (_: SocketException) {
                // Closed at teardown before a request arrived: nothing to serve.
                return@thread
            }
            connection.use {
                drainRequest(it.getInputStream())
                requestReceived.countDown()
                gate?.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                pauseBeforeAnswer()
                it.getOutputStream().apply {
                    write(httpResponse().toByteArray())
                    flush()
                }
            }
        }
    }

    /**
     * What the stub does with one request.
     *
     * A Boolean cannot say "accept and never answer", and that third state is
     * the one the bounded first attempt is tested with.
     */
    enum class Answer {
        /** Read the request and write the configured response. */
        RESPOND,

        /**
         * Read the request, then close the connection with nothing written.
         * HttpURLConnection raises that as an IOException, which ApiClient maps
         * to WarpLinkError.NetworkError, so it is a transient failure that
         * fails FAST.
         */
        DROP,

        /**
         * Read the request and never answer, holding the connection open.
         * The attempt ends only when its own readTimeout fires, which is what
         * makes a per-attempt timeout the thing under test rather than a value
         * nobody reads.
         */
        HANG,

        /**
         * Read the request, then answer one byte every [DRIP_MS], for ever.
         *
         * The failure [HANG] cannot express. `readTimeout` is SO_TIMEOUT, which
         * bounds ONE read rather than the response, so a byte arriving every
         * half second resets it for ever and the attempt never ends on its own.
         * A silent connection times out; a slow one does not, and both are the
         * same weak uplink.
         */
        DRIP,
    }

    /** Connections held open by a [Answer.HANG], released at teardown. */
    private val parked = CopyOnWriteArrayList<Socket>()

    /**
     * Answer [answers] in arrival order from ONE thread.
     *
     * Arming several [serveOnce] threads cannot give a retry test a script:
     * they all race the same accept(), so which request gets which answer is
     * undefined. This reads them in order instead.
     */
    fun serveScript(vararg answers: Answer) {
        thread(isDaemon = true, name = "warplink-loopback-script") {
            for (answer in answers) {
                val connection = try {
                    socket.accept()
                } catch (_: SocketException) {
                    // Closed at teardown: nothing left to serve.
                    return@thread
                }
                if (answer == Answer.HANG || answer == Answer.DRIP) {
                    // Deliberately NOT `use`: the connection must stay open.
                    // Parked so close() releases it at teardown.
                    drainRequest(connection.getInputStream())
                    requestReceived.countDown()
                    parked.add(connection)
                    if (answer == Answer.DRIP) drip(connection)
                    continue
                }
                connection.use {
                    drainRequest(it.getInputStream())
                    requestReceived.countDown()
                    if (answer == Answer.RESPOND) {
                        pauseBeforeAnswer()
                        it.getOutputStream().apply {
                            write(httpResponse().toByteArray())
                            flush()
                        }
                    }
                    // A DROP falls out of `use`, closing the socket with no
                    // response written.
                }
            }
        }
    }

    /**
     * The Boolean spelling of [serveScript], for the tests that only need
     * "dropped" or "answered" and read better that way.
     */
    fun serveSequence(vararg answers: Boolean) =
        serveScript(*answers.map { if (it) Answer.RESPOND else Answer.DROP }.toTypedArray())

    /**
     * Block until [count] requests have been read, or the timeout elapses.
     *
     * [requestReceived] counts to one and cannot express "three attempts", and
     * a permit-based latch would need the caller to track how many it had
     * already consumed. Polling the log it already keeps needs neither.
     */
    fun awaitRequests(count: Int, seconds: Long = AWAIT_SECONDS): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (requestLines.size >= count) return true
            Thread.sleep(POLL_MS)
        }
        return requestLines.size >= count
    }

    fun close() {
        parked.forEach { runCatching { it.close() } }
        socket.close()
    }

    /**
     * Answer [connection] one byte at a time, for ever, on its own thread.
     *
     * The preamble alone takes about half a minute at this rate, so the client
     * is still waiting on the status line the whole time: it is receiving bytes
     * and has read nothing it can act on. Ends when the socket is closed, by the
     * client abandoning the attempt or by [close] at teardown.
     */
    private fun drip(connection: Socket) {
        thread(isDaemon = true, name = "warplink-loopback-drip") {
            runCatching {
                val out = connection.getOutputStream()
                val preamble = (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "$CONTENT_LENGTH 1000000\r\n\r\n"
                    ).toByteArray()
                // The drip is an answer too, so its first byte waits like any
                // other. The bytes after it keep their own DRIP_MS cadence.
                pauseBeforeAnswer()
                var index = 0
                while (true) {
                    out.write(if (index < preamble.size) preamble[index].toInt() else ' '.code)
                    out.flush()
                    index += 1
                    Thread.sleep(DRIP_MS)
                }
            }
        }
    }

    /**
     * Consume request line, headers and body so the client's write completes,
     * keeping the request line and the body so a test can assert on what was
     * actually sent.
     */
    private fun drainRequest(input: InputStream) {
        val reader = input.bufferedReader()
        var contentLength = 0
        // The request line comes first and is not a header, so it is read out
        // of the header loop and kept rather than discarded with it.
        requestLines.add(reader.readLine() ?: return)
        val headers = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            headers.add(line)
            if (line.startsWith(CONTENT_LENGTH, ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toInt()
            }
        }
        requestHeaders.add(headers)
        val body = StringBuilder(contentLength)
        repeat(contentLength) {
            val next = reader.read()
            if (next != -1) body.append(next.toChar())
        }
        requestBody = body.toString()
        requestBodies.add(requestBody)
    }

    /**
     * Wait [answerDelayMs] REAL milliseconds before an answer goes out.
     *
     * Called from every path that writes one: [serveOnce], the [Answer.RESPOND]
     * branch of [serveScript], and the first byte of [drip]. Real time, not
     * Robolectric's, because the socket it models runs in real time too.
     */
    private fun pauseBeforeAnswer() {
        if (answerDelayMs > 0) Thread.sleep(answerDelayMs)
    }

    private fun httpResponse() =
        "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
            "Content-Type: application/json\r\n" +
            "$CONTENT_LENGTH ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + body

    private fun reasonPhrase(status: Int) = when (status) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "Status"
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONTENT_LENGTH = "Content-Length:"
        const val AWAIT_SECONDS = 10L
        const val POLL_MS = 5L

        /** One byte per this many milliseconds, for [Answer.DRIP]. */
        const val DRIP_MS = 500L

        /**
         * Real milliseconds to wait before writing an answer, from
         * `-Dwarplink.test.loopbackDelayMs=N`. Zero by default, so an ordinary
         * run is exactly what it was before this existed.
         *
         * It exists to prove the suite does not depend on the socket being
         * fast. Every wait helper in this suite must run the main looper
         * WITHOUT moving Robolectric's clock, because the per-attempt watchdog
         * in `BoundedAttempt` is armed with `postDelayed` against that clock
         * while the socket it guards runs in real time. A test whose wait still
         * moves the clock passes at 0 and fails here, because the watchdog it
         * is fast-forwarding into now has time to win.
         *
         * So a failure under this delay is a broken TEST, never a broken
         * server. Fix the test's wait. NEVER raise this delay, and never lower
         * it back to zero, to make a test pass.
         *
         * Read once, at class load, so every stub in a run answers alike.
         */
        val answerDelayMs: Long =
            System.getProperty("warplink.test.loopbackDelayMs")?.toLongOrNull() ?: 0L
    }
}
