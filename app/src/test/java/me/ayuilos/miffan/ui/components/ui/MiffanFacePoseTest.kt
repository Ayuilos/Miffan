package me.ayuilos.miffan.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiffanFacePoseTest {
    @Test
    fun semanticExpressionsRemainReadableAndOverrideInput() {
        MiffanMascotState.entries.forEach { state ->
            MiffanMascotInputState.entries.forEach { input ->
                val pose = miffanFacePose(state, input)
                assertTrue(pose.eyeOpen - kotlin.math.abs(pose.eyeAsymmetry) > 0.5f)
                assertTrue(pose.mouthOpen in 3f..15f)
                if (state != MiffanMascotState.Idle) {
                    assertEquals(miffanFacePose(state, MiffanMascotInputState.Inactive), pose)
                }
            }
        }
        assertTrue(miffanFacePose(MiffanMascotState.Happy, MiffanMascotInputState.Inactive).mouthCurve > 0f)
        assertTrue(miffanFacePose(MiffanMascotState.Error, MiffanMascotInputState.Inactive).mouthCurve < 0f)
    }

    @Test
    fun faceVectorPreservesEveryAnimatedParameter() {
        MiffanMascotState.entries.forEach { state ->
            val pose = miffanFacePose(state, MiffanMascotInputState.Inactive)
            assertEquals(pose, MiffanFacePose.VectorConverter.convertFromVector(
                MiffanFacePose.VectorConverter.convertToVector(pose),
            ))
        }
    }

    @Test
    fun attentionBlendsFromAnIntermediateExpressionWithoutReplacingIt() {
        val happy = miffanFacePose(MiffanMascotState.Happy, MiffanMascotInputState.Inactive)
        val error = miffanFacePose(MiffanMascotState.Error, MiffanMascotInputState.Inactive)
        val midway = happy.blendTo(error, 0.4f)
        assertEquals(midway, midway.blendTo(MiffanFacePose.Attention, 0f))
        assertEquals(MiffanFacePose.Attention, midway.blendTo(MiffanFacePose.Attention, 1f))
        assertTrue(midway.mouthCurve < happy.mouthCurve && midway.mouthCurve > error.mouthCurve)
    }
}
