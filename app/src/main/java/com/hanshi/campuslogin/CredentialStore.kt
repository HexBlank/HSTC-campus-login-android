package com.hanshi.campuslogin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号密码加密保存：AES-256-GCM 密钥生成并保存在 Android Keystore（硬件/TEE 保护，不可导出），
 * SharedPreferences 里只存密文。应用已禁止备份，换机或卸载后需要重新输入。
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun save(account: String, password: String) {
        prefs.edit {
            putString(KEY_ACCOUNT, encrypt(account))
            putString(KEY_PASSWORD, encrypt(password))
        }
    }

    /** 返回 (账号, 密码)；未保存或解密失败（如密钥被系统清除）时返回 null */
    fun load(): Pair<String, String>? {
        val account = prefs.getString(KEY_ACCOUNT, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            decrypt(account) to decrypt(password)
        } catch (e: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** 密文格式：Base64(12 字节 IV + GCM 密文) */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sealed)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        return String(cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "campus_login_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_ACCOUNT = "account"
        const val KEY_PASSWORD = "password"
    }
}
