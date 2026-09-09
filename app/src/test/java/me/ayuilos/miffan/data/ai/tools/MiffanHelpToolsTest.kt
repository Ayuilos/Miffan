package me.ayuilos.miffan.data.ai.tools

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MiffanHelpToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `loads matching topic without sending user query to website`() = runBlocking {
        val document = "# 模型服务商\n配置步骤"
        val manifest = manifest(document)
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = clientWithInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            when (chain.request().url.encodedPath) {
                "/skills/miffan-help/manifest.json" -> response(chain, manifest, "application/json")
                "/skills/miffan-help/1.0.0/zh-CN/providers.md" -> response(chain, document, "text/markdown")
                else -> response(chain, "missing", "text/plain", 404)
            }
        }

        val result = client.load(
            topicQuery = "OpenAI 模型配置 secret-user-words",
            requestedLocale = "zh-Hans-CN",
            appVersion = "3.3.0-beta.1",
        ) as MiffanHelpLoadResult.Document

        assertEquals("providers", result.topic.id)
        assertEquals("zh-CN", result.locale)
        assertEquals(document, result.content)
        assertFalse(result.stale)
        assertEquals(
            listOf(
                "https://miffan.ayuilos.me/skills/miffan-help/manifest.json",
                "https://miffan.ayuilos.me/skills/miffan-help/1.0.0/zh-CN/providers.md",
            ),
            requestedUrls,
        )
        assertTrue(requestedUrls.none { "secret-user-words" in it })
    }

    @Test
    fun `uses verified stale cache when network is unavailable`() = runBlocking {
        val document = "# 离线帮助"
        val manifest = manifest(document)
        val online = AtomicBoolean(true)
        val now = AtomicLong(1_000L)
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                if (!online.get()) throw IOException("offline")
                when (chain.request().url.encodedPath) {
                    "/skills/miffan-help/manifest.json" -> response(chain, manifest, "application/json")
                    "/skills/miffan-help/1.0.0/zh-CN/providers.md" -> response(chain, document, "text/markdown")
                    else -> response(chain, "missing", "text/plain", 404)
                }
            })
            .build()
        val cacheFile = tempFolder.newFile("stale-cache.json")
        val client = MiffanHelpClient(
            httpClient = httpClient,
            cacheFile = cacheFile,
            nowMillis = now::get,
            manifestCacheTtlMs = 10,
            topicCacheTtlMs = 10,
        )

        assertFalse((client.load("providers", "zh-CN", "3.3.0") as MiffanHelpLoadResult.Document).stale)
        now.set(2_000L)
        online.set(false)

        val offline = client.load("providers", "zh-CN", "3.3.0") as MiffanHelpLoadResult.Document
        assertEquals(document, offline.content)
        assertTrue(offline.stale)
    }

    @Test
    fun `invalid manifest does not replace valid stale cache`() = runBlocking {
        val document = "# cached help"
        val validManifest = manifest(document)
        val mode = AtomicLong(0)
        val now = AtomicLong(1_000L)
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                when (mode.get()) {
                    2L -> throw IOException("offline")
                    1L -> if (chain.request().url.encodedPath.endsWith("manifest.json")) {
                        response(chain, "{invalid-json", "application/json")
                    } else {
                        response(chain, document, "text/markdown")
                    }
                    else -> if (chain.request().url.encodedPath.endsWith("manifest.json")) {
                        response(chain, validManifest, "application/json")
                    } else {
                        response(chain, document, "text/markdown")
                    }
                }
            })
            .build()
        val client = MiffanHelpClient(
            httpClient = httpClient,
            cacheFile = tempFolder.newFile("manifest-cache.json"),
            nowMillis = now::get,
            manifestCacheTtlMs = 10,
            topicCacheTtlMs = 10,
        )

        assertTrue(client.load("providers", "zh-CN", "3.3.0") is MiffanHelpLoadResult.Document)
        now.set(2_000L)
        mode.set(1)
        assertTrue((client.load("providers", "zh-CN", "3.3.0") as MiffanHelpLoadResult.Document).stale)
        now.set(3_000L)
        mode.set(2)

        val offline = client.load("providers", "zh-CN", "3.3.0")
        assertTrue(offline is MiffanHelpLoadResult.Document)
        assertEquals(document, (offline as MiffanHelpLoadResult.Document).content)
        assertTrue(offline.stale)
    }

    @Test
    fun `disk cache retains manifest and only the most recent topic resources`() = runBlocking {
        val topics = (0 until 40).map { index ->
            val id = "topic-$index"
            MiffanHelpTopic(
                id = id,
                title = id,
                path = "/skills/miffan-help/1.0.0/{locale}/$id.md",
                sha256 = mapOf("zh-CN" to digest(id)),
            )
        }
        val manifest = json.encodeToString(
            MiffanHelpManifest(
                schemaVersion = 1,
                skillVersion = "1.0.0",
                defaultLocale = "zh-CN",
                locales = listOf("zh-CN"),
                topics = topics,
            )
        )
        val online = AtomicBoolean(true)
        val now = AtomicLong(1_000L)
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                if (!online.get()) throw IOException("offline")
                if (chain.request().url.encodedPath.endsWith("manifest.json")) {
                    response(chain, manifest, "application/json")
                } else {
                    val id = chain.request().url.pathSegments.last().removeSuffix(".md")
                    response(chain, id, "text/markdown")
                }
            })
            .build()
        val client = MiffanHelpClient(
            httpClient = httpClient,
            cacheFile = tempFolder.newFile("bounded-cache.json"),
            nowMillis = now::get,
            manifestCacheTtlMs = 1_000_000,
            topicCacheTtlMs = 1_000_000,
        )
        topics.forEach { topic ->
            assertTrue(client.load(topic.id, "zh-CN", "3.3.0") is MiffanHelpLoadResult.Document)
            now.incrementAndGet()
        }
        online.set(false)

        assertTrue(client.load("topic-39", "zh-CN", "3.3.0") is MiffanHelpLoadResult.Document)
        assertTrue(client.load("topic-0", "zh-CN", "3.3.0") is MiffanHelpLoadResult.Unavailable)
    }

    @Test
    fun `rejects document when sha256 does not match`() = runBlocking {
        val manifest = manifest("expected")
        val client = clientWithInterceptor { chain ->
            when (chain.request().url.encodedPath) {
                "/skills/miffan-help/manifest.json" -> response(chain, manifest, "application/json")
                else -> response(chain, "tampered", "text/markdown")
            }
        }

        val result = client.load("providers", "zh-CN", "3.3.0")

        assertTrue(result is MiffanHelpLoadResult.Unavailable)
        assertTrue((result as MiffanHelpLoadResult.Unavailable).reason.contains("integrity"))
    }

    @Test
    fun `does not follow redirect outside official origin`() = runBlocking {
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = clientWithInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "https://example.com/stolen.json")
                .body("".toResponseBody())
                .build()
        }

        val result = client.load("providers", "zh-CN", "3.3.0")

        assertTrue(result is MiffanHelpLoadResult.Unavailable)
        assertEquals(
            listOf("https://miffan.ayuilos.me/skills/miffan-help/manifest.json"),
            requestedUrls,
        )
    }

    @Test
    fun `rejects unknown-length response above byte limit`() = runBlocking {
        val oversized = "x".repeat(256 * 1_024 + 1)
        val client = clientWithInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(unknownLengthBody(oversized))
                .build()
        }

        val result = client.load("providers", "zh-CN", "3.3.0")

        assertTrue(result is MiffanHelpLoadResult.Unavailable)
        assertTrue((result as MiffanHelpLoadResult.Unavailable).reason.contains("byte limit"))
    }

    @Test
    fun `returns local topic index when nothing matches`() = runBlocking {
        val manifest = manifest("unused")
        val requestedPaths = CopyOnWriteArrayList<String>()
        val client = clientWithInterceptor { chain ->
            requestedPaths += chain.request().url.encodedPath
            response(chain, manifest, "application/json")
        }

        val result = client.load("completely unrelated", "en-US", "3.3.0") as MiffanHelpLoadResult.TopicIndex

        assertEquals("zh-CN", result.locale)
        assertEquals(listOf("providers"), result.topics.map { it.id })
        assertEquals(listOf("/skills/miffan-help/manifest.json"), requestedPaths)
    }

    @Test
    fun `locale selection prefers exact language then default`() {
        val locales = listOf("zh-CN", "en-US", "en-GB")

        assertEquals("en-GB", chooseLocale("en-GB", locales, "zh-CN"))
        assertEquals("en-US", chooseLocale("en-AU", locales, "zh-CN"))
        assertEquals("zh-CN", chooseLocale("fr-FR", locales, "zh-CN"))
    }

    @Test
    fun `exact app compatibility supports release and corresponding nightly`() {
        assertEquals(
            AppVersionCompatibility.COMPATIBLE,
            evaluateAppVersionCompatibility("=3.3.0-beta.1", "3.3.0-beta.1"),
        )
        assertEquals(
            AppVersionCompatibility.COMPATIBLE,
            evaluateAppVersionCompatibility("=3.3.0-beta.1", "3.3.0-beta.1-nightly"),
        )
        assertEquals(
            AppVersionCompatibility.INCOMPATIBLE,
            evaluateAppVersionCompatibility("=3.3.0-beta.1", "3.3.0-beta.2"),
        )
        assertEquals(
            AppVersionCompatibility.UNKNOWN,
            evaluateAppVersionCompatibility(">=3.3.0", "3.3.0"),
        )
        assertEquals(
            AppVersionCompatibility.UNKNOWN,
            evaluateAppVersionCompatibility("", "3.3.0"),
        )
    }

    @Test
    fun `topic index is capped at fifty entries`() = runBlocking {
        val topics = (0 until 60).map { index -> topic("topic-$index", "Topic $index", emptyList()) }
        val manifest = json.encodeToString(
            MiffanHelpManifest(
                schemaVersion = 1,
                skillVersion = "1.0.0",
                defaultLocale = "zh-CN",
                locales = listOf("zh-CN"),
                appVersionRange = "=3.3.0",
                topics = topics,
            )
        )
        val client = clientWithInterceptor { chain -> response(chain, manifest, "application/json") }

        val result = client.load("no matching topic", "zh-CN", "3.3.0") as MiffanHelpLoadResult.TopicIndex

        assertEquals(50, result.topics.size)
        assertEquals(AppVersionCompatibility.COMPATIBLE, result.compatibility)
    }

    @Test
    fun `cache write failure does not discard verified network document`() = runBlocking {
        val document = "# verified online help"
        val manifest = manifest(document)
        val unwritableCacheTarget = tempFolder.newFolder("non-empty-cache-directory").also {
            it.resolve("blocker").writeText("keep directory non-empty")
        }
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("manifest.json")) {
                    response(chain, manifest, "application/json")
                } else {
                    response(chain, document, "text/markdown")
                }
            })
            .build()
        val client = MiffanHelpClient(httpClient = httpClient, cacheFile = unwritableCacheTarget)

        val result = client.load("providers", "zh-CN", "3.3.0")

        assertTrue(result is MiffanHelpLoadResult.Document)
        assertEquals(document, (result as MiffanHelpLoadResult.Document).content)
    }

    @Test
    fun `topic routing favors specific phrases over generic settings words`() {
        val topics = listOf(
            topic("getting-started", "开始使用 Miffan", listOf("开始", "设置", "配置", "聊天")),
            topic("providers-models", "提供商与模型", listOf("模型", "API Key", "API", "OpenRouter")),
            topic("assistants-conversations", "助手与对话", listOf("助手", "系统提示词", "对话", "分支")),
            topic("search", "联网搜索与消息搜索", listOf("搜索", "模型搜索", "联网")),
            topic("data-backup", "数据、备份与恢复", listOf("备份", "API Key", "恢复", "导出备份")),
            topic("conversation-export", "导出聊天与对话", listOf("导出聊天", "导出对话", "分支")),
            topic("preferences-network", "网络与代理设置", listOf("代理", "网络设置")),
        )

        assertEquals(null, findTopic("API Key", topics))
        assertEquals(null, findTopic("分支", topics))
        assertEquals("assistants-conversations", findTopic("怎么设置系统提示词", topics)?.id)
        assertEquals("data-backup", findTopic("备份是否含 API Key", topics)?.id)
        assertEquals("providers-models", findTopic("添加 OpenRouter", topics)?.id)
        assertEquals("preferences-network", findTopic("配置代理", topics)?.id)
        assertEquals("conversation-export", findTopic("怎么导出聊天", topics)?.id)
        assertEquals("data-backup", findTopic("怎么导出备份", topics)?.id)
        assertEquals("search", findTopic("模型搜索", topics)?.id)
        assertEquals("providers-models", findTopic("providers-models", topics)?.id)
    }

    @Test
    fun `built-in skill routes product questions through help tool`() {
        assertEquals("miffan-help", miffanHelpBuiltInSkill.name)
        assertTrue(miffanHelpBuiltInSkill.body.contains("`miffan_help`"))
        assertTrue(miffanHelpBuiltInSkill.bundledFiles.isEmpty())
    }

    private fun clientWithInterceptor(intercept: (Interceptor.Chain) -> Response): MiffanHelpClient {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor(intercept))
            .build()
        return MiffanHelpClient(
            httpClient = httpClient,
            cacheFile = tempFolder.newFile(),
        )
    }

    private fun manifest(document: String): String = json.encodeToString(
        MiffanHelpManifest(
            schemaVersion = 1,
            skillVersion = "1.0.0",
            defaultLocale = "zh-CN",
            locales = listOf("zh-CN"),
            appVersionRange = ">=3.3.0",
            topics = listOf(
                MiffanHelpTopic(
                    id = "providers",
                    title = "模型服务商",
                    description = "配置模型与 API 服务商",
                    keywords = listOf("模型配置", "OpenAI", "provider"),
                    path = "/skills/miffan-help/1.0.0/{locale}/providers.md",
                    sha256 = mapOf("zh-CN" to digest(document)),
                )
            ),
        )
    )

    private fun topic(id: String, title: String, keywords: List<String>) = MiffanHelpTopic(
        id = id,
        title = title,
        keywords = keywords,
        path = "/skills/miffan-help/1.0.0/{locale}/$id.md",
        sha256 = mapOf("zh-CN" to "0".repeat(64)),
    )

    private fun response(
        chain: Interceptor.Chain,
        body: String,
        mediaType: String,
        code: Int = 200,
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Not Found")
        .body(body.toResponseBody(mediaType.toMediaType()))
        .build()

    private fun unknownLengthBody(content: String): ResponseBody = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8(content)

        override fun contentType() = "application/json".toMediaType()

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = buffer
    }

    private fun digest(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
