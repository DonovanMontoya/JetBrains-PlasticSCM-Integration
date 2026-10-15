package com.github.donovanmontoya.riderplastic.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSession

/** All requests use an in-memory transport or an ephemeral loopback server; no real keys or paid calls. */
class AiClientTest {
    private val openAi = AiRequestConfig(AiProvider.OPENAI, "test-model", AiClient.OPENAI_BASE_URL)
    private val anthropic = AiRequestConfig(AiProvider.ANTHROPIC, "test-claude-model", AiClient.ANTHROPIC_BASE_URL)
    private val local = AiRequestConfig(AiProvider.LOCAL, "test-model:local", AiClient.DEFAULT_LOCAL_BASE_URL)
    private val key = "test-key-not-a-credential"
    private val diff = "--- a/test.txt\n+++ b/test.txt\n-old\n+new\n"
    private val success = """{"choices":[{"finish_reason":"stop","message":{"content":"Update test text"}}]}"""

    @Test
    fun `OpenAI uses official chat endpoint and its native token parameter`() {
        val transport = RecordingTransport { response(it, success) }
        val result = client(transport).generate(openAi, key, diff) {}
        assertEquals("Update test text", result)
        val request = transport.requests.single()
        assertEquals("https://api.openai.com/v1/chat/completions", request.uri().toString())
        assertEquals("POST", request.method())
        assertEquals("Bearer $key", request.headers().firstValue("Authorization").get())
        assertFalse(request.headers().firstValue("x-api-key").isPresent)
        assertEquals("application/json", request.headers().firstValue("Content-Type").get())
        assertTrue(request.timeout().get().seconds in 1..60)
        val body = body(request)
        assertEquals("test-model", body["model"].asString)
        assertEquals(1024, body["max_completion_tokens"].asInt)
        assertFalse(body.has("max_tokens"))
        assertFalse(body["stream"].asBoolean)
        assertFalse(body["store"].asBoolean)
        assertFalse(body.has("temperature"))
        assertFalse(body.has("tools"))
        assertFalse(body.toString().contains(key))
        val messages = body.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject["role"].asString)
        assertTrue(messages[0].asJsonObject["content"].asString.contains("untrusted data"))
        assertEquals("user", messages[1].asJsonObject["role"].asString)
        assertTrue(messages[1].asJsonObject["content"].asString.endsWith(diff))
    }

    @Test
    fun `Anthropic uses native messages protocol and extracts only text blocks`() {
        val transport = RecordingTransport { response(it, """{
            "stop_reason":"end_turn", "content":[
                {"type":"thinking","thinking":"Do not show this"},
                {"type":"text","text":"Update test text"},
                {"type":"text","text":"\nKeep the fixture current"}
            ]
        }""") }
        assertEquals("Update test text\n\nKeep the fixture current", client(transport).generate(anthropic, key, diff) {})
        val request = transport.requests.single()
        assertEquals("https://api.anthropic.com/v1/messages", request.uri().toString())
        assertEquals(key, request.headers().firstValue("x-api-key").get())
        assertEquals("2023-06-01", request.headers().firstValue("anthropic-version").get())
        assertFalse(request.headers().firstValue("Authorization").isPresent)
        val body = body(request)
        assertEquals(1024, body["max_tokens"].asInt)
        assertTrue(body["system"].isJsonPrimitive)
        assertFalse(body.has("max_completion_tokens"))
        val messages = body.getAsJsonArray("messages")
        assertEquals(1, messages.size())
        assertEquals("user", messages[0].asJsonObject["role"].asString)
        val block = messages[0].asJsonObject.getAsJsonArray("content")[0].asJsonObject
        assertEquals("text", block["type"].asString)
        assertTrue(block["text"].asString.endsWith(diff))
    }

    @Test
    fun `local compatible endpoint supports no key and its own optional key`() {
        val transport = RecordingTransport { response(it, success) }
        val client = client(transport)
        client.generate(local, null, diff) {}
        client.generate(local, key, diff) {}
        val request = transport.requests.first()
        assertEquals("http://localhost:11434/v1/chat/completions", request.uri().toString())
        assertFalse(request.headers().firstValue("Authorization").isPresent)
        val body = body(request)
        assertEquals(1024, body["max_tokens"].asInt)
        assertFalse(body.has("max_completion_tokens"))
        assertFalse(body.has("store"))
        assertEquals("Bearer $key", transport.requests.last().headers().firstValue("Authorization").get())
    }

    @Test
    fun `URL normalization is stable and preserves selected destinations`() {
        assertEquals("https://api.openai.com/v1", AiClient.normalizeBaseUrl(AiProvider.OPENAI, " HTTPS://API.OPENAI.COM:443/v1/ "))
        assertEquals(AiClient.ANTHROPIC_BASE_URL, AiClient.normalizeBaseUrl(AiProvider.ANTHROPIC, ""))
        assertEquals("http://localhost/v1", AiClient.normalizeBaseUrl(AiProvider.LOCAL, "http://LOCALHOST:80/v1/"))
        assertEquals("http://127.0.0.1:11434/v1", AiClient.normalizeBaseUrl(AiProvider.LOCAL, "http://127.0.0.1:11434/v1"))
        assertEquals("http://[::1]:1234/v1", AiClient.normalizeBaseUrl(AiProvider.LOCAL, "http://[::1]:1234/v1"))
        assertEquals("https://inference.example:9443/v1", AiClient.normalizeBaseUrl(AiProvider.LOCAL, "https://inference.example:9443/v1"))
        assertEquals("test-model", AiClient.validateConfig(openAi.copy(model = " test-model ")).model)
        assertTrue(AiClient.destinationLabel(local).contains("http://localhost:11434/v1"))
    }

    @Test
    fun `unsafe malformed or ambiguous local URLs are rejected without exposing their values`() {
        val invalid = listOf(
            "http://example.com/v1", "http://localhost.example.com/v1", "http://localhost./v1",
            "http://127.1/v1", "http://127.0.0.2/v1", "http://2130706433/v1", "http://[0:0:0:0:0:0:0:1]/v1",
            "http://[::ffff:127.0.0.1]/v1", "http://192.168.1.1/v1", "http://0.0.0.0/v1",
            "https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#secret",
            "https://example.com:/v1", "https://example.com:0/v1", "https://example.com:65536/v1",
            "https://example.com:-1/v1", "https://example.com:abc/v1", "https:///v1", "https://bad_host/v1",
            "https://-invalid.example/v1", "https://example..com/v1", "https://example.com./v1",
            "file:///v1", "ftp://localhost/v1", "https://example.com/v1/chat/completions",
            "https://example.com/a/../v1", "https://example.com/%76%31", "https://example.com/v1//",
            "https://[fe80::1%25eth0]/v1", "https://localhost\\@example.com/v1", "https://example.com/\nv1", "",
        )
        invalid.forEach { url ->
            val exception = expectFailure { AiClient.normalizeBaseUrl(AiProvider.LOCAL, url) }
            assertFalse(exception.message!!.contains("secret"))
            assertNull(exception.cause)
        }
    }

    @Test
    fun `hosted providers cannot forward keys to custom origins`() {
        val transport = RecordingTransport { response(it, success) }
        listOf(
            openAi.copy(baseUrl = "https://api.openai.com.evil.example/v1"),
            openAi.copy(baseUrl = "https://api.openai.com:444/v1"),
            openAi.copy(baseUrl = "https://api.anthropic.com/v1"),
            anthropic.copy(baseUrl = "http://localhost/v1"),
            anthropic.copy(baseUrl = "https://custom.example/v1"),
        ).forEach { config -> expectFailure { client(transport).generate(config, key, diff) {} } }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `invalid models and keys do not send a request`() {
        val transport = RecordingTransport { response(it, success) }
        val client = client(transport)
        listOf("", " ", "a b", "a\nb", "x".repeat(201)).forEach {
            expectFailure { client.generate(openAi.copy(model = it), key, diff) {} }
        }
        listOf(null, "", " ", "test key", "key\nheader", "x".repeat(4097)).forEach {
            expectFailure { client.generate(openAi, it, diff) {} }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `empty and oversized diffs are rejected with UTF8 byte accounting`() {
        val transport = RecordingTransport { response(it, success) }
        val client = client(transport)
        listOf("", " \n", "x".repeat(AiClient.MAX_DIFF_BYTES + 1), "é".repeat(AiClient.MAX_DIFF_BYTES / 2 + 1)).forEach {
            expectFailure { client.generate(local, null, it) {} }
        }
        assertTrue(transport.requests.isEmpty())
        assertEquals("Update test text", client.generate(local, null, "x".repeat(AiClient.MAX_DIFF_BYTES)) {})
    }

    @Test
    fun `status failures are actionable redacted and never retried`() {
        val expected = mapOf(400 to "model", 401 to "key", 403 to "key", 404 to "model", 408 to "timed out",
            413 to "fewer files", 422 to "model", 429 to "quota", 500 to "unavailable", 503 to "unavailable", 504 to "timed out",
            301 to "redirect", 302 to "redirect", 307 to "redirect", 308 to "redirect", 418 to "failed")
        expected.forEach { (status, text) ->
            val transport = RecordingTransport { response(it, "PRIVATE DIFF $key", status) }
            val exception = expectFailure { client(transport).generate(openAi, key, diff) {} }
            assertTrue("status $status", exception.message!!.contains(text))
            assertFalse(exception.toString().contains(key))
            assertFalse(exception.toString().contains("PRIVATE DIFF"))
            assertNull(exception.cause)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `invalid provider bodies never reach UI errors`() {
        listOf("PRIVATE DIFF $key", "null", "{}", "[]", "{\"choices\":[]}",
            """{"choices":[{"message":{"content":null}}]}""",
            """{"choices":[{"message":{"content":"   "}}]}""",
            """{"choices":[{"message":{"content":42}}]}""",
        ).forEach { data ->
            val transport = RecordingTransport { response(it, data) }
            val exception = expectFailure { client(transport).generate(openAi, key, diff) {} }
            assertFalse(exception.toString().contains(key))
            assertFalse(exception.toString().contains("PRIVATE DIFF"))
            assertNull(exception.cause)
        }
    }

    @Test
    fun `truncated filtered and refusal outputs are rejected`() {
        listOf("length", "content_filter").forEach { reason ->
            val transport = RecordingTransport { response(it, """{"choices":[{"finish_reason":"$reason","message":{"content":"Partial message"}}]}""") }
            expectFailure { client(transport).generate(openAi, key, diff) {} }
        }
        listOf("max_tokens", "refusal").forEach { reason ->
            val transport = RecordingTransport { response(it, """{"stop_reason":"$reason","content":[{"type":"text","text":"Partial"}]}""") }
            expectFailure { client(transport).generate(anthropic, key, diff) {} }
        }
        val transport = RecordingTransport { response(it, """{"choices":[{"message":{"refusal":"Do not show private content","content":""}}]}""") }
        assertFalse(expectFailure { client(transport).generate(openAi, key, diff) {} }.message!!.contains("private"))
    }

    @Test
    fun `message content is normalized and capped`() {
        val transport = RecordingTransport { response(it, """{"choices":[{"message":{"content":"  Update text\r\n\r\nKeep fixtures current  "}}]}""") }
        assertEquals("Update text\n\nKeep fixtures current", client(transport).generate(openAi, key, diff) {})
        listOf("x".repeat(AiClient.MAX_MESSAGE_CHARS + 1), "Message\u0000end").forEach { text ->
            val body = JsonObject().apply { addProperty("content", text) }.toString()
            val invalid = RecordingTransport { response(it, """{"choices":[{"message":$body}]}""") }
            expectFailure { client(invalid).generate(openAi, key, diff) {} }
        }
    }

    @Test
    fun `response size cap is checked even for a mocked transport`() {
        val transport = RecordingTransport { response(it, "x".repeat(AiClient.MAX_RESPONSE_BYTES + 1)) }
        assertTrue(expectFailure { client(transport).generate(local, null, diff) {} }.message!!.contains("size limit"))
    }

    @Test
    fun `transport failures are redacted including their causes`() {
        listOf(IOException("PRIVATE DIFF $key"), IllegalArgumentException("PRIVATE DIFF $key")).forEach { failure ->
            val transport = AiProtocolTransport { CompletableFuture.failedFuture(failure) }
            val exception = expectFailure { client(transport).generate(openAi, key, diff) {} }
            assertFalse(exception.toString().contains(key))
            assertNull(exception.cause)
        }
        val throwing = AiProtocolTransport { throw IOException("PRIVATE DIFF $key") }
        assertFalse(expectFailure { client(throwing).generate(openAi, key, diff) {} }.toString().contains(key))
        val timeout = AiProtocolTransport { CompletableFuture.failedFuture(HttpTimeoutException(key)) }
        assertTrue(expectFailure { client(timeout).generate(openAi, key, diff) {} }.message!!.contains("timed out"))
    }

    @Test
    fun `cancellation before sending has no side effects`() {
        val transport = RecordingTransport { response(it, success) }
        val canceled = CancellationException("Caller canceled")
        try {
            client(transport).generate(openAi, key, diff) { throw canceled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(canceled, actual)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `cancellation preserves caller exception and cancels pending HTTP future`() {
        val future = CompletableFuture<HttpResponse<ByteArray>>()
        var sent = false
        val transport = AiProtocolTransport { sent = true; future }
        val canceled = IllegalStateException("Simulated IDE task cancellation")
        try {
            client(transport).generate(openAi, key, diff) { if (sent) throw canceled }
            fail("Expected cancellation")
        } catch (actual: IllegalStateException) {
            assertSame(canceled, actual)
        }
        assertTrue(future.isCancelled)
    }

    @Test
    fun `completed response is not applied after caller cancels`() {
        val transport = RecordingTransport { response(it, success) }
        var checks = 0
        try {
            client(transport).generate(openAi, key, diff) { if (++checks == 4) throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `deadline cancels pending future without retry`() {
        val future = CompletableFuture<HttpResponse<ByteArray>>()
        var calls = 0
        val transport = AiProtocolTransport { calls++; future }
        val exception = expectFailure { AiClient(transport, Duration.ofMillis(20)).generate(openAi, key, diff) {} }
        assertTrue(exception.message!!.contains("timed out"))
        assertTrue(future.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `unsafe JVM HTTP logging and POST retry flags prevent transport invocation`() {
        val flags = mapOf(
            "jdk.httpclient.enableAllMethodRetry" to listOf("true", "TRUE"),
            "jdk.httpclient.HttpClient.log" to listOf("headers", "content", "all", "errors,requests", "PRIVATE FLAG $key"),
        )
        flags.forEach { (property, values) ->
            val previous = System.getProperty(property)
            try {
                values.forEach { value ->
                    System.setProperty(property, value)
                    val transport = RecordingTransport { response(it, success) }
                    val exception = expectFailure { client(transport).generate(openAi, key, diff) {} }
                    assertEquals(
                        "AI generation is disabled by unsafe HTTP runtime settings. " +
                            "Remove the jdk.httpclient.HttpClient.log, jdk.httpclient.enableAllMethodRetry, and " +
                            "jdk.internal.httpclient.disableHostnameVerification JVM flags, then restart the IDE.",
                        exception.message,
                    )
                    assertTrue(transport.requests.isEmpty())
                    assertFalse(exception.toString().contains(key))
                    assertNull(exception.cause)
                    assertEquals(value, System.getProperty(property))
                }
            } finally {
                if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            }
        }
    }

    @Test
    fun `disabled JVM HTTP overrides permit mocked request without changing properties`() {
        val retryProperty = "jdk.httpclient.enableAllMethodRetry"
        val loggingProperty = "jdk.httpclient.HttpClient.log"
        val previousRetry = System.getProperty(retryProperty)
        val previousLogging = System.getProperty(loggingProperty)
        try {
            System.setProperty(retryProperty, "false")
            for (logging in listOf("", "none")) {
                System.setProperty(loggingProperty, logging)
                val transport = RecordingTransport { response(it, success) }
                assertEquals("Update test text", client(transport).generate(openAi, key, diff) {})
                assertEquals(1, transport.requests.size)
                assertEquals("false", System.getProperty(retryProperty))
                assertEquals(logging, System.getProperty(loggingProperty))
            }
        } finally {
            if (previousRetry == null) System.clearProperty(retryProperty) else System.setProperty(retryProperty, previousRetry)
            if (previousLogging == null) System.clearProperty(loggingProperty) else System.setProperty(loggingProperty, previousLogging)
        }
    }

    @Test
    fun `disabled TLS hostname verification prevents transport invocation`() {
        val property = "jdk.internal.httpclient.disableHostnameVerification"
        val previous = System.getProperty(property)
        try {
            for (value in listOf("true", "TRUE", "")) {
                System.setProperty(property, value)
                val transport = RecordingTransport { response(it, success) }
                val exception = expectFailure { client(transport).generate(openAi, key, diff) {} }
                assertTrue(exception.message!!.contains(property))
                assertTrue(exception.message!!.contains("restart the IDE"))
                assertTrue(transport.requests.isEmpty())
                assertEquals(value, System.getProperty(property))
                assertNull(exception.cause)
            }
            System.setProperty(property, "false")
            val transport = RecordingTransport { response(it, success) }
            assertEquals("Update test text", client(transport).generate(openAi, key, diff) {})
            assertEquals(1, transport.requests.size)
            assertEquals("false", System.getProperty(property))
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `thread interruption cancels request and preserves interrupt flag`() {
        val future = CompletableFuture<HttpResponse<ByteArray>>()
        try {
            val transport = AiProtocolTransport { Thread.currentThread().interrupt(); future }
            try {
                client(transport).generate(openAi, key, diff) {}
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(future.isCancelled)
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `body subscriber rejects cumulative oversized response before buffering it`() {
        val subscriber = AiProtocolLimitedBodySubscriber(4)
        val subscription = TestSubscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4, 5))))
        assertTrue(subscription.canceled)
        try {
            subscriber.body.toCompletableFuture().get()
            fail("Expected body limit")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is AiProtocolResponseTooLarge)
        }
    }

    @Test
    fun `body subscriber permits exactly capped payload and cancels duplicate subscriptions`() {
        val subscriber = AiProtocolLimitedBodySubscriber(4)
        subscriber.onSubscribe(TestSubscription())
        val duplicate = TestSubscription()
        subscriber.onSubscribe(duplicate)
        assertTrue(duplicate.canceled)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2)), ByteBuffer.wrap(byteArrayOf(3, 4))))
        subscriber.onComplete()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), subscriber.body.toCompletableFuture().get())
    }

    @Test
    fun `loopback HTTP bypasses system proxy and sends only the explicit local key`() {
        val proxyCalls = AtomicInteger()
        val previous = ProxySelector.getDefault()
        val seenKey = CompletableFuture<String>()
        val server = server { exchange ->
            seenKey.complete(exchange.requestHeaders.getFirst("Authorization"))
            reply(exchange, 200, success)
        }
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    proxyCalls.incrementAndGet()
                    return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
                }
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            assertEquals("Update test text", AiClient().generate(configFor(server), key, diff) {})
            assertEquals("Bearer $key", seenKey.get(2, TimeUnit.SECONDS))
            assertEquals(0, proxyCalls.get())
        } finally {
            ProxySelector.setDefault(previous)
            server.stop(0)
        }
    }

    @Test
    fun `redirect never forwards authorization or diff to second origin`() {
        val targetCalls = AtomicInteger()
        val target = server { exchange -> targetCalls.incrementAndGet(); reply(exchange, 200, success) }
        val sourceCalls = AtomicInteger()
        val source = server { exchange ->
            sourceCalls.incrementAndGet()
            exchange.requestBody.use { it.readAllBytes() }
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/v1/chat/completions")
            reply(exchange, 307, "PRIVATE RESPONSE $key")
        }
        try {
            val exception = expectFailure { AiClient().generate(configFor(source), key, diff) {} }
            assertTrue(exception.message!!.contains("redirect"))
            assertEquals(1, sourceCalls.get())
            assertEquals(0, targetCalls.get())
        } finally {
            source.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun `real HTTP server error does not retry`() {
        val calls = AtomicInteger()
        val server = server { exchange -> calls.incrementAndGet(); reply(exchange, 503, "PRIVATE RESPONSE $key") }
        try {
            expectFailure { AiClient().generate(configFor(server), key, diff) {} }
            assertEquals(1, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chunked oversized response is canceled without relying on Content Length`() {
        val server = server { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { output -> repeat(128) { output.write(ByteArray(1024) { 'x'.code.toByte() }) } }
            } catch (_: IOException) {
                // Expected when the client closes its capped response.
            } finally {
                exchange.close()
            }
        }
        try {
            assertTrue(expectFailure { AiClient().generate(configFor(server), null, diff) {} }.message!!.contains("size limit"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `real stalled response times out and cancels exchange`() {
        val release = CountDownLatch(1)
        val server = server { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                release.await(2, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        try {
            val client = AiClient(AiProtocolHttpTransport(), Duration.ofMillis(150))
            assertTrue(expectFailure { client.generate(configFor(server), null, diff) {} }.message!!.contains("timed out"))
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private fun client(transport: AiProtocolTransport) = AiClient(transport, Duration.ofSeconds(2))

    private fun expectFailure(block: () -> Unit): AiClientException = try {
        block()
        throw AssertionError("Expected AiClientException")
    } catch (e: AiClientException) {
        e
    }

    private class RecordingTransport(val respond: (HttpRequest) -> HttpResponse<ByteArray>) : AiProtocolTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun send(request: HttpRequest): CompletableFuture<HttpResponse<ByteArray>> {
            requests.add(request)
            return CompletableFuture.completedFuture(respond(request))
        }
    }

    private fun response(request: HttpRequest, text: String, status: Int = 200) = object : HttpResponse<ByteArray> {
        override fun statusCode(): Int = status
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<ByteArray>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): ByteArray = text.toByteArray(UTF_8)
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private fun body(request: HttpRequest): JsonObject {
        val bytes = ByteArrayOutputStream()
        val done = CompletableFuture<ByteArray>()
        request.bodyPublisher().get().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) {
                val chunk = ByteArray(item.remaining())
                item.get(chunk)
                bytes.write(chunk)
            }
            override fun onError(error: Throwable) { done.completeExceptionally(error) }
            override fun onComplete() { done.complete(bytes.toByteArray()) }
        })
        return JsonParser.parseString(String(done.get(2, TimeUnit.SECONDS), UTF_8)).asJsonObject
    }

    private class TestSubscription : Flow.Subscription {
        var canceled = false
        override fun request(n: Long) = Unit
        override fun cancel() { canceled = true }
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange -> handler(exchange) }
            start()
        }

    private fun configFor(server: HttpServer) = local.copy(baseUrl = "http://127.0.0.1:${server.address.port}/v1")

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
