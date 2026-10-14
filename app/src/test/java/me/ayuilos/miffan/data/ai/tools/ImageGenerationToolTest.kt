package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.mergeCustomBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class ImageGenerationToolTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=")
    private val dataUrl get() = "data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"
    private val model = Model(modelId = "test/image", displayName = "Test Image",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE), outputModalities = listOf(Modality.TEXT, Modality.IMAGE))
    private fun settings(model: Model = this.model) = Settings(
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))), imageToolModelId = model.id)
    private fun args(vararg ids: String) = buildJsonObject {
        put("prompt", "Draw a red bowl")
        if (ids.isNotEmpty()) put("reference_images", buildJsonArray { ids.forEach { add(it) } })
    }
    private fun tool(
        fake: FakeProvider,
        current: () -> Settings = { settings() },
        messages: () -> List<UIMessage> = { emptyList() },
        download: suspend (String) -> ByteArray = { error("Download unavailable") },
    ) = createImageGenerationTool(current, messages, { fake }, { bytes ->
        temporary.newFile("${java.util.UUID.randomUUID()}.png").apply { writeBytes(bytes) }.toURI().toString()
    }, download, download)

    @Test fun eligibilityIncludesEveryImageGenerationKind() {
        assertFalse(Model().canOutputImage())
        assertTrue(model.canOutputImage())
        assertTrue(Model(type = ModelType.IMAGE).canOutputImage())
        assertTrue(Model(tools = setOf(BuiltInTools.ImageGeneration)).canOutputImage())
        assertFalse(Model(tools = setOf(BuiltInTools.Search)).canOutputImage())
        assertNull(settings().copy(imageToolModelId = null).imageToolModelOrNull())
    }

    @Test fun deterministicIdsResolveAttachmentsAndEarlierCompletedToolOutputs() {
        val attachment = UIMessagePart.Image("file:///uploads/one.png")
        val generated = UIMessagePart.Image("file:///uploads/two.png")
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(attachment)),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call", "generate_image", "{}", listOf(generated)))))
        val id = imageIdForUrl(attachment.url)
        assertEquals(id, imageIdForUrl(attachment.url))
        assertNotEquals(id, imageIdForUrl(generated.url))
        assertTrue(id.matches(Regex("img_[0-9a-f]{24}")))
        assertEquals(listOf(attachment, generated), resolveImageReferences(listOf(id, imageIdForUrl(generated.url)), messages))
        val error = assertThrows(IllegalArgumentException::class.java) { resolveImageReferences(listOf("unknown"), messages) }
        assertTrue(error.message!!.contains(id))
        assertTrue(error.message!!.contains(imageIdForUrl(generated.url)))
        assertThrows(IllegalArgumentException::class.java) { resolveImageReferences(List(5) { id }, messages) }
    }

    @Test fun chatOutputSavesBase64AndReturnsIdsWithReferencesAndOptionalSchema() = runTest {
        val reference = UIMessagePart.Image("file:///uploads/reference.png")
        val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("A red bowl."), UIMessagePart.Image(dataUrl))))
        val tool = tool(fake, messages = { listOf(UIMessage(role = MessageRole.USER, parts = listOf(reference))) })
        assertEquals("generate_image", tool.name)
        assertEquals(listOf("prompt"), (tool.parameters() as InputSchema.Obj).required)
        assertTrue(tool.description.contains("Test Image"))
        val output = tool.execute(args(imageIdForUrl(reference.url)))
        val image = output[1] as UIMessagePart.Image
        assertTrue(image.url.startsWith("file:"))
        assertArrayEquals(png, java.io.File(java.net.URI(image.url)).readBytes())
        val json = Json.parseToJsonElement((output[0] as UIMessagePart.Text).text).jsonObject
        assertEquals(model.modelId, json["model"]!!.jsonPrimitive.content)
        assertEquals("A red bowl.", json["text"]!!.jsonPrimitive.content)
        assertEquals(imageIdForUrl(image.url), json["images"]!!.jsonArray.single().jsonObject["image_id"]!!.jsonPrimitive.content)
        assertFalse(output.toString().contains("base64"))
        assertEquals(listOf(UIMessagePart.Text("Draw a red bowl"), reference), fake.messages.single().parts)
        assertEquals(MessageRole.USER, fake.messages.single().role)
        assertTrue(fake.params!!.tools.isEmpty())
        assertTrue(fake.params!!.model.tools.isEmpty())
    }

    @Test fun imagesApiGeneratesFinalImagesAndIgnoresPartialPreviews() = runTest {
        val imageModel = Model(modelId = "images-api", type = ModelType.IMAGE)
        val fake = FakeProvider().apply { imageItems = listOf(ImageGenerationItem("preview", "image/png", partial = true),
            ImageGenerationItem(Base64.getEncoder().encodeToString(png), "image/png")) }
        val output = tool(fake, { settings(imageModel) }).execute(args())
        assertEquals(2, output.size)
        assertTrue(output[1] is UIMessagePart.Image)
        assertEquals("Draw a red bowl", fake.imageParams!!.prompt)
        assertNull(fake.params)
        assertNull(fake.editParams)
    }

    @Test fun legacyOpenRouterImageOnlyChatModelsUseImagesApiWhileGeminiStaysOnChat() = runTest {
        val flux = model.copy(modelId = "black-forest-labs/flux-3-image", outputModalities = listOf(Modality.IMAGE))
        val gemini = model.copy(modelId = "google/gemini-2.5-flash-image")
        for (selected in listOf(flux, gemini)) {
            val provider = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1", models = listOf(selected))
            val current = Settings(providers = listOf(provider), imageToolModelId = selected.id)
            val fake = FakeProvider(UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Image(dataUrl))))
                .apply { imageItems = listOf(ImageGenerationItem(Base64.getEncoder().encodeToString(png), "image/png")) }
            val output = tool(fake, { current }).execute(args())
            assertTrue(output[1] is UIMessagePart.Image)
            assertEquals(ModelType.CHAT, selected.type)
            if (selected == flux) {
                assertNotNull(fake.imageParams)
                assertNull(fake.params)
            } else {
                assertNotNull(fake.params)
                assertNull(fake.imageParams)
            }
        }
    }

    @Test fun openRouterRoutingUsesEffectiveProviderOverwrite() = runTest {
        val flux = model.copy(outputModalities = listOf(Modality.IMAGE),
            providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1"))
        val fake = FakeProvider().apply { imageItems = listOf(ImageGenerationItem(Base64.getEncoder().encodeToString(png), "image/png")) }
        val output = tool(fake, { settings(flux) }).execute(args())
        assertTrue(output[1] is UIMessagePart.Image)
        assertNotNull(fake.imageParams)
        assertNull(fake.params)
    }

    @Test fun referenceUrlsAreAcceptedAndDownloadedBeforeCallingEitherProviderApi() = runTest {
        val url = "https://images.example/reference.png"
        assertEquals(listOf(UIMessagePart.Image(url)), resolveImageReferences(listOf(url), emptyList()))
        assertThrows(IllegalArgumentException::class.java) { resolveImageReferences(listOf("data:image/png;base64,AQID"), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { resolveImageReferences(List(5) { url }, emptyList()) }
        for (imageApi in listOf(false, true)) {
            val selected = if (imageApi) model.copy(type = ModelType.IMAGE) else model
            val fake = FakeProvider(UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Image(dataUrl))))
                .apply { imageItems = listOf(ImageGenerationItem(Base64.getEncoder().encodeToString(png), "image/png")) }
            var downloaded = false
            val output = tool(fake, { settings(selected) }, download = {
                assertEquals(url, it)
                downloaded = true
                png
            }).execute(args(url))
            assertTrue(output[1] is UIMessagePart.Image)
            assertTrue(downloaded)
            val referenceFile = if (imageApi) java.io.File(fake.editParams!!.images.single()) else {
                val image = fake.messages.single().parts.filterIsInstance<UIMessagePart.Image>().single()
                assertTrue(image.url.startsWith("file:"))
                java.io.File(java.net.URI(image.url))
            }
            assertArrayEquals(png, referenceFile.readBytes())
        }
    }

    @Test fun explicitReferenceUrlsUseTheGuardAndReturnReadableErrorsBeforeProviderExecution() = runTest {
        val fake = FakeProvider()
        var ordinaryDownloads = 0
        val client = okhttp3.OkHttpClient.Builder().dns(okhttp3.Dns {
            listOf(java.net.InetAddress.getByName("8.8.8.8"))
        }).build()
        val guarded = createImageGenerationTool({ settings() }, { emptyList() }, { fake },
            saveImage = { error("Rejected reference must not be saved") },
            downloadImage = { ordinaryDownloads++; png },
            downloadReferenceImage = { ReferenceImageDownloader(client).download(it) })
        val output = guarded.execute(args("http://127.0.0.1/private.png"))
        val error = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertTrue(error["error"]!!.jsonPrimitive.content.startsWith(REFERENCE_IMAGE_URL_ERROR))
        assertEquals(0, ordinaryDownloads)
        assertNull(fake.params)
    }

    @Test fun chatImageIdsAndProviderReturnedUrlsRetainTheirOrdinaryDownloader() = runTest {
        val url = "http://127.0.0.1/provider-image.png"
        val image = UIMessagePart.Image(url)
        val fake = FakeProvider(UIMessage.assistant("").copy(parts = listOf(image)))
        var ordinaryDownloads = 0
        val unguardedProviderImages = createImageGenerationTool({ settings() },
            { listOf(UIMessage.assistant("").copy(parts = listOf(image))) }, { fake },
            saveImage = { bytes -> temporary.newFile().apply { writeBytes(bytes) }.toURI().toString() },
            downloadImage = { assertEquals(url, it); ordinaryDownloads++; png },
            downloadReferenceImage = { error("Only explicit reference URLs use the new guard") })
        val output = unguardedProviderImages.execute(args(imageIdForUrl(url)))
        assertTrue(output[1] is UIMessagePart.Image)
        assertEquals(2, ordinaryDownloads)
    }

    @Test fun imagesApiEditsWithDecodedLocalFilePaths() = runTest {
        val imageModel = Model(modelId = "images-api", type = ModelType.IMAGE)
        val file = temporary.newFile("reference with spaces.png").apply { writeBytes(png) }
        val reference = UIMessagePart.Image(file.toURI().toString())
        val fake = FakeProvider().apply { imageItems = listOf(ImageGenerationItem(Base64.getEncoder().encodeToString(png), "image/png")) }
        val output = tool(fake, { settings(imageModel) }, { listOf(UIMessage(role = MessageRole.USER, parts = listOf(reference))) })
            .execute(args(imageIdForUrl(reference.url)))
        assertTrue(output[1] is UIMessagePart.Image)
        assertEquals(listOf(file.absolutePath), fake.editParams!!.images)
        assertNull(fake.imageParams)
        assertNull(fake.params)
    }

    @Test fun responsesApiKeepsOnlyImageBuiltInToolAndUsesResponsesProvider() = runTest {
        val responsesModel = Model(modelId = "responses-image", tools = setOf(BuiltInTools.ImageGeneration, BuiltInTools.Search))
        val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(dataUrl))))
        val output = tool(fake, { settings(responsesModel) }).execute(args())
        assertTrue(output[1] is UIMessagePart.Image)
        assertTrue((fake.providerSetting as ProviderSetting.OpenAI).useResponseApi)
        assertEquals(setOf(BuiltInTools.ImageGeneration), fake.params!!.model.tools)
        assertTrue(fake.messages.single().toText().contains("image_generation tool"))
        assertNull(fake.imageParams)
    }

    @Test fun usesLiveSettingsIncludingProviderOverwriteAndLiveReferences() = runTest {
        var current = settings()
        var liveMessages = emptyList<UIMessage>()
        val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(dataUrl))))
        val tool = tool(fake, { current }, { liveMessages })
        val override = ProviderSetting.OpenAI(name = "Override", apiKey = "override-key")
        val changed = model.copy(modelId = "new-model", providerOverwrite = override)
        current = settings(changed)
        val reference = UIMessagePart.Image("file:///new-reference.png")
        liveMessages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("earlier", "generate_image", "{}", listOf(reference)))))
        val output = tool.execute(args(imageIdForUrl(reference.url)))
        assertTrue(output[1] is UIMessagePart.Image)
        assertEquals("new-model", fake.params!!.model.modelId)
        assertEquals(override.id, fake.providerSetting!!.id)
        assertEquals(reference, fake.messages.single().parts.last())
    }

    @Test fun noImageAndUnknownReferencesReturnReadableErrors() = runTest {
        val refusal = tool(FakeProvider(UIMessage.assistant("I cannot create that image."))).execute(args())
        assertTrue((refusal.single() as UIMessagePart.Text).text.contains("I cannot create that image."))
        val fake = FakeProvider()
        val unknown = tool(fake).execute(args("unknown"))
        assertTrue((unknown.single() as UIMessagePart.Text).text.contains("Available image ids: none"))
        assertNull(fake.params)
    }

    @Test fun unconfiguredToolReturnsExactSetupContractWithoutCallingProvider() = runTest {
        val fake = FakeProvider()
        for (current in listOf(settings().copy(imageToolModelId = null), settings(Model(modelId = "plain-chat")))) {
            val tool = tool(fake, { current })
            assertTrue(tool.description.contains("not set up yet"))
            assertTrue(tool.description.contains("call this tool anyway"))
            val output = tool.execute(args())
            assertEquals(1, output.size)
            assertEquals(Json.parseToJsonElement("""{"setup_required":true,"message":"Image drawing is not set up yet. The user is shown a button to turn it on; tell them briefly and wait."}"""),
                Json.parseToJsonElement((output.single() as UIMessagePart.Text).text))
        }
        assertNull(fake.params)
        assertNull(fake.imageParams)
    }

    @Test fun configuredAfterBuildDrawsUsingCurrentSettings() = runTest {
        var current = settings().copy(imageToolModelId = null)
        val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(dataUrl))))
        val tool = tool(fake, { current })
        assertTrue(tool.description.contains("not set up yet"))
        current = settings()
        val output = tool.execute(args())
        assertTrue(output[1] is UIMessagePart.Image)
        assertEquals(model.id, fake.params!!.model.id)
        assertFalse((output[0] as UIMessagePart.Text).text.contains("setup_required"))
    }

    @Test fun approvalUsesLiveConfigurationAndReasonIsOnlySetForUnconfiguredTurns() {
        var current = settings().copy(imageToolModelId = null)
        val tool = tool(FakeProvider(), { current })
        assertEquals("image_setup", IMAGE_SETUP_APPROVAL_REASON)
        assertEquals(IMAGE_SETUP_APPROVAL_REASON, tool.approvalReason)
        assertTrue(tool.needsApproval(args()))
        assertNull(tool.autoApprovedBy(args()))
        assertTrue(tool.description.contains("pauses the call"))
        current = settings()
        assertFalse(tool.needsApproval(args()))
        val configuredTool = tool(FakeProvider(), { current })
        assertFalse(configuredTool.needsApproval(args()))
        assertNull(configuredTool.approvalReason)
        assertNull(configuredTool.autoApprovedBy(args()))
        current = current.copy(imageToolModelId = null)
        assertTrue(configuredTool.needsApproval(args()))
    }

    @Test fun subscriptionChatModelsDrawViaResponsesWithoutChangingStoredModel() = runTest {
        val override = ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION)
        for (useOverwrite in listOf(false, true)) {
            val chat = Model(modelId = "gpt-chat-mini", providerOverwrite = override.takeIf { useOverwrite })
            val provider = ProviderSetting.OpenAI(models = listOf(chat),
                authType = if (useOverwrite) OpenAIAuthType.API_KEY else OpenAIAuthType.CHATGPT_SUBSCRIPTION)
            val current = Settings(providers = listOf(provider), imageToolModelId = chat.id)
            val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(dataUrl))))
            val output = tool(fake, { current }).execute(args())
            assertTrue(output[1] is UIMessagePart.Image)
            val actualProvider = fake.providerSetting as ProviderSetting.OpenAI
            assertTrue(actualProvider.useResponseApi)
            assertEquals(OpenAIAuthType.CHATGPT_SUBSCRIPTION, actualProvider.authType)
            assertEquals(setOf(BuiltInTools.ImageGeneration), fake.params!!.model.tools)
            assertTrue(fake.params!!.tools.isEmpty())
            assertTrue(fake.messages.single().toText().contains("image_generation tool"))
            assertFalse(chat.canOutputImage())
            assertTrue(chat.tools.isEmpty())
            assertEquals(chat, current.providers.single().models.single())
        }
    }

    @Test fun downloadsRemoteImagesAndFallsBackToUrlOnDownloadFailure() = runTest {
        val url = "https://images.example/result.png"
        val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(url))))
        val saved = tool(fake, download = { assertEquals(url, it); png }).execute(args())
        assertTrue((saved[1] as UIMessagePart.Image).url.startsWith("file:"))
        val fallback = tool(fake).execute(args())
        assertEquals(url, (fallback[1] as UIMessagePart.Image).url)
        assertTrue((fallback[0] as UIMessagePart.Text).text.contains(imageIdForUrl(url)))
    }

    @Test fun cancellationPropagatesInsteadOfFallingBackToRemoteUrl() = runTest {
        val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image("https://images.example/result.png"))))
        try {
            tool(fake, download = { throw CancellationException("Stopped") }).execute(args())
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("Stopped", e.message)
        }
    }

    @Test fun optionsAreOptionalAndReservedKeysFailBeforeCallingProvider() = runTest {
        val fake = FakeProvider()
        val tool = tool(fake)
        val schema = tool.parameters() as InputSchema.Obj
        assertTrue("options" in schema.properties)
        assertEquals(listOf("prompt"), schema.required)
        for (key in listOf("model", "prompt", "messages", "input", "input_references", "image", "images", "n", "stream", "tools")) {
            val output = tool.execute(JsonObject(args() + ("options" to buildJsonObject { put(key, "bad") })))
            val error = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject["error"]!!.jsonPrimitive.content
            assertTrue(error.contains("Reserved options"))
            assertTrue(error.contains(key))
        }
        val output = tool.execute(JsonObject(args() + ("options" to JsonPrimitive("bad"))))
        assertTrue((output.single() as UIMessagePart.Text).text.contains("options must be a JSON object"))
        assertNull(fake.params)
        assertNull(fake.imageParams)
    }

    @Test fun optionsRouteToImagesChatAndResponsesAndUserChoicesWin() = runTest {
        val choices = listOf(CustomBody("quality", JsonPrimitive("low")), CustomBody("size", JsonNull))
        val options = buildJsonObject {
            put("quality", "high")
            put("size", "1024x1024")
            put("resolution", "1K")
            put("remove", JsonNull)
            put("config", buildJsonObject { put("aspect_ratio", "16:9") })
        }
        val input = JsonObject(args() + ("options" to options))
        for (kind in listOf("images", "chat", "responses")) {
            val selected = when (kind) {
                "images" -> model.copy(type = ModelType.IMAGE, customBodies = choices)
                "responses" -> model.copy(tools = setOf(BuiltInTools.ImageGeneration), customBodies = choices)
                else -> model.copy(customBodies = choices)
            }
            val fake = FakeProvider(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(dataUrl))))
            fake.imageItems = listOf(ImageGenerationItem(Base64.getEncoder().encodeToString(png), "image/png"))
            val output = tool(fake, current = { settings(selected) }).execute(input)
            assertEquals(2, output.size)
            val extras = if (kind == "images") fake.imageParams!!.customBody else
                if (kind == "responses") fake.params!!.imageGenerationOptions else fake.params!!.customBody
            val merged = buildJsonObject { put("remove", true) }.mergeCustomBody(extras)
            assertEquals("1K", merged["resolution"]!!.jsonPrimitive.content)
            assertFalse("remove" in merged)
            assertFalse("size" in merged)
            assertEquals("16:9", merged["config"]!!.jsonObject["aspect_ratio"]!!.jsonPrimitive.content)
            if (kind == "responses") {
                assertFalse("quality" in merged)
                assertEquals(choices, fake.params!!.customBody)
            } else assertEquals("low", merged["quality"]!!.jsonPrimitive.content)
            if (kind == "images") {
                val ref = UIMessagePart.Image(temporary.newFile("options-ref.png").apply { writeBytes(png) }.toURI().toString())
                tool(fake, current = { settings(selected) }, messages = { listOf(UIMessage(role = MessageRole.USER, parts = listOf(ref))) })
                    .execute(JsonObject(input + ("reference_images" to buildJsonArray { add(imageIdForUrl(ref.url)) })))
                assertEquals(extras, fake.editParams!!.customBody)
            }
        }
    }

    private class FakeProvider(private val reply: UIMessage = UIMessage.assistant("")) : Provider<ProviderSetting> {
        var messages = emptyList<UIMessage>()
        var params: TextGenerationParams? = null
        var providerSetting: ProviderSetting? = null
        var imageParams: ImageGenerationParams? = null
        var editParams: ImageEditParams? = null
        var imageItems = emptyList<ImageGenerationItem>()
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = error("Unexpected streaming")
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
            this.messages = messages
            this.params = params
            this.providerSetting = providerSetting
            return TextGenerationResult("reply", params.model.modelId, reply)
        }
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem> {
            this.imageParams = params
            return flowOf(*imageItems.toTypedArray())
        }
        override suspend fun editImage(providerSetting: ProviderSetting, params: ImageEditParams): Flow<ImageGenerationItem> {
            this.editParams = params
            return flowOf(*imageItems.toTypedArray())
        }
    }
}
