package io.github.mangi.eta.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 复现 F02：SettingsDataStore.speechSettingsFlow() 是唯一没有 `.catch {}`、decode 也没有
 * runCatching 的偏好流。偏好里 speech_settings_v1 一旦是不兼容/损坏的 JSON，
 * 异常会直接抛给 collector（语音设置页 collectAsState 时崩），而同文件其余 10 个 flow
 * 都回退到默认值。
 *
 * 本测试只读地验证「坏 JSON 时应回退默认值而不是抛异常」，不修改任何生产代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeechSettingsFlowCorruptJsonReproTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsDataStore.init(context)
    }

    @Test
    fun corruptSpeechSettingsJsonFallsBackToDefaults() = runBlocking {
        val key = stringPreferencesKey("speech_settings_v1")
        dataStore().edit { it[key] = """{"autoSpeak":"这不是布尔值"}""" }

        try {
            val settings = SettingsDataStore.speechSettingsFlow().first()
            assertEquals("回退值应是默认 SpeechSettings", false, settings.autoSpeak)
        } catch (failure: Throwable) {
            fail(
                "偏好里的 speech_settings_v1 是不兼容 JSON 时，speechSettingsFlow 应回退默认值，" +
                    "而不是把异常抛给 collector；实际抛出：${failure::class.java.name}: ${failure.message}",
            )
        }
    }

    /** SettingsDataStore.dataStore 是 private 字段，测试里用反射取出以便写入损坏的偏好值。 */
    private fun dataStore(): DataStore<Preferences> {
        val field = SettingsDataStore::class.java.getDeclaredField("dataStore")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(SettingsDataStore) as DataStore<Preferences>
    }
}
