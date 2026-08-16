package me.rerere.rikkahub.data.skills.source

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The endpoint used by the official skills CLI is intentionally treated as unstable: unlike the
 * documented `/api/v1` API, `/api/search` currently requires no Vercel OIDC credential but has no
 * published compatibility contract. A failure therefore degrades to an unavailable result rather
 * than breaking chat or installation from an explicit canonical URL.
 */
class SkillShCatalogClient(
    httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(SEARCH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): SkillShCatalogSearchResult = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length !in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH ||
            normalizedQuery.any(Char::isISOControl)
        ) {
            return@withContext SkillShCatalogSearchResult.unavailable(
                reason = "Search query must contain $MIN_QUERY_LENGTH-$MAX_QUERY_LENGTH safe characters",
            )
        }

        try {
            val url = CATALOG_BASE_URL.toHttpUrl().newBuilder()
                .addPathSegments("api/search")
                .addQueryParameter("q", normalizedQuery)
                .addQueryParameter("limit", MAX_RESULTS.toString())
                .build()
            val request = Request.Builder()
                .get()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).await().use { response ->
                requireVerifiedResponse(response.request.url.host, CATALOG_HOST, response.code)
                if (!response.isSuccessful) {
                    throw IOException("skills.sh search returned HTTP ${response.code}")
                }
                val body = decodeCatalogUtf8(
                    response.body.byteStream().readBounded(MAX_RESPONSE_BYTES),
                )
                val payload = json.decodeFromString<CatalogApiResponse>(body)
                val entries = payload.skills.asSequence()
                    .mapNotNull(::toCatalogEntry)
                    .distinctBy { it.catalogId }
                    .take(MAX_RESULTS)
                    .toList()
                SkillShCatalogSearchResult(entries = entries)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SkillShCatalogSearchResult.unavailable(
                reason = "skills.sh search is temporarily unavailable",
            )
        }
    }

    private fun toCatalogEntry(skill: CatalogApiSkill): SkillShCatalogEntry? {
        val sourceMatch = GITHUB_SOURCE.matchEntire(skill.source.trim()) ?: return null
        val owner = sourceMatch.groupValues[1]
        val repo = sourceMatch.groupValues[2]
        if (owner.contains("--") || repo == "." || repo == ".." || repo.endsWith(".git")) return null
        val rawId = skill.id.trim().lowercase()
        // The CLI's current endpoint has returned a bare slug, while newer catalog responses use
        // the stable owner/repo/slug id. Accept only those two unambiguous shapes.
        val slug = when {
            SKILL_SLUG.matches(rawId) -> rawId
            rawId == "${owner.lowercase()}/${repo.lowercase()}/${rawId.substringAfterLast('/')}" -> {
                rawId.substringAfterLast('/').takeIf(SKILL_SLUG::matches) ?: return null
            }
            else -> return null
        }

        return SkillShCatalogEntry(
            catalogId = "$owner/$repo/$slug",
            source = "$owner/$repo",
            slug = slug,
            installs = skill.installs.coerceAtLeast(0),
            pageUrl = "https://$CATALOG_HOST/$owner/$repo/$slug",
        )
    }

    private fun requireVerifiedResponse(requestHost: String, expectedHost: String, statusCode: Int) {
        if (!requestHost.equals(expectedHost, ignoreCase = true)) {
            throw IOException("Unexpected response host")
        }
        if (statusCode in 300..399) {
            throw IOException("Redirects are not allowed")
        }
    }

    private fun decodeCatalogUtf8(bytes: ByteArray): String {
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        if ('\u0000' in decoded) throw IOException("Catalog response contains invalid text")
        return decoded
    }

    companion object {
        const val MAX_RESULTS = 10
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 100
        const val CATALOG_API_STABILITY = "unstable"

        private const val CATALOG_BASE_URL = "https://skills.sh/"
        private const val CATALOG_HOST = "skills.sh"
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val SEARCH_CONNECT_TIMEOUT_SECONDS = 8L
        private const val SEARCH_TIMEOUT_SECONDS = 15L
        private val GITHUB_SOURCE = Regex(
            "([A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?)/([A-Za-z0-9._-]{1,100})",
        )
        private val SKILL_SLUG = Regex("[a-z0-9][a-z0-9-]{0,127}")
    }
}

data class SkillShCatalogSearchResult(
    val entries: List<SkillShCatalogEntry>,
    val available: Boolean = true,
    val stability: String = SkillShCatalogClient.CATALOG_API_STABILITY,
    val unavailableReason: String? = null,
) {
    companion object {
        fun unavailable(reason: String) = SkillShCatalogSearchResult(
            entries = emptyList(),
            available = false,
            unavailableReason = reason,
        )
    }
}

data class SkillShCatalogEntry(
    val catalogId: String,
    val source: String,
    val slug: String,
    val installs: Long,
    val pageUrl: String,
)

@Serializable
private data class CatalogApiResponse(
    val skills: List<CatalogApiSkill> = emptyList(),
)

@Serializable
private data class CatalogApiSkill(
    val id: String = "",
    val name: String = "",
    val installs: Long = 0,
    val source: String = "",
)

private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Response exceeds $maxBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
