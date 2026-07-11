package com.mp3downloader.domain.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe bounded LRU cache. Evicts the least-recently-accessed entry once
 * [maxEntries] is exceeded. Implemented by holding a [LinkedHashMap] instance
 * (not subclassing it) to avoid a Kotlin IR bug with `object : LinkedHashMap`.
 *
 * Methods are suspending so they can be safely called from any dispatcher.
 */
class LruCache<K, V>(private val maxEntries: Int) {
    private val map = LinkedHashMap<K, V>(maxEntries, 0.75f, true)
    private val mutex = Mutex()

    suspend fun get(key: K): V? = mutex.withLock { map[key] }

    suspend fun put(key: K, value: V) = mutex.withLock {
        map[key] = value
        if (map.size > maxEntries) {
            val iterator = map.keys.iterator()
            if (iterator.hasNext()) iterator.remove()
        }
    }

    suspend fun remove(key: K): V? = mutex.withLock { map.remove(key) }
}
