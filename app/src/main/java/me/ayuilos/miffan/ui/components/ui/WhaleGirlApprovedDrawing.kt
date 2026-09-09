package me.ayuilos.miffan.ui.components.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Part boundaries are renderer-owned. Pages continue to supply semantic state only. */
private object ApprovedWhaleParts {
    fun path(data: String): Path = PathParser().parsePathString(data).toPath()
    val ink = Path().apply { StaticWhaleContours.paths.forEach { addPath(it) } }
    private val leftWindow = path("M190 475 L196 457 L213 446 L235 439 L256 440 L270 450 L280 464 L278 490 L278 521 L222 522 L210 507 L196 494 Z")
    private val rightWindow = path("M345 437 L355 425 L374 419 L400 418 L415 425 L428 432 L454 448 L442 460 L431 478 L421 488 L421 502 L364 504 L355 479 Z")
    private val mouthWindow = Path().apply { addRect(Rect(299f, 525f, 341f, 546f)) }
    val leftEye = Path.combine(PathOperation.Intersect, ink, leftWindow)
    val rightEye = Path.combine(PathOperation.Intersect, ink, rightWindow)
    val mouth = Path.combine(PathOperation.Intersect, ink, mouthWindow)
    private val faceWindows = Path().apply { addPath(leftWindow); addPath(rightWindow); addPath(mouthWindow) }
    val restingHead = Path.combine(PathOperation.Difference, ink, faceWindows)
    private val jawWindow = path("M209 526 C216 556 270 568 320 568 C370 568 412 550 440 540 L440 558 C400 589 357 589 318 589 C260 589 216 576 204 546 Z")
    val puffingHead = Path.combine(PathOperation.Difference, restingHead, jawWindow)
    val leftLash = Path.combine(PathOperation.Intersect, leftEye, Path().apply { addRect(Rect(180f, 430f, 285f, 458f)) })
    val rightLash = Path.combine(PathOperation.Intersect, rightEye, Path().apply { addRect(Rect(340f, 410f, 460f, 438f)) })
    val bowl = path("M278 580 Q284 637 331 639 Q375 638 385 580 Z")
    val rice = path("M280 580 Q281 568 294 569 Q300 552 310 565 Q322 551 336 563 Q347 551 355 566 Q370 558 382 580 Z")
    val proudMouth = path("M302 530 Q319 543 340 525 Q333 549 316 544 Q308 539 302 530 Z")
}

internal fun DrawScope.drawApprovedWhaleHead(
    clip: WhaleGirlClip,
    palette: WhaleLinePalette,
    closed: Float,
    puff: Float,
    sleepy: Float,
    time: Double,
    unit: Float,
    gaze: Offset = Offset.Zero,
) {
    val ink = palette.ink
    val paper = palette.paper
    val line = maxOf(2.8f, .65f / unit).coerceAtMost(5f)
    val stroke = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val acting = whaleActing(clip, time)
    drawWhaleColorBlocks(palette)
    val blinkPhase = time % 4.2
    val blink = if (blinkPhase > 3.9) sin((blinkPhase - 3.9) / .3 * PI).toFloat().coerceIn(0f, 1f) else 0f
    val openness = 1f - maxOf(closed, blink)
    // Preserve the approved contours exactly at rest, including after an interrupted reaction.
    if (clip == WhaleGirlClip.IDLE && openness >= .999f && puff < .001f && gaze == Offset.Zero) {
        StaticWhaleContours.paths.forEach { drawWhaleContour(it, ink, unit) }
        return
    }

    val leftCheek = puff * acting.cheekLeft
    val rightCheek = puff * acting.cheekRight
    val jawDrop = puff * (7f + (leftCheek + rightCheek) * .1f - acting.swallow * 6f)
    if (puff > .001f) {
        val face = Path().apply {
            moveTo(207f, 517f)
            cubicTo(244f, 521f, 392f, 510f, 439f, 530f)
            cubicTo(433f + rightCheek, 560f, 376f, 581f + jawDrop, 310f, 575f + jawDrop)
            cubicTo(257f, 574f + jawDrop, 208f - leftCheek, 562f, 207f, 517f)
            close()
        }
        drawPath(face, paper)
        drawWhaleContour(ApprovedWhaleParts.puffingHead, ink, unit)
        val jaw = Path().apply {
            moveTo(210f, 530f)
            cubicTo(208f - leftCheek, 562f, 257f, 574f + jawDrop, 310f, 575f + jawDrop)
            cubicTo(376f, 581f + jawDrop, 433f + rightCheek, 560f, 434f, 545f)
        }
        drawPath(jaw, ink, style = Stroke(maxOf(3.8f, line), cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else drawWhaleContour(ApprovedWhaleParts.restingHead, ink, unit)

    fun closedEye(cx: Float, cy: Float) = Path().apply {
        moveTo(cx - 44, cy + 4)
        cubicTo(cx - 26, cy - 18 + sleepy * 34, cx + 8, cy - 22 + sleepy * 36, cx + 29, cy - 1)
        cubicTo(cx + 11, cy - 12 + sleepy * 30, cx - 12, cy - 9 + sleepy * 30, cx - 29, cy + 10)
        lineTo(cx - 44, cy + 14)
        lineTo(cx - 37, cy + 6)
        close()
    }
    fun eye(original: Path, lash: Path, cx: Float, cy: Float) {
        if (clip == WhaleGirlClip.THINKING) {
            drawWhaleContour(lash, ink, unit)
            val spiral = Path().apply {
                for (i in 0..96) {
                    val angle = i / 96.0 * PI * 4.4 + time * PI
                    val radius = 1f + i / 96f * 25f
                    val x = cx + cos(angle).toFloat() * radius
                    val y = cy + sin(angle).toFloat() * radius
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(spiral, ink, style = Stroke(maxOf(4.6f, line), cap = StrokeCap.Round))
        } else {
            if (openness > .02f) {
                withTransform({
                    translate(gaze.x, gaze.y)
                    scale(if (clip == WhaleGirlClip.SURPRISE) 1.1f else 1f,
                    openness * if (clip == WhaleGirlClip.SURPRISE) 1.12f else 1f, Offset(cx, cy)) }) {
                    drawWhaleContour(original, ink, unit, (openness * 3).coerceAtMost(1f))
                }
            }
            val lidAlpha = if (clip == WhaleGirlClip.SUCCESS) blink else 1 - openness
            if (lidAlpha > .001f) drawPath(closedEye(cx, cy), ink, alpha = lidAlpha)
        }
    }
    eye(ApprovedWhaleParts.leftEye, ApprovedWhaleParts.leftLash, 246f, 482f)
    eye(ApprovedWhaleParts.rightEye, ApprovedWhaleParts.rightLash, 392f, 462f)

    when (clip) {
        WhaleGirlClip.IDLE, WhaleGirlClip.FOCUSED, WhaleGirlClip.TYPING -> drawPath(ApprovedWhaleParts.mouth, ink)
        WhaleGirlClip.SUBMITTED -> drawOval(ink, Offset(314f, 528f), Size(16f, 18f), style = stroke)
        WhaleGirlClip.PETTING -> {
            val smile = Path().apply {
                moveTo(304f, 533f)
                quadraticTo(320f, 549f + 4 * sin(time * PI / 1.5).toFloat(), 338f, 530f)
            }
            drawPath(smile, ink, style = stroke)
        }
        WhaleGirlClip.SUCCESS -> drawPath(ApprovedWhaleParts.proudMouth, ink, style = stroke)
        WhaleGirlClip.THINKING -> drawOval(ink, Offset(312f, 526f), Size(15f, 20f), style = stroke)
        WhaleGirlClip.SURPRISE -> {
            drawOval(ink, Offset(301f, 521f), Size(34f, 43f))
            drawOval(palette.frill, Offset(308f, 543f), Size(20f, 12f))
            withTransform({ scale(acting.alert, acting.alert, Offset(609f, 276f)) }) {
                // The broad tapered mark remains legible at actual chat-avatar sizes.
                val mark = Path().apply {
                    moveTo(596f, 204f); quadraticTo(608f, 198f, 623f, 204f)
                    lineTo(617f, 271f); quadraticTo(608f, 277f, 601f, 271f); close()
                }
                drawPath(mark, palette.accent)
                drawPath(mark, ink, style = stroke)
                drawCircle(palette.accent, 11f, Offset(609f, 294f))
                drawCircle(ink, 11f, Offset(609f, 294f), style = stroke)
                drawLine(ink, Offset(576f, 211f), Offset(565f, 197f), line * 1.5f, StrokeCap.Round)
                drawLine(ink, Offset(638f, 220f), Offset(650f, 210f), line * 1.5f, StrokeCap.Round)
            }
        }
        WhaleGirlClip.CHEWING -> {
            val shift = (leftCheek - rightCheek) * .28f
            val lips = Path().apply {
                moveTo(302f + shift, 532f)
                quadraticTo(311f + shift, 539f - acting.swallow * 5f, 319f + shift, 534f)
                quadraticTo(327f + shift, 540f - acting.swallow * 5f, 337f + shift, 531f)
            }
            drawPath(lips, ink, style = stroke)
            // Small cheek creases follow the working side of the mouth.
            drawArc(ink, 125f, 85f, false, Offset(219f - leftCheek * .4f, 528f), Size(13f, 15f), style = stroke)
            drawArc(ink, -30f, 85f, false, Offset(407f + rightCheek * .4f, 524f), Size(13f, 15f), style = stroke)
        }
        WhaleGirlClip.EATING -> {
            val open = acting.mouthOpen
            val mouth = Path().apply {
                moveTo(300f, 532f)
                quadraticTo(318f, 521f - open * 5f, 338f, 532f)
                cubicTo(337f, 540f + open * 34f, 300f, 544f + open * 34f, 300f, 532f)
                close()
            }
            if (open > .03f) drawPath(mouth, ink)
            else {
                val satisfied = Path().apply {
                    moveTo(303f, 534f)
                    quadraticTo(319f, 546f - acting.swallow * 6f, 336f, 531f)
                }
                drawPath(satisfied, ink, style = stroke)
            }
            withTransform({ translate(0f, -acting.foodReach * 6f) }) {
                drawPath(ApprovedWhaleParts.rice, palette.frill)
                drawPath(ApprovedWhaleParts.rice, ink, style = stroke)
                drawPath(ApprovedWhaleParts.bowl, palette.fin)
                drawPath(ApprovedWhaleParts.bowl, ink, style = stroke)
                drawArc(palette.frill, 15f, 145f, false, Offset(291f, 585f), Size(77f, 32f), style = stroke)
                drawLine(ink, Offset(282f, 583f), Offset(381f, 583f), line, StrokeCap.Round)
                drawOval(palette.frill, Offset(320f, 610f), Size(19f, 10f))
            }
            // Lift one bite on a curved approach, close the mouth, then withdraw empty.
            val reach = acting.foodReach
            val tx = 353f - 34f * reach
            val ty = 573f - 29f * reach - sin(reach * PI).toFloat() * 12f
            val gap = 4f + acting.riceAmount * 7f
            drawLine(ink, Offset(tx - 5f, ty - gap / 2), Offset(tx + 91f, ty - 41f), line * 1.6f, StrokeCap.Round)
            drawLine(ink, Offset(tx, ty + gap / 2), Offset(tx + 98f, ty - 29f), line * 1.6f, StrokeCap.Round)
            if (acting.riceAmount > .001f) {
                withTransform({ scale(acting.riceAmount, acting.riceAmount, Offset(tx, ty)) }) {
                drawOval(palette.frill, Offset(tx - 15f, ty - 10f), Size(27f, 18f))
                drawOval(ink, Offset(tx - 15f, ty - 10f), Size(27f, 18f), style = stroke)
                drawLine(palette.fin, Offset(tx - 6f, ty - 5f), Offset(tx - 2f, ty), line * .7f, StrokeCap.Round)
                }
            }
        }
        WhaleGirlClip.SLEEPING -> {
            val breath = acting.breath
            drawOval(ink, Offset(311f, 532f), Size(15f, 5f + breath * 4f))
            // Anchor between the eyes and mouth; keep the neck above the mouth throughout breathing.
            val bubble = Path().apply {
                moveTo(321f, 505f)
                cubicTo(335f, 506f, 341f, 493f - breath * 4f, 361f, 492f - breath * 4f)
                cubicTo(392f + breath * 8f, 490f - breath * 4f, 399f + breath * 8f,
                    533f + breath * 5f, 372f, 540f + breath * 5f)
                cubicTo(347f, 547f + breath * 4f, 343f, 518f, 321f, 505f)
                close()
            }
            drawPath(bubble, palette.frill.copy(alpha = .82f))
            drawPath(bubble, ink, style = stroke)
            drawArc(palette.fin, 195f, 70f, false, Offset(352f, 499f - breath * 4f),
                Size(30f + breath * 6f, 30f + breath * 6f), style = stroke)
        }
    }
}

/** Give subpixel contours a small optical weight at avatar sizes; large approved art is unchanged. */
internal fun DrawScope.drawWhaleContour(path: Path, color: Color, unit: Float, alpha: Float = 1f) {
    drawPath(path, color, alpha = alpha)
    val extra = (.45f * density / unit - 3.2f).coerceAtLeast(0f)
    if (extra > 0f) drawPath(path, color, alpha = alpha,
        style = Stroke(extra, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
