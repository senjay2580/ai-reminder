package com.senjay.reminder

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private val apk = "https://github.com/senjay2580/ai-reminder/releases/download/v1.5.1/AiReminder-v1.5.1.apk"

    @Test
    fun parsesReleaseManifestWithMultilineChineseNotes() {
        val notes = "- 修复更新检查 403\n- 通知信箱中的\"删除\"功能"
        val text = JSONObject().put("version", "1.5.1").put("notes", notes).put("apkUrl", apk).toString()
        assertEquals(Updater.Release("1.5.1", notes, apk), Updater.parseRelease(text))
    }

    @Test
    fun acceptsTagPrefixAndOptionalNotes() {
        val text = JSONObject().put("version", "v1.5.1").put("apkUrl", apk)
        assertEquals(Updater.Release("1.5.1", "", apk), Updater.parseRelease(text.toString()))
        text.put("notes", JSONObject.NULL)
        assertEquals("", Updater.parseRelease(text.toString()).notes)
    }

    @Test
    fun missingApkFailsInsteadOfReportingCurrentVersion() {
        assertThrows(JSONException::class.java) { Updater.parseRelease("""{"version":"1.5.1"}""") }
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("1.5.1", "1.5.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertFalse(Updater.isNewer("1.5.1", "1.5.1"))
        assertFalse(Updater.isNewer("1.5.0", "1.5.1"))
    }
}
