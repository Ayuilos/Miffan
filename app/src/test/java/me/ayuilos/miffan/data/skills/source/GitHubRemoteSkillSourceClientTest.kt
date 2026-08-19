package me.ayuilos.miffan.data.skills.source

import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.skills.install.SkillInstallErrorCode
import me.ayuilos.miffan.data.skills.install.SkillInstallException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRemoteSkillSourceClientTest {
    @Test
    fun `fetch pins default branch and downloads one validated skill subtree`() = runBlocking {
        val sourceUrl = "https://skills.sh/acme/skills/demo"
        val requests = mutableListOf<String>()
        val client = GitHubRemoteSkillSourceClient(
            fakeClient { request ->
                requests += request.url.toString()
                when {
                    request.url.host == "api.github.com" &&
                        request.url.encodedPath == "/repos/acme/skills" -> {
                        jsonResponse(request, """{"default_branch":"main"}""")
                    }
                    request.url.host == "api.github.com" &&
                        request.url.encodedPath == "/repos/acme/skills/commits/main" -> {
                        jsonResponse(
                            request,
                            """{"sha":"$COMMIT_SHA","commit":{"tree":{"sha":"$TREE_SHA"}}}""",
                        )
                    }
                    request.url.host == "api.github.com" &&
                        request.url.encodedPath == "/repos/acme/skills/git/trees/$TREE_SHA" -> {
                        jsonResponse(request, validTree())
                    }
                    request.url.host == "raw.githubusercontent.com" &&
                        request.url.encodedPath == "/acme/skills/$COMMIT_SHA/skills/demo/SKILL.md" -> {
                        textResponse(request, VALID_SKILL)
                    }
                    request.url.host == "raw.githubusercontent.com" &&
                        request.url.encodedPath == "/acme/skills/$COMMIT_SHA/skills/demo/scripts/check.sh" -> {
                        textResponse(request, "echo safe")
                    }
                    else -> error("Unexpected request: ${request.url}")
                }
            },
        )

        val result = client.fetch(sourceUrl)

        assertEquals(sourceUrl, result.source.requestedUrl)
        assertEquals("github", result.source.provider)
        assertEquals(COMMIT_SHA, result.source.revision)
        assertEquals(
            "https://github.com/acme/skills/tree/$COMMIT_SHA/skills/demo",
            result.source.canonicalUrl,
        )
        assertEquals(listOf("SKILL.md", "scripts/check.sh"), result.files.map { it.relativePath })
        assertEquals(1, requests.count { it.endsWith("/skills/demo/SKILL.md") })
        assertTrue(requests.filter { it.contains("raw.githubusercontent.com") }.all { COMMIT_SHA in it })
    }

    @Test
    fun `fetch rejects noncanonical skills sh URLs before network access`() {
        val client = GitHubRemoteSkillSourceClient(
            fakeClient { error("network must not be used") },
        )

        val error = assertThrows(SkillInstallException::class.java) {
            runBlocking { client.fetch("https://skills.sh.evil.example/acme/skills/demo") }
        }

        assertEquals(SkillInstallErrorCode.INVALID_SOURCE, error.code)
    }

    @Test
    fun `fetch rejects truncated trees and symbolic links`() {
        val truncatedClient = GitHubRemoteSkillSourceClient(
            githubFixtureClient(treeBody = """{"truncated":true,"tree":[]}"""),
        )
        val symlinkClient = GitHubRemoteSkillSourceClient(
            githubFixtureClient(
                treeBody = validTree(
                    extraEntry = """,{"path":"skills/demo/link","mode":"120000","type":"blob","sha":"$FILE_SHA","size":6}""",
                ),
            ),
        )

        val truncated = assertThrows(SkillInstallException::class.java) {
            runBlocking { truncatedClient.fetch("https://skills.sh/acme/skills/demo") }
        }
        val symlink = assertThrows(SkillInstallException::class.java) {
            runBlocking { symlinkClient.fetch("https://skills.sh/acme/skills/demo") }
        }

        assertEquals(SkillInstallErrorCode.DOWNLOAD_FAILED, truncated.code)
        assertEquals(SkillInstallErrorCode.UNSUPPORTED_ENTRY, symlink.code)
    }

    @Test
    fun `fetch rejects non UTF8 supporting files`() {
        val client = GitHubRemoteSkillSourceClient(
            githubFixtureClient(scriptBody = byteArrayOf(0xC3.toByte(), 0x28)),
        )

        val error = assertThrows(SkillInstallException::class.java) {
            runBlocking { client.fetch("https://skills.sh/acme/skills/demo") }
        }

        assertEquals(SkillInstallErrorCode.INVALID_SKILL_FILE, error.code)
    }

    @Test
    fun `fetch rejects NUL text and ambiguous root skill repositories`() = runBlocking {
        val nulClient = GitHubRemoteSkillSourceClient(
            githubFixtureClient(scriptBody = "echo\u0000unsafe".toByteArray()),
        )
        val nulError = assertThrows(SkillInstallException::class.java) {
            runBlocking { nulClient.fetch("https://skills.sh/acme/skills/demo") }
        }
        assertEquals(SkillInstallErrorCode.INVALID_SKILL_FILE, nulError.code)

        val rootClient = GitHubRemoteSkillSourceClient(
            fakeClient { request ->
                when {
                    request.url.encodedPath == "/repos/acme/skills" -> {
                        jsonResponse(request, """{"default_branch":"main"}""")
                    }
                    request.url.encodedPath == "/repos/acme/skills/commits/main" -> {
                        jsonResponse(
                            request,
                            """{"sha":"$COMMIT_SHA","commit":{"tree":{"sha":"$TREE_SHA"}}}""",
                        )
                    }
                    request.url.encodedPath == "/repos/acme/skills/git/trees/$TREE_SHA" -> {
                        jsonResponse(
                            request,
                            """
                                {"truncated":false,"tree":[
                                  {"path":"SKILL.md","mode":"100644","type":"blob","sha":"$FILE_SHA","size":80},
                                  {"path":"unrelated.txt","mode":"100644","type":"blob","sha":"$FILE_SHA","size":9}
                                ]}
                            """.trimIndent(),
                        )
                    }
                    request.url.encodedPath.endsWith("/$COMMIT_SHA/SKILL.md") -> {
                        textResponse(request, VALID_SKILL)
                    }
                    else -> error("Root skill fetched unrelated repository content: ${request.url}")
                }
            },
        )

        val rootError = assertThrows(SkillInstallException::class.java) {
            runBlocking { rootClient.fetch("https://skills.sh/acme/skills/demo") }
        }
        assertEquals(SkillInstallErrorCode.INVALID_SOURCE, rootError.code)
    }

    private fun githubFixtureClient(
        treeBody: String = validTree(),
        scriptBody: ByteArray = "echo safe".toByteArray(),
    ): OkHttpClient = fakeClient { request ->
        when {
            request.url.host == "api.github.com" && request.url.encodedPath == "/repos/acme/skills" -> {
                jsonResponse(request, """{"default_branch":"main"}""")
            }
            request.url.host == "api.github.com" &&
                request.url.encodedPath == "/repos/acme/skills/commits/main" -> {
                jsonResponse(request, """{"sha":"$COMMIT_SHA","commit":{"tree":{"sha":"$TREE_SHA"}}}""")
            }
            request.url.host == "api.github.com" &&
                request.url.encodedPath == "/repos/acme/skills/git/trees/$TREE_SHA" -> {
                jsonResponse(request, treeBody)
            }
            request.url.encodedPath.endsWith("/skills/demo/SKILL.md") -> textResponse(request, VALID_SKILL)
            request.url.encodedPath.endsWith("/skills/demo/scripts/check.sh") -> {
                bytesResponse(request, scriptBody)
            }
            else -> error("Unexpected request: ${request.url}")
        }
    }

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> handler(chain.request()) })
        .build()

    private fun jsonResponse(request: Request, body: String): Response =
        bytesResponse(request, body.toByteArray(), "application/json")

    private fun textResponse(request: Request, body: String): Response =
        bytesResponse(request, body.toByteArray(), "text/plain")

    private fun bytesResponse(
        request: Request,
        body: ByteArray,
        mediaType: String = "application/octet-stream",
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("test")
        .body(body.toResponseBody(mediaType.toMediaType()))
        .build()

    companion object {
        private val COMMIT_SHA = "a".repeat(40)
        private val TREE_SHA = "b".repeat(40)
        private val FILE_SHA = "c".repeat(40)
        private val VALID_SKILL = """
            ---
            name: demo
            description: Demo skill
            ---
            Follow these instructions.
        """.trimIndent()

        private fun validTree(extraEntry: String = ""): String = """
            {
              "truncated": false,
              "tree": [
                {"path":"skills/demo","mode":"040000","type":"tree","sha":"${"d".repeat(40)}"},
                {"path":"skills/demo/SKILL.md","mode":"100644","type":"blob","sha":"$FILE_SHA","size":80},
                {"path":"skills/demo/scripts","mode":"040000","type":"tree","sha":"${"e".repeat(40)}"},
                {"path":"skills/demo/scripts/check.sh","mode":"100755","type":"blob","sha":"$FILE_SHA","size":9}
                $extraEntry
              ]
            }
        """.trimIndent()
    }
}
