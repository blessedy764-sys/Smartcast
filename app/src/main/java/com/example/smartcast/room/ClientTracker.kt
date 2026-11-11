package com.example.smartcast.room

import java.util.concurrent.ConcurrentHashMap

data class ClientInfo(
    val clientId: String,
    val address: String,
    val firstSeenTimestamp: Long,
    val lastSeenTimestamp: Long
)

class ClientTracker(
    private val timeoutMs: Long = 5_000L
) {
    private val clients = ConcurrentHashMap<String, ClientInfo>()

    /** Call when a heartbeat arrives. Adds the client or refreshes its last-seen time. */
    fun onHeartbeat(
        clientId: String,
        address: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val existing = clients[clientId]
        val first = existing?.firstSeenTimestamp ?: nowMs
        clients[clientId] = ClientInfo(
            clientId = clientId,
            address = address,
            firstSeenTimestamp = first,
            lastSeenTimestamp = nowMs
        )
    }

    /** Removes clients not heard from within the timeout. Returns the removed client IDs. */
    fun removeStale(nowMs: Long = System.currentTimeMillis()): List<String> {
        val removed = mutableListOf<String>()
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenTimestamp > timeoutMs) {
                removed.add(entry.key)
                iterator.remove()
            }
        }
        return removed
    }

    fun remove(clientId: String) {
        clients.remove(clientId)
    }

    fun isActive(clientId: String): Boolean = clients.containsKey(clientId)

    fun activeClients(): List<ClientInfo> = clients.values.toList()

    fun count(): Int = clients.size

    fun clear() {
        clients.clear()
    }
}
