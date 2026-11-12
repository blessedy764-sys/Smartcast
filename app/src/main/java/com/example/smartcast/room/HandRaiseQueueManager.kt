package com.smartcast.live.qa

class HandRaiseQueueManager(private val onQueueChanged: (List<Long>) -> Unit) {
    private val pendingQueue = mutableListOf<Long>()
    var activeSpeakerId: Long? = null
        private set

    fun handleIncomingPacket(packet: HandRaisePacket) {
        when (packet.action) {
            HandRaiseAction.REQUEST -> {
                if (!pendingQueue.contains(packet.clientId) && activeSpeakerId != packet.clientId) {
                    pendingQueue.add(packet.clientId)
                    onQueueChanged(pendingQueue.toList())
                }
            }
            HandRaiseAction.RELEASE -> {
                if (activeSpeakerId == packet.clientId) {
                    activeSpeakerId = null
                }
                pendingQueue.remove(packet.clientId)
                onQueueChanged(pendingQueue.toList())
            }
            else -> {}
        }
    }

    fun approveNext(): Long? {
        if (pendingQueue.isEmpty()) return null
        val nextClient = pendingQueue.removeAt(0)
        activeSpeakerId = nextClient
        onQueueChanged(pendingQueue.toList())
        return nextClient
    }

    fun clearActiveSpeaker() {
        activeSpeakerId = null
    }
}
