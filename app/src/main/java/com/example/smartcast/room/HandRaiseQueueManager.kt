package com.smartcast.live.qa

import com.smartcast.live.network.PttReceiver
import java.security.SecureRandom

/**
 * Host-side hand raise queue. Approving a guest opens an encrypted PTT session on the
 * [PttReceiver]; releasing, replacing or clearing the speaker closes it.
 */
class HandRaiseQueueManager(
    private val pttReceiver: PttReceiver,
    private val onQueueChanged: (List<Long>) -> Unit
) {
    private val pendingQueue = mutableListOf<Long>()

    // Random start so a host restart is very unlikely to reuse IDs under the same room key.
    // Generating a fresh room key per tour is still the safest option.
    private var lastSessionId = SecureRandom().nextInt()

    var activeSpeakerId: Long? = null
        private set
    var activeSessionId: Int? = null
        private set

    @Synchronized
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
                    endActiveSession()
                }
                pendingQueue.remove(packet.clientId)
                onQueueChanged(pendingQueue.toList())
            }
            else -> {}
        }
    }

    /**
     * Approves the next guest in the queue and returns the APPROVE packet to send to them
     * (it carries the PTT session ID), or null if nobody is waiting.
     */
    @Synchronized
    fun approveNext(): HandRaisePacket? {
        if (pendingQueue.isEmpty()) return null
        endActiveSession() // close the previous speaker's session, if any
        val nextClient = pendingQueue.removeAt(0)
        val sessionId = allocateSessionId()
        pttReceiver.openSession(sessionId)
        activeSpeakerId = nextClient
        activeSessionId = sessionId
        onQueueChanged(pendingQueue.toList())
        return HandRaisePacket(nextClient, HandRaiseAction.APPROVE, sessionId)
    }

    @Synchronized
    fun clearActiveSpeaker() {
        endActiveSession()
    }

    private fun endActiveSession() {
        activeSessionId?.let { pttReceiver.closeSession(it) }
        activeSessionId = null
        activeSpeakerId = null
    }

    private fun allocateSessionId(): Int {
        do { lastSessionId++ } while (lastSessionId == 0) // 0 means "no session" on the wire
        return lastSessionId
    }
}
