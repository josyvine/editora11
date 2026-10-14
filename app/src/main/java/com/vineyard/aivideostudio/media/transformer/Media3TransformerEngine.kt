package com.vineyard.aivideostudio.media.transformer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.vineyard.aivideostudio.ai.model.HighlightSegment
import com.vineyard.aivideostudio.ai.model.RecipeCompositeLayerDto
import com.vineyard.aivideostudio.core.model.Caption
import com.vineyard.aivideostudio.core.model.effects.BlurSpec
import com.vineyard.aivideostudio.core.model.effects.ColorGradeSpec
import com.vineyard.aivideostudio.core.model.effects.ReplacementOverlaySpec
import com.vineyard.aivideostudio.core.model.effects.SpeedRampSpec
import com.vineyard.aivideostudio.core.model.effects.TextCardSpec
import com.vineyard.aivideostudio.core.model.effects.TrackingIndicatorSpec
import com.vineyard.aivideostudio.core.result.AppError
import com.vineyard.aivideostudio.core.result.AppResult
import com.vineyard.aivideostudio.media.transformer.effects.BlurGlEffect
import com.vineyard.aivideostudio.media.transformer.effects.ColorFilterGlEffect
import com.vineyard.aivideostudio.media.transformer.overlays.DynamicGraphicsOverlay
import com.vineyard.aivideostudio.media.transformer.overlays.TextCardOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
class Media3TransformerEngine(private val context: Context) {

    suspend fun trimVideo(
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        stripAudio: Boolean = false
    ): AppResult<File> = withContext(Dispatchers.Main) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(stripAudio)
            .build()

        runTransformer(editedMediaItem, outputFile)
    }

    suspend fun spliceHighlightSegments(
        inputUri: Uri,
        outputFile: File,
        segments: List<HighlightSegment>,
        stripAudio: Boolean = false
    ): AppResult<File> = withContext(Dispatchers.Main) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        if (segments.isEmpty()) {
            return@withContext trimVideo(inputUri, outputFile, 0L, 60_000L, stripAudio)
        }

        val editedMediaItems = segments.map { segment ->
            val startMs = (segment.start * 1000L).toLong()
            val endMs = (segment.end * 1000L).toLong()

            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(stripAudio)
                .build()
        }

        val sequence = EditedMediaItemSequence(editedMediaItems)
        val composition = Composition.Builder(listOf(sequence)).build()

        runTransformer(composition, outputFile)
    }

    suspend fun cropVideo(
        inputUri: Uri,
        outputFile: File,
        normalizedLeft: Float,
        normalizedRight: Float,
        normalizedBottom: Float,
        normalizedTop: Float,
        stripAudio: Boolean = false
    ): AppResult<File> = withContext(Dispatchers.Main) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val left = (normalizedLeft * 2f) - 1f
        val right = (normalizedRight * 2f) - 1f
        val bottom = (normalizedBottom * 2f) - 1f
        val top = (normalizedTop * 2f) - 1f

        val cropEffect = Crop(left, right, bottom, top)
        val effects = Effects(emptyList(), listOf(cropEffect))

        val mediaItem = MediaItem.fromUri(inputUri)
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(stripAudio)
            .setEffects(effects)
            .build()

        runTransformer(editedMediaItem, outputFile)
    }

    suspend fun zoomVideo(
        inputUri: Uri,
        outputFile: File,
        scale: Float,
        stripAudio: Boolean = false
    ): AppResult<File> = withContext(Dispatchers.Main) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val scaleEffect = ScaleAndRotateTransformation.Builder()
            .setScale(scale, scale)
            .build()
        val effects = Effects(emptyList(), listOf(scaleEffect))

        val mediaItem = MediaItem.fromUri(inputUri)
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(stripAudio)
            .setEffects(effects)
            .build()

        runTransformer(editedMediaItem, outputFile)
    }

    suspend fun exportVideo(
        inputUri: Uri,
        outputFile: File,
        commentaryAudioUri: Uri? = null,
        stripOriginalAudio: Boolean = true,
        captions: List<Caption> = emptyList(),
        targetAspectRatio: String = "ORIGINAL",
        zoomScale: Float = 1.0f,
        speedRamps: List<SpeedRampSpec> = emptyList(),
        blurSpecs: List<BlurSpec> = emptyList(),
        replacementOverlays: List<ReplacementOverlaySpec> = emptyList(),
        colorGrade: ColorGradeSpec? = null,
        trackingIndicators: List<TrackingIndicatorSpec> = emptyList(),
        textCards: List<TextCardSpec> = emptyList(),
        compositeLayers: List<RecipeCompositeLayerDto> = emptyList(),
        videoWidth: Int? = null,
        videoHeight: Int? = null,
        overlaySourceUri: Uri? = null,
        maskBitmapProvider: ((Long) -> Bitmap?)? = null
    ): AppResult<File> = withContext(Dispatchers.Main) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        // 1. Raw Resolution Extraction
        val rawDimensions = if (videoWidth != null && videoHeight != null && videoWidth > 0 && videoHeight > 0) {
            Pair(videoWidth, videoHeight)
        } else {
            getVideoDimensions(inputUri)
        }

        // 2. UNIVERSAL CANVAS ALIGNMENT:
        // Guarantees that targetAspectRatio (e.g. 9:16 Shorts) sets a portrait canvas (1080x1920)
        // instead of inheriting inverted raw sensor landscape dimensions (1920x1080).
        val (resolvedWidth, resolvedHeight) = when (targetAspectRatio) {
            "9:16" -> Pair(minOf(rawDimensions.first, rawDimensions.second), maxOf(rawDimensions.first, rawDimensions.second))
            "16:9" -> Pair(maxOf(rawDimensions.first, rawDimensions.second), minOf(rawDimensions.first, rawDimensions.second))
            "1:1" -> {
                val minSide = minOf(rawDimensions.first, rawDimensions.second)
                Pair(minSide, minSide)
            }
            else -> rawDimensions
        }

        val sharedVideoEffects = mutableListOf<Effect>()

        when (targetAspectRatio) {
            "9:16" -> sharedVideoEffects.add(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT))
            "16:9" -> sharedVideoEffects.add(Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT))
            "1:1" -> sharedVideoEffects.add(Presentation.createForAspectRatio(1f, Presentation.LAYOUT_SCALE_TO_FIT))
            else -> {}
        }

        if (zoomScale > 1.0f) {
            sharedVideoEffects.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(zoomScale, zoomScale)
                    .build()
            )
        }

        if (blurSpecs.isNotEmpty()) {
            sharedVideoEffects.add(BlurGlEffect(blurSpecs))
        }

        if (colorGrade != null) {
            sharedVideoEffects.add(ColorFilterGlEffect(colorGrade))
        }

        val overlayList = mutableListOf<TextureOverlay>()

        // 1. UNIVERSAL CLONE OVERLAY (Composites the ML Kit cutout directly onto Track 1's canvas)
        if (compositeLayers.isNotEmpty() && maskBitmapProvider != null) {
            for (layer in compositeLayers) {
                if (layer.layerOrder > 0) {
                    overlayList.add(
                        UniversalCloneBitmapOverlay(
                            layerDto = layer,
                            maskBitmapProvider = maskBitmapProvider,
                            targetWidth = resolvedWidth,
                            targetHeight = resolvedHeight
                        )
                    )
                }
            }
        }

        // 2. Subtitles & Captions
        if (captions.isNotEmpty()) {
            overlayList.add(SubtitleBitmapOverlay(captions, resolvedWidth, resolvedHeight))
        }

        // 3. Dynamic Graphics
        if (replacementOverlays.isNotEmpty() || trackingIndicators.isNotEmpty()) {
            overlayList.add(
                DynamicGraphicsOverlay(
                    replacements = replacementOverlays,
                    trackingIndicators = trackingIndicators,
                    targetWidth = resolvedWidth,
                    targetHeight = resolvedHeight
                )
            )
        }

        // 4. Text Cards
        if (textCards.isNotEmpty()) {
            overlayList.add(TextCardOverlay(textCards))
        }

        // Hook all overlays (clones, captions, graphics) directly into Track 1's GPU pipeline
        if (overlayList.isNotEmpty()) {
            sharedVideoEffects.add(OverlayEffect(ImmutableList.copyOf(overlayList)))
        }

        val shouldStripAudio = stripOriginalAudio
        val masterSequenceList = mutableListOf<EditedMediaItemSequence>()

        // Build Base Video Sequence (Track 1)
        val baseSequence = if (compositeLayers.isNotEmpty()) {
            val baseLayer = compositeLayers.firstOrNull { it.layerOrder == 0 }
            val firstOverlayLayer = compositeLayers.firstOrNull { it.layerOrder > 0 } ?: compositeLayers.first()
            val overlayDuration = firstOverlayLayer.getResolvedEndMs() - firstOverlayLayer.getResolvedStartMs()

            val baseStartMs = baseLayer?.getResolvedStartMs() ?: 0L
            val baseEndMs = baseLayer?.getResolvedEndMs()?.takeIf { it > baseStartMs }
                ?: if (overlayDuration > 0L) (baseStartMs + overlayDuration) else getVideoDurationMs(inputUri)

            val baseMediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(baseStartMs)
                        .setEndPositionMs(baseEndMs)
                        .build()
                )
                .build()

            val editedBaseItem = EditedMediaItem.Builder(baseMediaItem)
                .setRemoveAudio(shouldStripAudio)
                .setEffects(Effects(emptyList(), sharedVideoEffects))
                .build()

            EditedMediaItemSequence(editedBaseItem)
        } else if (speedRamps.isNotEmpty()) {
            buildSpeedRampedSequence(inputUri, shouldStripAudio, speedRamps, sharedVideoEffects)
        } else {
            val videoMediaItem = MediaItem.fromUri(inputUri)
            val editedVideoItem = EditedMediaItem.Builder(videoMediaItem)
                .setRemoveAudio(shouldStripAudio)
                .setEffects(Effects(emptyList(), sharedVideoEffects))
                .build()
            EditedMediaItemSequence(editedVideoItem)
        }
        masterSequenceList.add(baseSequence)

        // Audio Mixing: Injects Overlay Take Audio Track into the composition mixer
        if (compositeLayers.isNotEmpty() && overlaySourceUri != null && !shouldStripAudio) {
            val overlayAudioItem = MediaItem.fromUri(overlaySourceUri)
            val editedOverlayAudio = EditedMediaItem.Builder(overlayAudioItem)
                .setRemoveVideo(true)
                .build()
            masterSequenceList.add(EditedMediaItemSequence(editedOverlayAudio))
        }

        // Audio Mixing: Commentary Audio
        val isCommentaryAudioValid = commentaryAudioUri != null && try {
            val path = commentaryAudioUri.path ?: ""
            val f = File(path)
            f.exists() && f.length() > 0L
        } catch (_: Exception) {
            true
        }

        if (isCommentaryAudioValid && commentaryAudioUri != null) {
            val audioMediaItem = MediaItem.fromUri(commentaryAudioUri)
            val editedAudioItem = EditedMediaItem.Builder(audioMediaItem)
                .setRemoveVideo(true)
                .build()
            masterSequenceList.add(EditedMediaItemSequence(editedAudioItem))
        }

        val composition = Composition.Builder(masterSequenceList).build()
        runTransformer(composition, outputFile)
    }

    private fun buildSpeedRampedSequence(
        inputUri: Uri,
        stripAudio: Boolean,
        speedRamps: List<SpeedRampSpec>,
        sharedEffects: List<Effect>
    ): EditedMediaItemSequence {
        val totalDurationMs = getVideoDurationMs(inputUri)
        val sortedRamps = speedRamps.sortedBy { it.startTimeMs }
        val editedItems = mutableListOf<EditedMediaItem>()
        var cursorMs = 0L

        for (ramp in sortedRamps) {
            val rampStart = ramp.startTimeMs.coerceIn(0L, totalDurationMs)
            val rampEnd = ramp.endTimeMs.coerceIn(rampStart, totalDurationMs)

            if (rampStart > cursorMs) {
                val normalItem = createSegmentItem(inputUri, cursorMs, rampStart, 1.0f, stripAudio, sharedEffects)
                editedItems.add(normalItem)
            }

            if (rampEnd > rampStart) {
                val fastItem = createSegmentItem(inputUri, rampStart, rampEnd, ramp.speedMultiplier, stripAudio, sharedEffects)
                editedItems.add(fastItem)
            }

            cursorMs = rampEnd
        }

        if (cursorMs < totalDurationMs) {
            val trailingItem = createSegmentItem(inputUri, cursorMs, totalDurationMs, 1.0f, stripAudio, sharedEffects)
            editedItems.add(trailingItem)
        }

        if (editedItems.isEmpty()) {
            val fallbackItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                .setRemoveAudio(stripAudio)
                .setEffects(Effects(emptyList(), sharedEffects))
                .build()
            return EditedMediaItemSequence(fallbackItem)
        }

        return EditedMediaItemSequence(editedItems)
    }

    private fun createSegmentItem(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        speedMultiplier: Float,
        stripAudio: Boolean,
        sharedEffects: List<Effect>
    ): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        val segmentEffects = mutableListOf<Effect>()
        if (speedMultiplier != 1.0f && speedMultiplier > 0.0f) {
            segmentEffects.add(SpeedChangeEffect(speedMultiplier))
        }
        segmentEffects.addAll(sharedEffects)

        return EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(stripAudio)
            .setEffects(Effects(emptyList(), segmentEffects))
            .build()
    }

    private fun getVideoDimensions(uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920

            if (rotation == 90 || rotation == 270) {
                Pair(rawHeight, rawWidth)
            } else {
                Pair(rawWidth, rawHeight)
            }
        } catch (_: Exception) {
            Pair(1080, 1920)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun getVideoDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 60_000L
        } catch (_: Exception) {
            60_000L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private suspend fun runTransformer(
        editedMediaItem: EditedMediaItem,
        outputFile: File
    ): AppResult<File> {
        val sequence = EditedMediaItemSequence(editedMediaItem)
        val composition = Composition.Builder(listOf(sequence)).build()
        return runTransformer(composition, outputFile)
    }

    private suspend fun runTransformer(
        composition: Composition,
        outputFile: File
    ): AppResult<File> = suspendCancellableCoroutine { continuation ->
        var activeTransformer: Transformer? = null

        val listener = object : Transformer.Listener {
            override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                if (continuation.isActive) {
                    if (outputFile.exists() && outputFile.length() > 0L) {
                        continuation.resume(AppResult.Success(outputFile))
                    } else {
                        continuation.resume(
                            AppResult.Error(AppError.MediaProcessingError("Transformer finished but output file is empty (0 bytes)."))
                        )
                    }
                }
            }

            override fun onError(
                comp: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                if (continuation.isActive) {
                    val errorCode = exportException.errorCode
                    val errorCodeName = exportException.errorCodeName
                    val underlyingCause = exportException.cause?.message ?: "None"
                    val fullStackTrace = exportException.stackTraceToString()

                    val deepDiagnosticMessage = "Media3 Export Failed [$errorCodeName / Code: $errorCode]: " +
                            "${exportException.message} | Cause: $underlyingCause\nStackTrace: $fullStackTrace"

                    continuation.resume(
                        AppResult.Error(
                            AppError.MediaProcessingError(
                                message = deepDiagnosticMessage,
                                cause = exportException
                            )
                        )
                    )
                }
            }
        }

        try {
            activeTransformer = Transformer.Builder(context)
                .addListener(listener)
                .build()

            activeTransformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            if (continuation.isActive) {
                val fullStackTrace = e.stackTraceToString()
                continuation.resume(
                    AppResult.Error(
                        AppError.MediaProcessingError(
                            message = "Failed starting Media3 Transformer: ${e.message}\nStackTrace: $fullStackTrace",
                            cause = e
                        )
                    )
                )
            }
        }

        continuation.invokeOnCancellation {
            try {
                activeTransformer?.cancel()
            } catch (_: Exception) {}
        }
    }

    /**
     * Universal Video Clone Overlay.
     * Renders ML Kit's full-color foreground subject cutout directly onto Track 1's canvas.
     * Eliminates sudden disappearance by holding the final frame (freeze-frame hold) until the scene ends,
     * and anchors the vertical scale to the bottom baseline to eliminate floating gaps above the desk.
     */
    private class UniversalCloneBitmapOverlay(
        private val layerDto: RecipeCompositeLayerDto,
        private val maskBitmapProvider: (Long) -> Bitmap?,
        private val targetWidth: Int = 1080,
        private val targetHeight: Int = 1920
    ) : BitmapOverlay() {

        private val frameBitmap: Bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        private val canvas: Canvas = Canvas(frameBitmap)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dstRect = Rect(0, 0, targetWidth, targetHeight)
        private val durationUs: Long = (layerDto.getResolvedEndMs() - layerDto.getResolvedStartMs()) * 1000L

        // Holds the last valid cutout frame so the person stays naturally seated in the scene
        private var lastValidCutout: Bitmap? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            frameBitmap.eraseColor(Color.TRANSPARENT)

            // CapCut-style Freeze Frame Hold:
            // When the overlay take reaches its end earlier than the base scene (e.g. 5.0s < 7.5s),
            // clamp the lookup time to durationUs so it continues holding the seated actor in frame.
            val frameLookupUs = if (durationUs > 0L) {
                minOf(presentationTimeUs, durationUs)
            } else {
                presentationTimeUs
            }

            val cutoutPerson = maskBitmapProvider.invoke(frameLookupUs) ?: lastValidCutout

            if (cutoutPerson != null && !cutoutPerson.isRecycled) {
                lastValidCutout = cutoutPerson

                val maskDto = layerDto.mask
                val resolvedOpacity = layerDto.opacity.takeIf { it != 1.0f } ?: (maskDto?.opacity ?: 1.0f)
                val resolvedXOffset = if (layerDto.xOffset != 0.0f) layerDto.xOffset else (maskDto?.xOffset ?: 0.0f)
                val resolvedYOffset = if (layerDto.yOffset != 0.0f) layerDto.yOffset else (maskDto?.yOffset ?: 0.0f)
                val resolvedScale = if (layerDto.scale != 1.0f) layerDto.scale else (maskDto?.scale ?: 1.0f)

                canvas.save()

                val transX = resolvedXOffset * targetWidth
                val transY = resolvedYOffset * targetHeight
                if (transX != 0f || transY != 0f) {
                    canvas.translate(transX, transY)
                }

                // ANCHOR SCALE TO THE BOTTOM BASELINE:
                // Anchoring vertical scale to the bottom edge (targetHeight) keeps the person's torso
                // and arms firmly grounded on the table rather than floating into the air.
                if (resolvedScale != 1.0f && resolvedScale > 0.0f) {
                    canvas.scale(resolvedScale, resolvedScale, targetWidth / 2f, targetHeight.toFloat())
                }

                paint.alpha = (resolvedOpacity.coerceIn(0f, 1f) * 255).toInt()
                canvas.drawBitmap(cutoutPerson, null, dstRect, paint)
                canvas.restore()
            }

            return frameBitmap
        }
    }

    private class SubtitleBitmapOverlay(
        private val captions: List<Caption>,
        private val targetWidth: Int = 1080,
        private val targetHeight: Int = 1920
    ) : BitmapOverlay() {

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val backgroundPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val frameBitmap: Bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        private val canvas: Canvas = Canvas(frameBitmap)

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val currentSec = presentationTimeUs / 1_000_000.0
            frameBitmap.eraseColor(Color.TRANSPARENT)

            val activeCaption = captions.firstOrNull { currentSec >= it.start && currentSec <= it.end }
            if (activeCaption != null && activeCaption.text.isNotBlank()) {
                val posX = if (activeCaption.x > 1.0f) activeCaption.x / 100f else activeCaption.x
                val rawY = if (activeCaption.y > 1.0f) activeCaption.y / 100f else activeCaption.y
                val targetY = if (rawY in 0.75f..0.96f) 0.90f else rawY

                val x = targetWidth * posX.coerceIn(0.05f, 0.95f)
                val y = targetHeight * targetY.coerceIn(0.1f, 0.95f)

                val scaledFontSize = if (activeCaption.fontSizeSp > 0f) {
                    activeCaption.fontSizeSp * (targetWidth / 480f)
                } else {
                    54f
                }

                textPaint.textSize = scaledFontSize
                strokePaint.textSize = scaledFontSize

                val fontHex = activeCaption.fontColorHex
                if (!fontHex.isNullOrBlank()) {
                    try {
                        textPaint.color = Color.parseColor(fontHex)
                    } catch (_: Exception) {
                        textPaint.color = Color.WHITE
                    }
                } else {
                    textPaint.color = Color.WHITE
                }

                val maxTextWidth = targetWidth * 0.76f
                val words = activeCaption.text.split(" ")
                val lines = mutableListOf<String>()
                var currentLine = StringBuilder()

                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (textPaint.measureText(testLine) <= maxTextWidth) {
                        currentLine = StringBuilder(testLine)
                    } else {
                        if (currentLine.isNotEmpty()) {
                            lines.add(currentLine.toString())
                        }
                        currentLine = StringBuilder(word)
                    }
                }
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                if (lines.isEmpty()) {
                    lines.add(activeCaption.text)
                }

                val lineHeight = scaledFontSize * 1.25f
                val totalTextHeight = lines.size * lineHeight
                let {
                    val longestLineWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: maxTextWidth

                    val padH = 36f
                    val padV = 20f
                    val minConcealerWidth = targetWidth * 0.82f
                    val maskWidth = maxOf(longestLineWidth + (padH * 2), minConcealerWidth)

                    val pillRect = RectF(
                        x - (maskWidth / 2f),
                        y - (totalTextHeight / 2f) - padV,
                        x + (maskWidth / 2f),
                        y + (totalTextHeight / 2f) + padV
                    )

                    val bgHex = activeCaption.backgroundColorHex
                    backgroundPillPaint.color = try {
                        if (!bgHex.isNullOrBlank() && bgHex != "#00000000") {
                            Color.parseColor(bgHex)
                        } else {
                            Color.BLACK
                        }
                    } catch (_: Exception) {
                        Color.BLACK
                    }
                    backgroundPillPaint.alpha = 255

                    canvas.drawRoundRect(pillRect, 22f, 22f, backgroundPillPaint)

                    val startY = y - (totalTextHeight / 2f) + scaledFontSize * 0.85f
                    for ((lineIdx, lineText) in lines.withIndex()) {
                        val lineY = startY + (lineIdx * lineHeight)
                        canvas.drawText(lineText, x, lineY, strokePaint)
                        canvas.drawText(lineText, x, lineY, textPaint)
                    }
                }
            }

            return frameBitmap
        }
    }
}