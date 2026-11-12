package com.foldbook

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 공유폴더 비밀번호를 파일에 평문으로 두지 않기 위한 암호화 (Android Keystore, AES-GCM).
 * 저장 형식: `enc1:` + base64(iv 12바이트 + 암호문). 접두어가 없으면 예전 버전의 평문으로 본다.
 */
object PassCrypt {

    private const val PREFIX = "enc1:"
    private const val ALIAS = "foldbook-conn-pass"

    fun isEncrypted(stored: String) = stored.startsWith(PREFIX)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    /** 암호화에 실패하면(키스토어 오류) 연결을 못 쓰게 되는 것보다 평문 저장이 낫다. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain   // 못 푼 암호문은 그대로 다시 저장
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** 복호화에 실패하면 암호문을 그대로 돌려준다 (일시적인 키스토어 오류로 저장된 값을 잃지 않도록). */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            }
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrDefault(stored)
    }
}
