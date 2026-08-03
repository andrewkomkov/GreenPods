package io.github.andrewkomkov.greenpods.core.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Result of asking GitHub whether a newer release exists. */
sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus

    data class Available(
        val release: ReleaseInfo,
    ) : UpdateStatus

    data class Failed(
        val reason: String,
    ) : UpdateStatus
}

data class ReleaseInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String?,
    val htmlUrl: String,
)

/**
 * Where update information comes from.
 *
 * An interface so screens can be tested against each outcome — current, newer, failed —
 * without a network or a mock web server standing in for one.
 */
fun interface UpdateSource {
    suspend fun check(): UpdateStatus
}

/**
 * In-app update check against GitHub Releases.
 *
 * GreenPods is distributed as an APK from GitHub rather than through Play, so it
 * has to tell users about new versions itself. The check is deliberately read-only:
 * it never downloads or installs anything silently, it just reports what is
 * available and hands off to the system installer if the user opts in.
 *
 * The unauthenticated GitHub API allows 60 requests per hour per IP, which is far
 * more than a once-a-day check needs.
 */
class UpdateChecker(
    private val currentVersionName: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val releasesUrl: String = DEFAULT_RELEASES_URL,
) : UpdateSource {
    override suspend fun check(): UpdateStatus =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(releasesUrl)
                        .header("Accept", "application/vnd.github+json")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UpdateStatus.Failed("GitHub returned ${response.code}")
                    }
                    val body =
                        response.body?.string()
                            ?: return@withContext UpdateStatus.Failed("Empty response")

                    val release = parseRelease(JSONObject(body))
                    if (isNewerThanCurrent(release.versionName)) {
                        UpdateStatus.Available(release)
                    } else {
                        UpdateStatus.UpToDate
                    }
                }
            } catch (e: IOException) {
                UpdateStatus.Failed(e.message ?: "Network error")
            } catch (e: org.json.JSONException) {
                UpdateStatus.Failed("Malformed release payload")
            }
        }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val assets = json.optJSONArray("assets")
        val apkUrl =
            (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")

        return ReleaseInfo(
            versionName = json.optString("tag_name").removePrefix("v"),
            notes = json.optString("body"),
            apkUrl = apkUrl?.takeIf(String::isNotBlank),
            htmlUrl = json.optString("html_url"),
        )
    }

    internal fun isNewerThanCurrent(candidate: String): Boolean = compareVersions(candidate, currentVersionName) > 0

    companion object {
        const val DEFAULT_RELEASES_URL =
            "https://api.github.com/repos/andrewkomkov/GreenPods/releases/latest"

        /**
         * Compares dotted version strings numerically, so 0.10.0 correctly sorts
         * above 0.9.0 — which a string comparison gets wrong. Any suffix after the
         * numeric part (`-rc1`, `-debug`) is ignored, so pre-releases of the same
         * version never look like upgrades.
         */
        internal fun compareVersions(
            left: String,
            right: String,
        ): Int {
            fun parts(version: String) =
                version
                    .substringBefore('-')
                    .split('.')
                    .map { it.toIntOrNull() ?: 0 }

            val a = parts(left)
            val b = parts(right)
            for (index in 0 until maxOf(a.size, b.size)) {
                val diff = (a.getOrElse(index) { 0 }) - (b.getOrElse(index) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
