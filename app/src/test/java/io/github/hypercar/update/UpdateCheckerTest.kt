package io.github.hypercar.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun alphaUsesReleaseListWhileStableAndBetaUseLatest() {
        assertTrue(releaseApiUrl("1.2.3-alpha.10").endsWith("/releases?per_page=100"))
        assertTrue(releaseApiUrl("1.2.3").endsWith("/releases/latest"))
        assertTrue(releaseApiUrl("1.2.3-beta.10").endsWith("/releases/latest"))
    }

    @Test
    fun alphaSelectsLargestVersionRatherThanApiOrderOrPublicationDate() {
        val payload = JSONArray()
            .put(release("v1.2.3-alpha.9"))
            .put(release("v1.2.3-alpha.100"))
            .put(release("v1.2.3-alpha.10"))
        assertEquals("1.2.3-alpha.100", parseLatestRelease(payload.toString(), "1.2.3-alpha.1")?.version)
        payload.put(release("v1.3.0-alpha.1"))
        assertEquals("1.3.0-alpha.1", parseLatestRelease(payload.toString(), "1.2.3-alpha.1")?.version)
    }

    @Test
    fun alphaRejectsDraftStableBetaAndMalformedTags() {
        val payload = JSONArray().put(release("v1.2.3-alpha.2"))
            .put(release("v9.0.0-alpha.1", draft = true))
            .put(release("v9.0.0-alpha.1", prerelease = false))
            .put(release("v9.0.0", prerelease = false))
            .put(release("v9.0.0-beta.1"))
        listOf(
            "9.0.0-alpha.1", "V9.0.0-alpha.1", "v9.0-alpha.1", "v9.0.0.1-alpha.1",
            "v9.0.0-alpha", "v9.0.0-alpha.1+build", "v9.0.0-alpha.1-extra",
            "v09.0.0-alpha.1", "v9.0.0-alpha.01", "v9.0.0-alpha.-1",
        ).forEach { payload.put(release(it)) }
        assertEquals("v1.2.3-alpha.2", parseLatestRelease(payload.toString(), "1.2.3-alpha.1")?.tag)
    }

    @Test
    fun alphaDoesNotFallBackToStableWhenNoValidAlphaExists() {
        val payload = JSONArray().put(release("v9.0.0", prerelease = false))
        assertNull(parseLatestRelease(payload.toString(), "1.2.3-alpha.1"))
        assertNull(parseLatestRelease("[]", "1.2.3-alpha.1"))
    }

    @Test
    fun stableParsesReleaseAndApkMetadata() {
        val json = release("v1.2.4", prerelease = false)
            .put("body", " 更新说明 \n")
            .put("html_url", "release-page")
            .put("published_at", "2026-11-27T00:00:00Z")
            .put("assets", JSONArray()
                .put(JSONObject().put("name", "checksums.txt"))
                .put(JSONObject().put("name", "app.APK")
                    .put("browser_download_url", "apk-download").put("size", 1234)))
        val info = parseLatestRelease(json.toString(), "1.2.3")!!
        assertEquals("1.2.4", info.version)
        assertEquals("更新说明", info.notes)
        assertEquals("release-page", info.pageUrl)
        assertEquals("apk-download", info.apkUrl)
        assertEquals(1234L, info.apkSize)
        assertEquals("2026-11-27T00:00:00Z", info.publishedAt)
    }

    @Test
    fun stableNeverAcceptsAnAlphaOrOtherPrereleaseResponse() {
        assertNull(parseLatestRelease(release("v9.0.0-alpha.1").toString(), "1.2.3"))
        assertNull(parseLatestRelease(release("v9.0.0-alpha.1", prerelease = false).toString(), "1.2.3"))
        assertNull(parseLatestRelease(release("v9.0.0-beta.1").toString(), "1.2.3"))
        assertNull(parseLatestRelease(release("v9.0.0", draft = true, prerelease = false).toString(), "1.2.3"))
    }

    @Test
    fun releaseOutranksPrereleasesOfSameCore() {
        assertTrue(compareReleaseVersions("1.2.3", "1.2.3-alpha.999")!! > 0)
        assertTrue(compareReleaseVersions("1.2.3", "1.2.3-beta.999")!! > 0)
        assertTrue(compareReleaseVersions("1.2.3-beta.1", "1.2.3-alpha.999")!! > 0)
        assertFalse(compareReleaseVersions("1.2.3-alpha.999", "1.2.3")!! > 0)
        assertTrue(compareReleaseVersions("1.2.4-alpha.1", "1.2.3")!! > 0)
    }

    @Test
    fun alphaSequenceIsNumericAndDoesNotOverflow() {
        assertTrue(compareReleaseVersions("1.2.3-alpha.10", "1.2.3-alpha.9")!! > 0)
        assertTrue(compareReleaseVersions("1.2.3-alpha.999999999999999999999", "1.2.3-alpha.10")!! > 0)
        assertEquals(0, compareReleaseVersions("1.2.3-alpha.10", "1.2.3-alpha.10"))
    }

    @Test
    fun legacyMultiSegmentVersionsAndMissingZeroSegmentsRemainCompatible() {
        assertTrue(compareReleaseVersions("0.1.0.42", "0.1.0")!! > 0)
        assertTrue(compareReleaseVersions("0.1.0.100", "0.1.0.42")!! > 0)
        assertEquals(0, compareReleaseVersions("0.1.0.0", "0.1.0"))
        assertEquals(0, compareReleaseVersions("v1.2.3+build.7", "1.2.3+build.8"))
        assertTrue(compareReleaseVersions("2.0.0", "1.99.99.999")!! > 0)
    }

    @Test
    fun invalidVersionsDoNotBecomeUpdates() {
        assertNull(parseReleaseVersion("garbage"))
        assertNull(parseReleaseVersion("1.2.3-alpha."))
        assertNull(compareReleaseVersions("invalid", "1.2.3"))
    }

    @Test
    fun alphaSkipsMissingOrIncompleteApksAndSelectsUploadedAsset() {
        val usable = release("v1.2.3-alpha.2")
        val missingRelease = release("v1.2.3-alpha.3").apply { remove("assets") }
        val uploading = release("v1.2.3-alpha.4").apply {
            getJSONArray("assets").getJSONObject(0).put("state", "new")
        }
        val blankUrl = release("v1.2.3-alpha.5").apply {
            getJSONArray("assets").getJSONObject(0).put("browser_download_url", " ")
        }
        val payload = JSONArray().put(usable).put(missingRelease).put(uploading).put(blankUrl)
        assertEquals("v1.2.3-alpha.2", parseLatestRelease(payload.toString(), "1.2.3-alpha.1")?.tag)
        assertNull(parseLatestRelease(JSONArray().put(missingRelease).toString(), "1.2.3-alpha.1"))

        val mixedAssets = release("v1.2.3-alpha.6").apply {
            getJSONArray("assets").put(0, JSONObject().put("name", "incomplete.apk").put("state", "new"))
                .put(JSONObject().put("name", "ready.apk").put("state", "uploaded")
                    .put("browser_download_url", "ready-download"))
        }
        assertEquals("ready-download", parseLatestRelease(JSONArray().put(mixedAssets).toString(), "1.2.3-alpha.1")?.apkUrl)
    }

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = true) =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("assets", JSONArray().put(JSONObject().put("name", "app.apk")
                .put("state", "uploaded").put("browser_download_url", "apk-download")))
}
