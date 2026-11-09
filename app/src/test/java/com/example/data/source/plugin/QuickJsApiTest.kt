package com.example.data.source.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJsApiTest {

    @Test
    fun testEvaluateOnePlusOne() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val result = quickJs.evaluate<Int>("1 + 1")
            assertEquals(2, result)
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun testEvaluationTimeoutMillisExists() {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.evaluationTimeoutMillis = 5000L
            assertEquals(5000L, quickJs.evaluationTimeoutMillis)
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun testInterruptEvaluationExists() {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            // Calling interruptEvaluation when idle should be safe and execute cleanly
            quickJs.interruptEvaluation()
        } finally {
            quickJs.close()
        }
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
    fun testAsyncFunctionExists() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("fetchMessage") {
                delay(20)
                "pong"
            }
            val result = quickJs.evaluate<String>("async function test() { return await fetchMessage(); }; test();")
            assertEquals("pong", result)
        } finally {
            quickJs.close()
        }
    }
}
