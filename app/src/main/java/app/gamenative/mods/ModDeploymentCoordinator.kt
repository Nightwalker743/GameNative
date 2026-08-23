package app.gamenative.mods

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ModDeploymentCoordinator {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withGameLock(appId: String, block: suspend () -> T): T {
        val key = appId.ifBlank { "unknown-game" }
        val entry = synchronized(entries) {
            entries.getOrPut(key, ::Entry).also { it.users++ }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                entry.users--
                if (entry.users == 0) entries.remove(key, entry)
            }
        }
    }
}
