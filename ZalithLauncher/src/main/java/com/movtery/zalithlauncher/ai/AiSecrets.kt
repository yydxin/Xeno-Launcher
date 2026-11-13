/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密存储：Android Keystore 中的 AES/GCM 密钥，密文以 Base64 存 MMKV。
 * 明文只在内存中短暂存在，任何日志都不输出 Key。
 */
object AiSecrets {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "xzlc_ai_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    class SecretException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 加密失败必须显式失败，不能静默返回空串。 */
    fun encryptOrThrow(plain: String): String {
        if (plain.isBlank()) throw SecretException("API Key 不能为空")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Throwable) {
            throw SecretException(describe(e), e)
        }
    }

    fun decryptOrThrow(encoded: String): String {
        if (encoded.isBlank()) throw SecretException("本地没有保存 API Key")
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= IV_SIZE) throw SecretException("API Key 密文格式损坏，请重新填写")
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: SecretException) {
            throw e
        } catch (e: Throwable) {
            throw SecretException(describe(e), e)
        }
    }

    fun encrypt(plain: String): String = runCatching { encryptOrThrow(plain) }.getOrDefault("")

    fun decrypt(encoded: String): String = runCatching { decryptOrThrow(encoded) }.getOrDefault("")

    private fun describe(error: Throwable): String = when (error) {
        is android.security.keystore.KeyPermanentlyInvalidatedException ->
            "系统密钥已失效，请重新填写 API Key"
        is javax.crypto.AEADBadTagException ->
            "API Key 解密失败（密钥可能已重置），请重新填写"
        is java.security.UnrecoverableKeyException ->
            "系统密钥不可用，请重新填写 API Key"
        else -> "API Key 加密/解密失败：${error.message ?: error::class.java.simpleName}"
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
