package com.example.data.source.plugin

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ResponseByteStore(
    private val maxStoreBytes: Long = MAX_STORE_BYTES,
    private val maxBinaryBytes: Long = MAX_BINARY_BYTES
) {
    companion object {
        const val MAX_STORE_BYTES = 20 * 1024 * 1024L // 20 MB total store cap
        const val MAX_BINARY_BYTES = 5 * 1024 * 1024L // 5 MB per binary payload cap
    }

    private val idCounter = AtomicLong(1L)
    private val store = ConcurrentHashMap<Long, ByteArray>()
    private val totalStoredBytes = AtomicLong(0L)

    fun store(bytes: ByteArray): Long {
        val size = bytes.size.toLong()
        val currentTotal = totalStoredBytes.get()
        if (currentTotal + size > maxStoreBytes) {
            throw StoreCapacityExceededException("Store byte capacity exceeded (current: $currentTotal bytes, incoming: $size bytes, max: $maxStoreBytes bytes)")
        }

        val id = idCounter.getAndIncrement()
        store[id] = bytes
        totalStoredBytes.addAndGet(size)
        return id
    }

    fun get(id: Long): ByteArray? = store[id]

    fun getBinary(id: Long): ByteArray {
        val bytes = store[id] ?: throw NoSuchElementException("No stored response for id=$id")
        if (bytes.size > maxBinaryBytes) {
            throw PayloadTooLargeException("Binary response (${bytes.size} bytes) exceeds ${maxBinaryBytes / (1024 * 1024)} MB limit")
        }
        return bytes
    }

    fun remove(id: Long): ByteArray? {
        val removed = store.remove(id)
        if (removed != null) {
            totalStoredBytes.addAndGet(-removed.size.toLong())
        }
        return removed
    }

    fun clear() {
        store.clear()
        totalStoredBytes.set(0L)
    }

    fun size(): Int = store.size

    fun totalBytes(): Long = totalStoredBytes.get()
}
