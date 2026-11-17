package org.samesame.notes.feature.settings.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

const val RELEASES_PAGE = "https://github.com/Rishi-Bhati/samesame_notes/releases/latest"
private const val REPO_PREFIX = "https://github.com/Rishi-Bhati/samesame_notes/"
private const val API_URL = "https://api.github.com/repos/Rishi-Bhati/samesame_notes/releases/latest"
private const val MAX_BODY = 1 shl 20

/** A version such as `0.3.0-alpha`; compares by core, then pre-release label (alpha < beta < rc < stable), then its number. */
class ReleaseVersion private constructor(
    private val major: Int, private val minor: Int, private val patch: Int,
    private val rank: Int, private val number: Int,
) : Comparable<ReleaseVersion> {

    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.rank }, { it.number })

    override fun equals(other: Any?) = other is ReleaseVersion && compareTo(other) == 0
    override fun hashCode() = listOf(major, minor, patch, rank, number).hashCode()

    companion object {
        private val pattern = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(\d*))?$""")

        fun parse(raw: String): ReleaseVersion? {
            var s = raw.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            s = s.substringBefore('+')
            val m = pattern.matchEntire(s) ?: return null
            val (ma, mi, pa, label, num) = m.destructured
            val rank = when (label.lowercase()) {
                "" -> 3
                "alpha" -> 0
                "beta" -> 1
                "rc" -> 2
                else -> return null
            }
            return runCatching {
                ReleaseVersion(ma.toInt(), mi.toInt(), pa.toInt(), rank, if (num.isEmpty()) 0 else num.toInt())
            }.getOrNull()
        }
    }
}

data class LatestRelease(val tag: String, val version: ReleaseVersion, val url: String) {
    /** The tag without a leading v, for display. */
    val displayVersion: String get() = tag.trim().removePrefix("v").removePrefix("V")
}

/** Reads the fields of GitHub's "latest release" payload. Null when it is unusable. */
fun parseLatestRelease(json: String): LatestRelease? {
    val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun flag(key: String) = (obj[key] as? JsonPrimitive)?.booleanOrNull
    if (flag("draft") == true) return null
    val tag = str("tag_name") ?: return null
    val version = ReleaseVersion.parse(tag) ?: return null
    val url = str("html_url")?.takeIf { it.startsWith(REPO_PREFIX) } ?: RELEASES_PAGE
    return LatestRelease(tag, version, url)
}

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val release: LatestRelease) : UpdateResult
    data object Failed : UpdateResult
    data object RateLimited : UpdateResult
}

/** One HTTPS request per call; only ever invoked from a user tap. Blocking: call off the main thread. */
class UpdateChecker(private val installedVersionName: String) {
    fun check(): UpdateResult {
        val installed = ReleaseVersion.parse(installedVersionName) ?: return UpdateResult.Failed
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "SameSame-notes/$installedVersionName")
            }
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.use { readCapped(it) }
                    val latest = parseLatestRelease(body) ?: return UpdateResult.Failed
                    if (latest.version > installed) UpdateResult.Available(latest) else UpdateResult.UpToDate
                }
                403, 429 -> UpdateResult.RateLimited
                else -> UpdateResult.Failed
            }
        } catch (_: Exception) {
            UpdateResult.Failed
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCapped(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX_BODY) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_BODY - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
