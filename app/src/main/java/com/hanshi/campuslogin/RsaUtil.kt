package com.hanshi.campuslogin

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/** CAS 密码加密：RSA PKCS#1 v1.5，Base64 后加 `__RSA__` 前缀（与服务器约定一致） */
object RsaUtil {

    fun encryptPassword(password: String, publicKeyText: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, parsePublicKey(publicKeyText))
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return "__RSA__" + Base64.getEncoder().encodeToString(encrypted)
    }

    /** 支持 PEM（BEGIN PUBLIC KEY / BEGIN RSA PUBLIC KEY）以及不带头尾的 Base64 */
    fun parsePublicKey(text: String): PublicKey {
        val body = text.lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        val der = Base64.getDecoder().decode(body)
        val factory = KeyFactory.getInstance("RSA")
        return try {
            factory.generatePublic(X509EncodedKeySpec(der))
        } catch (e: InvalidKeySpecException) {
            // PKCS#1 格式：SEQUENCE { INTEGER 模数, INTEGER 公钥指数 }
            val reader = DerReader(der)
            reader.enterSequence()
            factory.generatePublic(RSAPublicKeySpec(reader.readInteger(), reader.readInteger()))
        }
    }

    /** 仅够解析 PKCS#1 公钥的最小 DER 读取器 */
    private class DerReader(private val data: ByteArray) {
        private var pos = 0

        fun enterSequence() {
            require(data[pos++].toInt() == 0x30) { "公钥格式错误：不是 SEQUENCE" }
            readLength()
        }

        fun readInteger(): BigInteger {
            require(data[pos++].toInt() == 0x02) { "公钥格式错误：不是 INTEGER" }
            val len = readLength()
            val value = BigInteger(data.copyOfRange(pos, pos + len))
            pos += len
            return value
        }

        private fun readLength(): Int {
            val first = data[pos++].toInt() and 0xFF
            if (first < 0x80) return first
            var len = 0
            repeat(first and 0x7F) { len = (len shl 8) or (data[pos++].toInt() and 0xFF) }
            return len
        }
    }
}
