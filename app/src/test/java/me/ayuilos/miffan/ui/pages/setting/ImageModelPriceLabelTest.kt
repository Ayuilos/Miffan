package me.ayuilos.miffan.ui.pages.setting

import me.ayuilos.miffan.data.ai.openrouter.OpenRouterImageModel
import me.rerere.ai.provider.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageModelPriceLabelTest {
    private fun model(vararg prices: Pair<String, String>) = OpenRouterImageModel(
        id = "m", name = "m", description = null, inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.IMAGE), pricing = mapOf(*prices), contextLength = null, created = null,
    )

    @Test fun imageOnlyModelsBillThroughImageOutputNotPromptOrCompletion() {
        // black-forest-labs/flux-3-image as OpenRouter lists it (2026-10-15)
        val flux = model("prompt" to "0", "completion" to "0", "image_token" to "0.00000982035928143713",
            "image_output" to "0.00000982035928143713")
        assertFalse(flux.isFree())
        assertEquals("$9.82/M 图像输出", flux.priceLabel())
    }

    @Test fun onlyAllZeroPricesAreFree() {
        assertTrue(model("prompt" to "0", "completion" to "0", "image_output" to "0").isFree())
        assertFalse(model().isFree())
        assertEquals("价格不固定", model("prompt" to "-1", "completion" to "-1").priceLabel())
        assertEquals("$30/M 图像输出", model("prompt" to "0.0000015", "completion" to "0.0000075", "image_output" to "0.00003").priceLabel())
    }
}
