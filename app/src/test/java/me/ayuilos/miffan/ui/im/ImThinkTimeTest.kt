package me.ayuilos.miffan.ui.im

import me.ayuilos.miffan.R
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ReasoningCapabilities
import org.junit.Assert.*
import org.junit.Test

class ImThinkTimeTest {
    private val open = Model(modelId = "open")
    private val mandatory = Model(modelId = "always", reasoningCapabilities = ReasoningCapabilities(mandatory = true))
    private val upToHigh = Model(modelId = "high", reasoningCapabilities = ReasoningCapabilities(supportedEfforts = listOf("low", "medium", "high")))

    @Test fun tiersStoreTheirLevelsOnAModelThatSupportsEverything() {
        assertEquals(ThinkTime.entries, thinkTimesFor(open))
        assertEquals(ReasoningLevel.OFF, ThinkTime.QUICK.levelFor(open))
        assertEquals(ReasoningLevel.AUTO, ThinkTime.AUTO.levelFor(open))
        assertEquals(ReasoningLevel.HIGH, ThinkTime.MORE.levelFor(open))
        assertEquals(ReasoningLevel.MAX, ThinkTime.DEEP.levelFor(open))
    }

    @Test fun storedLevelsReadAsTheirTier() {
        val shown = ReasoningLevel.entries.associateWith { thinkTimeOf(it, open) }
        assertEquals(ThinkTime.QUICK, shown[ReasoningLevel.OFF])
        // Professional-mode levels without a tier of their own read as the default.
        assertEquals(ThinkTime.AUTO, shown[ReasoningLevel.LOW])
        assertEquals(ThinkTime.AUTO, shown[ReasoningLevel.MEDIUM])
        assertEquals(ThinkTime.AUTO, shown[ReasoningLevel.AUTO])
        assertEquals(ThinkTime.MORE, shown[ReasoningLevel.HIGH])
        assertEquals(ThinkTime.DEEP, shown[ReasoningLevel.XHIGH])
        assertEquals(ThinkTime.DEEP, shown[ReasoningLevel.MAX])
    }

    @Test fun anAlwaysThinkingModelKeepsTheQuickTierAsShortThinking() {
        assertFalse(mandatory.canSkipThinking())
        assertEquals(ReasoningLevel.LOW, ThinkTime.QUICK.levelFor(mandatory))
        assertEquals(ThinkTime.QUICK, thinkTimeOf(ReasoningLevel.LOW, mandatory))
        assertEquals(R.string.im_think_quick_forced, ThinkTime.QUICK.titleFor(mandatory))
        assertEquals(R.string.im_think_quick, ThinkTime.QUICK.titleFor(open))
    }

    @Test fun tiersThatWouldStoreTheSameLevelCollapse() {
        // Without xhigh or max, deep thinking is what "think more" already does.
        assertEquals(listOf(ThinkTime.QUICK, ThinkTime.AUTO, ThinkTime.MORE), thinkTimesFor(upToHigh))
        assertEquals(ReasoningLevel.HIGH, ThinkTime.DEEP.levelFor(upToHigh))
        // An unsupported stored level is sent as AUTO, so that is what the row shows.
        assertEquals(ThinkTime.AUTO, thinkTimeOf(ReasoningLevel.MAX, upToHigh))
    }
}
