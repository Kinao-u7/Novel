package com.example.data.source.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.dokar.quickjs.QuickJsException

class QuickJsBridgeApiTest {

    @Test(timeout = 20_000)
    fun testAsyncFunctionNoArgs(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("testNoArgs") {
                delay(10)
                "ok"
            }
            val result = quickJs.evaluate<String>("await testNoArgs()")
            assertEquals("ok", result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testAsyncFunctionTypedStringArg(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("testTypedArg") { input: String ->
                delay(10)
                "hello $input"
            }
            val result = quickJs.evaluate<String>("await testTypedArg('world')")
            assertEquals("hello world", result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testAsyncFunctionUntypedArgs(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("testUntypedArgs") { args: Array<Any?> ->
                delay(10)
                "${args[0]}-${args[1]}"
            }
            val result = quickJs.evaluate<String>("await testUntypedArgs('foo', 42)")
            assertEquals("foo-42", result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testFunctionTypedStringArg(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.function("testFuncTyped") { input: String ->
                "sync $input"
            }
            val result = quickJs.evaluate<String>("testFuncTyped('bar')")
            assertEquals("sync bar", result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testFunctionUntypedArgs(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.function("testFuncUntyped") { args: Array<Any?> ->
                "${args[0]}:${args[1]}"
            }
            val result = quickJs.evaluate<String>("testFuncUntyped('baz', 99)")
            assertEquals("baz:99", result)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testSyncFunctionExceptionBehavior(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.function("throwingSync") {
                throw IllegalStateException("sync error message")
            }

            // Case A: JS tries to catch the exception from synchronous function:
            // try { f() } catch (e) { return e.name + ":" + e.message }
            var caughtInJs: String? = null
            var escapedFromJsTryCatch: Throwable? = null
            try {
                caughtInJs = quickJs.evaluate<String>(
                    """
                    (function() {
                        try {
                            throwingSync();
                            return "not_caught";
                        } catch (e) {
                            return (e.name || "Error") + ":" + (e.message || String(e));
                        }
                    })()
                    """.trimIndent()
                )
            } catch (t: Throwable) {
                escapedFromJsTryCatch = t
            }

            println("[testSyncFunctionExceptionBehavior] JS try-catch result=$caughtInJs, escapedException=${escapedFromJsTryCatch?.let { it::class.java.name + ": " + it.message }}")

            // Case B: Uncaught in JS: throwingSync()
            var uncaughtInKotlin: Throwable? = null
            try {
                quickJs.evaluate<Any?>("throwingSync();")
            } catch (t: Throwable) {
                uncaughtInKotlin = t
            }
            println("[testSyncFunctionExceptionBehavior] uncaught evaluate exception=${uncaughtInKotlin?.let { it::class.java.name + ": " + it.message }}")

            // In quickjs-kt, synchronous function exceptions escape evaluate directly as the original Kotlin exception,
            // bypassing JavaScript try/catch completely:
            assertNotNull("Exception must escape evaluate directly to Kotlin", escapedFromJsTryCatch)
            assertTrue("Escaped exception must be IllegalStateException, got: ${escapedFromJsTryCatch!!::class.java.name}", escapedFromJsTryCatch is IllegalStateException)
            assertEquals("sync error message", escapedFromJsTryCatch.message)

            assertNotNull("Uncaught call must throw Kotlin exception", uncaughtInKotlin)
            assertTrue("Uncaught exception must be IllegalStateException, got: ${uncaughtInKotlin!!::class.java.name}", uncaughtInKotlin is IllegalStateException)
            assertEquals("sync error message", uncaughtInKotlin.message)
        } finally {
            quickJs.close()
        }
    }

    @Test(timeout = 20_000)
    fun testAsyncFunctionExceptionBehavior(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("throwingAsync") {
                delay(10)
                throw IllegalStateException("async error message")
            }

            // Case A: JS catches rejected promise:
            // try { await f() } catch (e) { return e.name + ":" + e.message }
            val caughtInJs = quickJs.evaluate<String>(
                """
                (async function() {
                    try {
                        await throwingAsync();
                        return "not_caught";
                    } catch (e) {
                        return (e.name || "Error") + ":" + (e.message || String(e));
                    }
                })()
                """.trimIndent()
            )
            println("[testAsyncFunctionExceptionBehavior] JS try-catch result=$caughtInJs")
            assertEquals("Error:async error message", caughtInJs)

            // Case B: Uncaught in JS: await throwingAsync()
            var uncaughtInKotlin: Throwable? = null
            try {
                quickJs.evaluate<Any?>("await throwingAsync();")
            } catch (t: Throwable) {
                uncaughtInKotlin = t
            }
            println("[testAsyncFunctionExceptionBehavior] uncaught evaluate exception=${uncaughtInKotlin?.let { it::class.java.name + ": " + it.message }}")
            assertNotNull("Uncaught asyncFunction must surface to Kotlin", uncaughtInKotlin)
            assertTrue("Uncaught asyncFunction must surface as QuickJsException, got: ${uncaughtInKotlin!!::class.java.name}", uncaughtInKotlin is QuickJsException)
            assertTrue("Exception message must contain error message", uncaughtInKotlin.message!!.contains("async error message"))
        } finally {
            quickJs.close()
        }
    }
}
