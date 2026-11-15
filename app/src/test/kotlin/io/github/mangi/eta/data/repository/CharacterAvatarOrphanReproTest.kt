package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 复现 F03：CharacterRepository.save(profile, avatarBytes) 用 writeAvatar(uniqueName = true)
 * 写一个**新**头像文件并只更新 avatarPath，全文件没有任何删除旧头像的代码，
 * 于是每次「换头像」都会在 filesDir/roleplay/<角色 id>/ 下留下一个孤儿 PNG。
 *
 * 本测试只读地验证「换头像后目录里只应剩 1 个头像文件」，不修改任何生产代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CharacterAvatarOrphanReproTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("eta.db")
        CharacterRepository.initialize(context)
    }

    @Test
    fun replacingAvatarLeavesSingleAvatarFile() = runBlocking {
        val card = CharacterCard(buildJsonObject { put("name", "审计用测试角色") })
        val created = CharacterRepository.create(card, pngBytes(0xFF112233.toInt()))
        CharacterRepository.save(created, pngBytes(0xFF445566.toInt()))

        val directory = File(context.filesDir, "roleplay/${created.id}")
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        assertEquals(
            "换头像后旧头像文件应被清理，目录里应只剩 1 个文件，实际为：${files.map { it.name }}",
            1,
            files.size,
        )
    }

    private fun pngBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
