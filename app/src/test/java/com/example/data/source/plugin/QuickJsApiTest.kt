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
    fun testEvaluateOnePlusOne() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            assertEquals(2, it.evaluate<Int>("1 + 1"))
        }
    }

    @Test(timeout = 20_000)
    fun testEvaluationTimeoutMillisExists() {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.evaluationTimeoutMillis = 5000L
            assertEquals(5000L, it.evaluationTimeoutMillis)
        }
    }

    @Test(timeout = 20_000)
    fun testInterruptEvaluationExists() {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.interruptEvaluation()
        }
    }

    @Test(timeout = 20_000)
    fun testMemoryLimitExists() {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.memoryLimit = 16 * 1024 * 1024L
            assertEquals(16 * 1024 * 1024L, it.memoryLimit)
        }
    }

    @Test(timeout = 20_000)
    fun testStackLimitExists() {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.maxStackSize = 512 * 1024L
            assertEquals(512 * 1024L, it.maxStackSize)
        }
    }

    @Test(timeout = 20_000)
    fun testBytecodeCompileAndEvaluateExists() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            val bytecode: ByteArray = it.compile(code = "2 * 21", filename = "test.js")
            assertNotNull(bytecode)
            assertTrue(bytecode.isNotEmpty())
            assertEquals(42, it.evaluate<Int>(bytecode))
        }
    }

    @Test(timeout = 20_000)
    fun testPassingDispatcherAtCreationExists() = runBlocking<Unit> {
        val customExecutor = Executors.newSingleThreadExecutor { Thread(it, "quickjs-test-worker") }
        val customDispatcher = customExecutor.asCoroutineDispatcher()
        try {
            QuickJs.create(jobDispatcher = customDispatcher).use {
                assertEquals("hello world", it.evaluate<String>("'hello ' + 'world'"))
            }
        } finally {
            customDispatcher.close()
            customExecutor.shutdown()
        }
    }

    @Test(timeout = 20_000)
    fun testAsyncFunctionExists() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.asyncFunction("fetchMessage") { delay(20); "pong" }
            assertEquals("pong", it.evaluate<String>("await fetchMessage()"))
        }
    }

    @Test(timeout = 20_000)
    fun testRejectedPromiseThrows() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            var caught: Throwable? = null
            try {
                it.evaluate<Any?>("async function fail() { throw new Error('boom'); }; await fail();")
            } catch (e: Throwable) { caught = e }
            assertNotNull("Expected exception when awaiting rejected promise", caught)
            assertTrue(caught is Exception)
        }
    }

    @Test(timeout = 20_000)
    fun testAsyncFunctionErrorSurfaces() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.asyncFunction("failingApi") { throw IllegalStateException("Kotlin bridge failed") }
            val caughtInJs = it.evaluate<String>(
                "var msg = ''; try { await failingApi(); } catch (e) { msg = e.message || String(e); }; msg;"
            )
            assertTrue(caughtInJs.contains("Kotlin bridge failed"))

            var caughtInKotlin: Throwable? = null
            try { it.evaluate<Any?>("await failingApi();") } catch (e: Throwable) { caughtInKotlin = e }
            assertNotNull(caughtInKotlin)
        }
    }

    @Test(timeout = 20_000)
    fun testPromiseAllWithAsyncFunction() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.asyncFunction("fetchA") { delay(20); "partA" }
            it.asyncFunction("fetchB") { delay(20); "partB" }
            val result = it.evaluate<List<*>>("await Promise.all([fetchA(), fetchB()])")
            assertEquals(2, result.size)
            assertEquals("partA", result[0])
            assertEquals("partB", result[1])
        }
    }

    @Test(timeout = 20_000)
    fun testInfiniteLoopIsInterrupted() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.evaluationTimeoutMillis = 500L
            val start = System.currentTimeMillis()
            var caught: Throwable? = null
            try { it.evaluate<Any?>("while(true){}") } catch (e: Throwable) { caught = e }
            val elapsed = System.currentTimeMillis() - start
            assertNotNull(caught)
            assertTrue(elapsed < 5_000L)
        }
    }

    @Test(timeout = 20_000)
    fun testPromiseLoopIsInterrupted() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.evaluationTimeoutMillis = 500L
            val start = System.currentTimeMillis()
            var caught: Throwable? = null
            try {
                it.evaluate<Any?>("function loop() { return Promise.resolve().then(loop); }; loop(); await new Promise(() => {});")
            } catch (e: Throwable) { caught = e }
            val elapsed = System.currentTimeMillis() - start
            assertNotNull(caught)
            assertTrue(elapsed < 5_000L)
        }
    }

    @Test(timeout = 20_000)
    fun testMemoryLimitEnforced() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            it.memoryLimit = 16 * 1024 * 1024L
            it.evaluationTimeoutMillis = 10_000L
            var caught: Throwable? = null
            try {
                it.evaluate<Any?>("const chunks = []; while(true) { chunks.push(new Array(100000).fill('large_string')); }")
            } catch (e: Throwable) { caught = e }
            assertNotNull(caught)
            val msgOrClass = (caught?.message ?: "") + " " + (caught?.let { c -> c::class.java.simpleName } ?: "")
            assertTrue(msgOrClass.contains("memory", ignoreCase = true) || msgOrClass.contains("out of memory", ignoreCase = true))
        }
    }

    @Test(timeout = 20_000)
    fun testCancellationInterrupts() = runBlocking<Unit>(Dispatchers.Default) {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use {
            val job = launch(Dispatchers.Default) {
                it.evaluate<Any?>("while (true) {}")
            }
            delay(100)
            val cancelStart = System.currentTimeMillis()
            job.cancelAndJoin()
            val cancelElapsed = System.currentTimeMillis() - cancelStart
            assertTrue(cancelElapsed < 5_000L)
        }
    }
}
