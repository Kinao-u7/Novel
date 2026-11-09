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
            var caught: Throwable? = null
            try {
                quickJs.evaluate<Any?>("async function fail() { throw new Error('boom'); }; await fail();")
            } catch (e: Throwable) {
                caught = e
            }
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
            var caughtInKotlin: Throwable? = null
            try {
                quickJs.evaluate<Any?>("await failingApi();")
            } catch (e: Throwable) {
                caughtInKotlin = e
            }
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
            val result = quickJs.evaluate<Array<Any?>>("await Promise.all([fetchA(), fetchB()])")
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
            quickJs.evaluationTimeoutMillis = 2000L
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
            assertNotNull("Expected memory limit or timeout to throw an exception", caught)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testCancellationInterrupts() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val start = System.currentTimeMillis()
            val job = launch(Dispatchers.Default) {
                quickJs.evaluate<Any?>("while (true) {}")
            }
            delay(100)
            job.cancelAndJoin()
            val elapsed = System.currentTimeMillis() - start
            assertTrue("Expected cancellation to stop quickly, took ${elapsed}ms", elapsed < 5_000L)
            assertTrue(job.isCancelled)
        } finally {
            quickJs.close()
        }
    }
}
