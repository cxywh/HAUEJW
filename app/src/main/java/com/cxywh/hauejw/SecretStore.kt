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
 * 「记住密码」的加密存储，**按学号分账号**存放。
 *
 * 密码用 AES-256-GCM 加密后才写进本地文件，加密密钥由系统密钥库（AndroidKeyStore）生成并保管：
 *  - 密钥本身不出密钥库，硬件支持时（TEE/StrongBox）连 App 进程都拿不到明文密钥；
 *  - 密文带随机 IV，同一密码每次写出来的密文都不一样；
 *  - 键名带学号后缀（ct@2023123456），多账号互不干扰，删一个账号不影响别的账号；
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
    private const val K_SIV = "s_iv"
    private const val K_SCT = "s_ct"

    /** 键名带上学号：ct@2023123456 / s_iv@2023123456 */
    private fun k(base: String, account: String) = "$base@$account"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

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

    private fun put(ctx: Context, account: String, ivKey: String, ctKey: String, value: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        sp(ctx).edit()
            .putString(k(ivKey, account), Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString(k(ctKey, account), Base64.encodeToString(ct, Base64.NO_WRAP))
            .commit()
        true
    } catch (e: Exception) {
        false
    }

    private fun get(ctx: Context, account: String, ivKey: String, ctKey: String): String? = try {
        val p = sp(ctx)
        val iv = p.getString(k(ivKey, account), null)
        val ct = p.getString(k(ctKey, account), null)
        if (iv == null || ct == null) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
            String(c.doFinal(Base64.decode(ct, Base64.DEFAULT)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    /** 写入该账号的密码；失败（如设备无密钥库）返回 false，此时界面应提示「本机不支持」而不是静默假装成功 */
    fun save(ctx: Context, account: String, password: String): Boolean =
        account.isNotBlank() && put(ctx, account, K_IV, K_CT, password)

    /** 取出该账号的密码；没存过、密钥失效、数据损坏都返回 null */
    fun load(ctx: Context, account: String): String? =
        if (account.isBlank()) null else get(ctx, account, K_IV, K_CT)

    fun has(ctx: Context, account: String): Boolean = try {
        account.isNotBlank() && sp(ctx).contains(k(K_CT, account))
    } catch (_: Exception) {
        false
    }

    /** 存过密码的学号清单（界面右下角「已记住的账号」用；顺序由 Prefs 管） */
    fun accounts(ctx: Context): List<String> = try {
        sp(ctx).all.keys
            .filter { it.startsWith("$K_CT@") }
            .map { it.removePrefix("$K_CT@") }
            // 校园卡查询凭据存在同一个库（键 card@学号），它不是账号，不能出现在切换列表里
            .filter { it.isNotBlank() && !it.contains("@") }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /** 只忘掉这一个账号的密码与会话，别的账号原样保留 */
    fun forget(ctx: Context, account: String) {
        if (account.isBlank()) return
        try {
            sp(ctx).edit()
                .remove(k(K_IV, account)).remove(k(K_CT, account))
                .remove(k(K_SIV, account)).remove(k(K_SCT, account))
                .commit()
        } catch (_: Exception) {
        }
    }

    /** 老版本只认一个账号，用的是没有后缀的键；升级后把这四个键挪到该学号名下，省得用户重输一遍密码 */
    fun migrateLegacy(ctx: Context, account: String) {
        if (account.isBlank()) return
        try {
            val p = sp(ctx)
            if (!p.contains(K_CT) && !p.contains(K_SIV)) return
            val e = p.edit()
            for ((old, new) in listOf(
                K_IV to k(K_IV, account), K_CT to k(K_CT, account),
                K_SIV to k(K_SIV, account), K_SCT to k(K_SCT, account),
            )) {
                p.getString(old, null)?.let { e.putString(new, it) }
            }
            e.remove(K_IV).remove(K_CT).remove(K_SIV).remove(K_SCT).commit()
        } catch (_: Exception) {
        }
    }

    /**
     * 清掉老版本写下的会话 Cookie 密文（那时有内嵌网页直达功能，现在已无人读取）。
     * 留着的唯一作用就是多一份落盘的凭据，清掉更干净。启动时调一次，幂等。
     */
    fun purgeSessions(ctx: Context) {
        try {
            val p = sp(ctx)
            val stale = p.all.keys.filter { it.startsWith("$K_SIV@") || it.startsWith("$K_SCT@") }
            if (stale.isEmpty()) return
            val e = p.edit()
            for (k in stale) e.remove(k)
            e.commit()
        } catch (_: Exception) {
        }
    }

    /** 清除全部账号的密码与会话，并删掉密钥库里的密钥（「清除全部账号记忆」用） */
    fun clearAll(ctx: Context) {
        try {
            sp(ctx).edit().clear().commit()
        } catch (_: Exception) {
        }
        try {
            KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }
}
