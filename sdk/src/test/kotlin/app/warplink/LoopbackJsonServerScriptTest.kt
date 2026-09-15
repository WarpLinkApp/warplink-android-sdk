package app.warplink

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The retry tests are only as good as the stub that scripts them, and a stub
 * that quietly answers out of order or answers a [LoopbackJsonServer.Answer.HANG]
 * would make a broken retry look green. So the stub is driven here directly over
 * a socket, with no SDK in between.
 */
class LoopbackJsonServerScriptTest {

    private lateinit var stub: LoopbackJsonServer

    @Before
    fun setUp() {
        stub = LoopbackJsonServer("""{"ok":true}""")
    }

    @After
    fun tearDown() {
        stub.close()
    }

    @Test
    fun `a script answers in the order it was armed`() {
        stub.serveScript(LoopbackJsonServer.Answer.DROP, LoopbackJsonServer.Answer.RESPOND)

        assertEquals("", send("/first"), "a DROP must close with nothing written")
        assertTrue(send("/second").startsWith("HTTP/1.1 200"), "the second answer is the RESPOND")

        assertTrue(stub.awaitRequests(2))
        assertEquals(listOf("/first", "/second"), stub.requestLines.map { it.split(" ")[1] })
    }

    @Test
    fun `a HANG accepts the request and never answers`() {
        stub.serveScript(LoopbackJsonServer.Answer.HANG, LoopbackJsonServer.Answer.RESPOND)

        try {
            send("/hung", readTimeoutMs = 700)
            fail("a HANG must hold the connection open, not answer or close it")
        } catch (_: SocketTimeoutException) {
            // The attempt ends only on the client's own timeout, which is the
            // point: it is what puts a per-attempt timeout under test.
        }

        // The request was still read, so the next armed answer is the RESPOND.
        assertTrue(send("/after").startsWith("HTTP/1.1 200"))
        assertTrue(stub.awaitRequests(2))
    }

    @Test
    fun `close releases a connection parked by a HANG`() {
        stub.serveScript(LoopbackJsonServer.Answer.HANG)
        val client = Socket(LOOPBACK, port())
        client.soTimeout = AWAIT_MS
        client.getOutputStream().apply { write(requestBytes("/hung")); flush() }
        assertTrue(stub.awaitRequests(1))

        stub.close()

        // Not a timeout: the server side is gone, so the read ends at EOF. A
        // parked socket that outlived its test would hold this open instead.
        assertEquals("", client.getInputStream().readBytes().decodeToString())
        client.close()
    }

    @Test
    fun `serveSequence is the same script spelled in Booleans`() {
        stub.serveSequence(false, true)

        assertEquals("", send("/first"))
        assertTrue(send("/second").startsWith("HTTP/1.1 200"))
        assertTrue(stub.awaitRequests(2))
    }

    @Test
    fun `every request keeps its headers beside its request line`() {
        stub.serveScript(LoopbackJsonServer.Answer.RESPOND, LoopbackJsonServer.Answer.RESPOND)

        send("/one", extraHeaders = listOf("X-WarpLink-Tap-Id: tap-one"))
        send("/two", extraHeaders = listOf("X-WarpLink-Tap-Id: tap-two"))
        assertTrue(stub.awaitRequests(2))

        val tapIds = stub.requestHeaders.mapNotNull { headers ->
            headers.firstOrNull { it.startsWith("X-WarpLink-Tap-Id:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
        }
        assertEquals(listOf("tap-one", "tap-two"), tapIds)
    }

    @Test
    fun `awaitRequests reports the requests that never arrived`() {
        stub.serveScript(LoopbackJsonServer.Answer.RESPOND)
        send("/only")

        assertTrue(stub.awaitRequests(1))
        assertFalse(stub.awaitRequests(2, seconds = 1), "one request cannot satisfy a wait for two")
    }

    @Test
    fun `serveOnce still answers, so the suites that use it are untouched`() {
        stub.serveOnce()

        assertTrue(send("/once").startsWith("HTTP/1.1 200"))
        assertTrue(stub.requestReceived.await(AWAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, stub.requestLines.size)
    }

    private fun port() = stub.baseUrl.substringAfterLast(":").toInt()

    private fun requestBytes(path: String, extraHeaders: List<String> = emptyList()) =
        buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $LOOPBACK:${port()}\r\n")
            extraHeaders.forEach { append("$it\r\n") }
            append("\r\n")
        }.toByteArray()

    /** The whole response, or "" when the stub closed without writing one. */
    private fun send(
        path: String,
        extraHeaders: List<String> = emptyList(),
        readTimeoutMs: Int = AWAIT_MS,
    ): String = Socket(LOOPBACK, port()).use { client ->
        client.soTimeout = readTimeoutMs
        client.getOutputStream().apply { write(requestBytes(path, extraHeaders)); flush() }
        client.getInputStream().readBytes().decodeToString()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val AWAIT_SECONDS = 10L
        const val AWAIT_MS = 10_000
    }
}
