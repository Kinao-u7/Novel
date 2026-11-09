package com.example.data.source.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickJsBridgeApiTest {

    @Test(timeout = 20_000)
    fun testAsyncFunctionNoArgs() = runBlocking(Dispatchers.Default) {
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
    fun testAsyncFunctionTypedStringArg() = runBlocking(Dispatchers.Default) {
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
    fun testAsyncFunctionUntypedArgs() = runBlocking(Dispatchers.Default) {
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
    fun testFunctionTypedStringArg() = runBlocking(Dispatchers.Default) {
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
    fun testFunctionUntypedArgs() = runBlocking(Dispatchers.Default) {
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
}
