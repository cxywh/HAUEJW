package com.cxywh.hauejw

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 「记住密码」的加密存储。
 *
 * 密码用 AES-256-GCM 加密后才写进本地文件，加密密钥由系统密钥库（AndroidKeyStore）生成并保管：
 *  - 密钥本身不出密钥库，硬件支持时（TEE/StrongBox）连 App 进程都拿不到明文密钥；
 *  - 密文带随机 IV，同一密码每次写出来的密文都不一样；
 *  - App 卸载、清除数据、或密钥被系统作废（如改锁屏密码导致 KeyStore 重置）都会自动失效，读不到就返回 null。
 *
 * 边界说明：这挡的是「拷走手机数据文件就能读出明文密码」，挡不住已经 root 且拿到本机最高权限的攻击者
 * （那种情况下任何本地存储都保不住）。所以默认不勾选，由用户自己决定。
 */
object SecretStore {

    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "hauejw_password_key"
    private const val SP = "hauejw_sec"
    private const val K_IV = "iv"
    private const val K_CT = "ct"

    // 会话 Cookie 单独一对键，和密码共用同一把密钥、同一个加密文件
    private const val K_SIV = "s_iv"
    private const val K_SCT = "s_ct"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** 写入密码；失败（如设备无密钥库）返回 false，此时界面应提示「本机不支持」而不是静默假装成功 */
    fun save(ctx: Context, password: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(password.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString(K_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString(K_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .commit()
        true
    } catch (e: Exception) {
        false
    }

    /** 取出密码；没存过、密钥失效、数据损坏都返回 null */
    fun load(ctx: Context): String? = try {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val iv = sp.getString(K_IV, null)
        val ct = sp.getString(K_CT, null)
        if (iv == null || ct == null) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
            String(c.doFinal(Base64.decode(ct, Base64.DEFAULT)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().commit()
        } catch (_: Exception) {
        }
        try {
            KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }

    fun has(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).contains(K_CT)
    } catch (_: Exception) {
        false
    }

    /**
     * 会话 Cookie（JSESSIONID 这类）也加密存本机。
     * 之前它只活在进程内存里，App 一被系统回收或重装，直达页就掉登录 —— 页面能打开但按钮点了没反应。
     * 存的是会话标识不是密码，但同样按凭据对待：AES-GCM 加密，卸载/清数据即失效。
     */
    fun saveSession(ctx: Context, cookie: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(cookie.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString(K_SIV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString(K_SCT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .commit()
        true
    } catch (e: Exception) {
        false
    }

    fun loadSession(ctx: Context): String? = try {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val iv = sp.getString(K_SIV, null)
        val ct = sp.getString(K_SCT, null)
        if (iv == null || ct == null) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
            String(c.doFinal(Base64.decode(ct, Base64.DEFAULT)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    fun clearSession(ctx: Context) {
        try {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .remove(K_SIV).remove(K_SCT).commit()
        } catch (_: Exception) {
        }
    }
}
