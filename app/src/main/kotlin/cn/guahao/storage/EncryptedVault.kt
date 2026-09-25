package cn.guahao.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.AtomicFile

class EncryptedVault(context: Context) : SecretStore {
    private val directory = context.noBackupFilesDir
    private val alias = "guahao.v01.local"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
        }.generateKey()
    }
    fun encrypt(text: String, context: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }
    fun decrypt(bytes: ByteArray, context: String): String {
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes, 13, bytes.size - 13), Charsets.UTF_8)
    }
    @Synchronized override fun write(name: String, text: String) {
        require(Regex("[a-zA-Z0-9._-]+").matches(name))
        val file = AtomicFile(java.io.File(directory, name))
        val stream = file.startWrite()
        try { stream.write(encrypt(text, name)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized override fun read(name: String): String? {
        require(Regex("[a-zA-Z0-9._-]+").matches(name))
        val file = AtomicFile(java.io.File(directory, name))
        return if (file.baseFile.exists()) decrypt(file.readFully(), name) else null
    }
}
