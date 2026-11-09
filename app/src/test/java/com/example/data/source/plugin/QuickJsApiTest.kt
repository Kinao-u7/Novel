package com.example.data.source.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJsApiTest {

    @Test(timeout = 20_000)
    fun testEvaluateOnePlusOne() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val result = quickJs.evaluate<Int>("1 + 1")
            assertEquals(2, result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testEvaluationTimeoutMillisExists() {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.evaluationTimeoutMillis = 5000L
            assertEquals(5000L, quickJs.evaluationTimeoutMillis)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testInterruptEvaluationExists() {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.interruptEvaluation()
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testMemoryLimitExists() {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val limit = 16 * 1024 * 1024L
            quickJs.memoryLimit = limit
            assertEquals(limit, quickJs.memoryLimit)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testStackLimitExists() {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val stackSize = 512 * 1024L
            quickJs.maxStackSize = stackSize
            assertEquals(stackSize, quickJs.maxStackSize)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testBytecodeCompileAndEvaluateExists() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val bytecode: ByteArray = quickJs.compile(code = "2 * 21", filename = "test.js")
            assertNotNull(bytecode)
            assertTrue(bytecode.isNotEmpty())
            val result = quickJs.evaluate<Int>(bytecode)
            assertEquals(42, result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testPassingDispatcherAtCreationExists() = runBlocking {
        val customExecutor = Executors.newSingleThreadExecutor { Thread(it, "quickjs-test-worker") }
        val customDispatcher = customExecutor.asCoroutineDispatcher()
        try {
            val quickJs = QuickJs.create(jobDispatcher = customDispatcher)
            try {
                val result = quickJs.evaluate<String>("'hello ' + 'world'")
                assertEquals("hello world", result)
            } finally {
                quickJs.close()
            }
        } finally {
            customDispatcher.close()
            customExecutor.shutdown()
        }
    }

    @Test(timeout = 20_000)
    fun testAsyncFunctionExists() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("fetchMessage") {
                delay(20)
                "pong"
            }
            val result = quickJs.evaluate<String>("await fetchMessage()")
            assertEquals("pong", result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testRejectedPromiseThrows() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val start = System.currentTimeMillis()
            var caught: Throwable? = null
            try {
                quickJs.evaluate<Any?>("async function fail() { throw new Error('boom'); }; await fail();")
            } catch (e: Throwable) {
                caught = e
            }
            val elapsed = System.currentTimeMillis() - start
            println("[testRejectedPromiseThrows] class=${caught?.let { it::class.java.name }}, message=${caught?.message}, elapsed=${elapsed}ms")
            assertNotNull("Expected exception when awaiting rejected promise", caught)
            assertTrue("Expected Exception, got: ${caught!!::class.java.name}", caught is Exception)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testAsyncFunctionErrorSurfaces() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("failingApi") {
                throw IllegalStateException("Kotlin bridge failed")
            }
            // Verify error can be caught in JS with try/catch
            val caughtInJs = quickJs.evaluate<String>(
                """
                var message = "";
                try {
                    await failingApi();
                } catch (e) {
                    message = e.message || String(e);
                }
                message;
                """.trimIndent()
            )
            assertTrue("Expected JS to catch bridge exception, got: '$caughtInJs'", caughtInJs.contains("Kotlin bridge failed") || caughtInJs.isNotEmpty())

            // Verify uncaught error in JS surfaces as a Kotlin exception
            val start = System.currentTimeMillis()
            var caughtInKotlin: Throwable? = null
            try {
                quickJs.evaluate<Any?>("await failingApi();")
            } catch (e: Throwable) {
                caughtInKotlin = e
            }
            val elapsed = System.currentTimeMillis() - start
            println("[testAsyncFunctionErrorSurfaces] class=${caughtInKotlin?.let { it::class.java.name }}, message=${caughtInKotlin?.message}, elapsed=${elapsed}ms")
            assertNotNull("Expected uncaught asyncFunction error to surface to Kotlin", caughtInKotlin)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testPromiseAllWithAsyncFunction() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("fetchA") {
                delay(20)
                "partA"
            }
            quickJs.asyncFunction("fetchB") {
                delay(20)
                "partB"
            }
            val result = quickJs.evaluate<List<*>>("await Promise.all([fetchA(), fetchB()])")
            assertEquals(2, result.size)
            assertEquals("partA", result[0])
            assertEquals("partB", result[1])
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testInfiniteLoopIsInterrupted() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.evaluationTimeoutMillis = 500L
            val start = System.currentTimeMillis()
            var caught: Throwable? = null
            try {
                quickJs.evaluate<Any?>("while(true){}")
            } catch (e: Throwable) {
                caught = e
            }
            val elapsed = System.currentTimeMillis() - start
            println("[testInfiniteLoopIsInterrupted] class=${caught?.let { it::class.java.name }}, message=${caught?.message}, elapsed=${elapsed}ms")
            assertNotNull("Expected evaluationTimeoutMillis to interrupt infinite loop", caught)
            assertTrue("Expected interruption within ~5s, took ${elapsed}ms", elapsed < 5_000L)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testPromiseLoopIsInterrupted() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.evaluationTimeoutMillis = 500L
            val start = System.currentTimeMillis()
            var caught: Throwable? = null
            try {
                quickJs.evaluate<Any?>(
                    """
                    function loop() { return Promise.resolve().then(loop); }
                    loop();
                    await new Promise(() => {});
                    """.trimIndent()
                )
            } catch (e: Throwable) {
                caught = e
            }
            val elapsed = System.currentTimeMillis() - start
            println("[testPromiseLoopIsInterrupted] class=${caught?.let { it::class.java.name }}, message=${caught?.message}, elapsed=${elapsed}ms")
            assertNotNull("Expected evaluationTimeoutMillis to interrupt promise loop", caught)
            assertTrue("Expected interruption within ~5s, took ${elapsed}ms", elapsed < 5_000L)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testMemoryLimitEnforced() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.memoryLimit = 16 * 1024 * 1024L
            quickJs.evaluationTimeoutMillis = 10_000L
            val start = System.currentTimeMillis()
            var caught: Throwable? = null
            try {
                quickJs.evaluate<Any?>(
                    """
                    const chunks = [];
                    while (true) {
                        chunks.push(new Array(100000).fill("large_data_string_for_memory_filling"));
                    }
                    """.trimIndent()
                )
            } catch (e: Throwable) {
                caught = e
            }
            val elapsed = System.currentTimeMillis() - start
            println("[testMemoryLimitEnforced] class=${caught?.let { it::class.java.name }}, message=${caught?.message}, elapsed=${elapsed}ms")
            assertNotNull("Expected memoryLimit to throw an out-of-memory exception", caught)
            assertTrue("Expected exception to arrive in under 1500ms, took ${elapsed}ms", elapsed < 1500L)
            val msgOrClass = (caught?.message ?: "") + " " + (caught?.let { it::class.java.simpleName } ?: "")
            assertTrue(
                "Expected class or message to indicate memory error, got class='${caught?.let { it::class.java.name }}', message='${caught?.message}'",
                msgOrClass.contains("memory", ignoreCase = true) || msgOrClass.contains("out of memory", ignoreCase = true)
            )
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testCancellationInterrupts() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            assertEquals(-1L, quickJs.evaluationTimeoutMillis)
            var childException: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try {
                    quickJs.evaluate<Any?>("while (true) {}")
                } catch (e: Throwable) {
                    childException = e
                    throw e
                }
            }
            delay(100)
            val cancelStart = System.currentTimeMillis()
            job.cancelAndJoin()
            val cancelElapsed = System.currentTimeMillis() - cancelStart

            println("[testCancellationInterrupts] class=${childException?.let { it::class.java.name }}, message=${childException?.message}, cancelElapsed=${cancelElapsed}ms")
            assertTrue("Expected cancelAndJoin to return within 5s, took ${cancelElapsed}ms", cancelElapsed < 5_000L)
        } finally {
            quickJs.close()
        }
    }
}
