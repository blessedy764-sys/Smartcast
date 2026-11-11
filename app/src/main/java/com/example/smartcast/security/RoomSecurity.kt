package com.gitco.smartcast.security

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object RoomSecurity {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128 // 16 bytes authentication tag
    private const val IV_LENGTH_BYTE = 12  // Standard GCM IV size

    /**
     * Derives a secure 256-bit AES key from a human-readable room PIN.
     */
    fun deriveKey(roomPin: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(roomPin.toCharArray(), salt, 65536, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Encrypts the Opus payload on the Host before sending over UDP.
     */
    fun encrypt(plainText: ByteArray, secretKey: SecretKeySpec, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        return cipher.doFinal(plainText)
    }

    /**
     * Decrypts the Opus payload on the Receiver. Returns null if PIN is incorrect.
     */
    fun decrypt(cipherText: ByteArray, secretKey: SecretKeySpec, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            // Decryption failed (Wrong room PIN or corrupted packet)
            null
        }
    }
}
