package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ForgeCredentials
import io.github.mangi.eta.data.model.ForgeSettings
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Forge（opencode）配置的持久化入口。
 *
 * 非敏感字段（中转站地址、模型 ID）进 DataStore；API Key 复用 App 现有密钥方案：
 * Android Keystore 里的 AES/GCM 密钥加密后以 Base64 存 SharedPreferences，
 * 与网页工具凭据（[WebToolsSettingsRepository]）同一套做法，不存明文。
 */
internal object ForgeSettingsRepository {
    private const val PREFS_NAME = "eta_forge_secrets"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEYSTORE_ALIAS = "eta_forge_credentials_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun settingsFlow(): Flow<ForgeSettings> = SettingsDataStore.forgeSettingsFlow()

    suspend fun settings(): ForgeSettings = SettingsDataStore.forgeSettings()

    suspend fun save(settings: ForgeSettings) = SettingsDataStore.setForgeSettings(settings)

    suspend fun credentials(context: Context): ForgeCredentials = withContext(Dispatchers.IO) {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CREDENTIALS, null) ?: return@withContext ForgeCredentials()
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            json.decodeFromString<ForgeCredentials>(
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            throw ForgeCredentialsUnavailable()
        }
    }

    suspend fun saveCredentials(context: Context, credentials: ForgeCredentials) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(credentials).toByteArray(Charsets.UTF_8))
        check(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CREDENTIALS, Base64.getEncoder().encodeToString(encrypted))
                .commit(),
        ) {
            "Forge 配置保存失败"
        }
    }

    /** 三项齐全才算配置完成；Forge 门禁用它在进入对话前给出缺项提示。 */
    fun isConfigured(settings: ForgeSettings, credentials: ForgeCredentials): Boolean =
        settings.baseUrl.isNotBlank() && settings.modelId.isNotBlank() && credentials.apiKey.isNotBlank()

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}

internal class ForgeCredentialsUnavailable : Exception("Forge 配置无法解密，请重新填写并保存")
