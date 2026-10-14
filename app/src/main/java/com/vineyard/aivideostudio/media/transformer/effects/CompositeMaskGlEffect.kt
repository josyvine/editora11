package com.vineyard.aivideostudio.media.transformer.effects

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleFrameGlShaderProgram

/**
 * High-performance GPU-accelerated OpenGL ES 2.0 Effect for Media3 Transformer.
 * Performs dynamic feathered masking for clones, traveling mattes, wipes,
 * and universal AI Subject Cutout (CapCut-style Auto Background Removal) directly on hardware surfaces.
 */
@UnstableApi
class CompositeMaskGlEffect(
    val maskType: MaskType = MaskType.AI_SUBJECT_MATTE,
    val startSplitPos: Float = 0.5f,
    val endSplitPos: Float = 0.5f,
    val feather: Float = 0.04f,
    val isInverted: Boolean = false,
    val rectBounds: FloatArray = floatArrayOf(0f, 0f, 1f, 1f), // xMin, yMin, xMax, yMax
    val startTimeUs: Long = 0L,
    val endTimeUs: Long = Long.MAX_VALUE,
    val opacity: Float = 1.0f,
    val xOffset: Float = 0.0f,
    val yOffset: Float = 0.0f,
    val scale: Float = 1.0f,
    val maskBitmapProvider: ((Long) -> Bitmap?)? = null // AI Cutout Matte Provider per frame
) : GlEffect {

    enum class MaskType(val typeId: Float) {
        LINEAR_HORIZONTAL(0.0f), // Top vs Bottom split
        LINEAR_VERTICAL(1.0f),   // Left vs Right split
        RECTANGLE(2.0f),         // Bounding Box matte
        RADIAL_CIRCLE(3.0f),     // Circular spotlight / portal matte
        AI_SUBJECT_MATTE(4.0f)   // Universal CapCut-style AI background removal (body cutout)
    }

    @OptIn(UnstableApi::class)
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CompositeMaskShaderProgram(context, useHdr, this)
    }
}

@UnstableApi
internal class CompositeMaskShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val effect: CompositeMaskGlEffect
) : SingleFrameGlShaderProgram(useHdr) {

    private val glProgram: GlProgram
    private var maskTexId: Int = 0

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoords;
            void main() {
                gl_Position = aFramePosition;
                vTexCoords = (aFramePosition.xy + vec2(1.0, 1.0)) * 0.5;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoords;
            uniform sampler2D uTexSampler;
            uniform sampler2D uMaskSampler;
            uniform float uMaskType;
            uniform float uSplitPos;
            uniform float uFeather;
            uniform float uInvert;
            uniform float uIsActive;
            uniform vec4 uRectBounds;
            uniform float uHasMaskTex;
            uniform vec2 uOffset;
            uniform float uScale;
            uniform float uOpacity;

            void main() {
                if (uIsActive < 0.5) {
                    gl_FragColor = texture2D(uTexSampler, vTexCoords);
                    return;
                }

                // Universal 2D Coordinate Transformation (Scale around center + Offset position)
                vec2 coords = (vTexCoords - vec2(0.5, 0.5)) / max(uScale, 0.0001) + vec2(0.5, 0.5) - uOffset;

                // Handle out-of-bounds regions for transformed clone overlay
                if (coords.x < 0.0 || coords.x > 1.0 || coords.y < 0.0 || coords.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
                    return;
                }

                vec4 baseColor = texture2D(uTexSampler, coords);
                float alpha = 1.0;
                float feather = max(uFeather, 0.0001);

                if (uMaskType < 0.5) {
                    // Linear Horizontal Split
                    alpha = smoothstep(uSplitPos - feather, uSplitPos + feather, coords.y);
                } else if (uMaskType < 1.5) {
                    // Linear Vertical Split
                    alpha = smoothstep(uSplitPos - feather, uSplitPos + feather, coords.x);
                } else if (uMaskType < 2.5) {
                    // Rectangle Box
                    float left = smoothstep(uRectBounds.x - feather, uRectBounds.x + feather, coords.x);
                    float right = 1.0 - smoothstep(uRectBounds.z - feather, uRectBounds.z + feather, coords.x);
                    float top = smoothstep(uRectBounds.y - feather, uRectBounds.y + feather, coords.y);
                    float bottom = 1.0 - smoothstep(uRectBounds.w - feather, uRectBounds.w + feather, coords.y);
                    alpha = left * right * top * bottom;
                } else if (uMaskType < 3.5) {
                    // Radial Circle
                    float dist = distance(coords, vec2(0.5, 0.5));
                    alpha = 1.0 - smoothstep(uSplitPos - feather, uSplitPos + feather, dist);
                } else {
                    // Universal AI Subject Matte Cutout (Foreground Separation)
                    if (uHasMaskTex > 0.5) {
                        vec4 maskVal = texture2D(uMaskSampler, coords);
                        // Accommodates grayscale luminance masks (R=G=B), alpha masks (A), or binary mattes
                        alpha = max(maskVal.r, max(maskVal.g, max(maskVal.b, maskVal.a)));
                    } else {
                        alpha = 1.0;
                    }
                }

                if (uInvert > 0.5) {
                    alpha = 1.0 - alpha;
                }

                float finalAlpha = baseColor.a * alpha * clamp(uOpacity, 0.0, 1.0);
                gl_FragColor = vec4(baseColor.rgb * finalAlpha, finalAlpha);
            }
        """
    }

    init {
        glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )

        // Generate standard OpenGL texture handle for the AI cutout mask
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        maskTexId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val isActive = presentationTimeUs in effect.startTimeUs..effect.endTimeUs

        val currentSplitPos = if (isActive && effect.endTimeUs > effect.startTimeUs) {
            val progress = ((presentationTimeUs - effect.startTimeUs).toFloat() /
                    (effect.endTimeUs - effect.startTimeUs).toFloat()).coerceIn(0f, 1f)
            effect.startSplitPos + (effect.endSplitPos - effect.startSplitPos) * progress
        } else {
            effect.startSplitPos
        }

        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.setSamplerTexIdUniform("uMaskSampler", maskTexId, 1)

        glProgram.setFloatUniform("uMaskType", effect.maskType.typeId)
        glProgram.setFloatUniform("uSplitPos", currentSplitPos)
        glProgram.setFloatUniform("uFeather", effect.feather)
        glProgram.setFloatUniform("uInvert", if (effect.isInverted) 1.0f else 0.0f)
        glProgram.setFloatUniform("uIsActive", if (isActive) 1.0f else 0.0f)
        glProgram.setFloatsUniform("uRectBounds", effect.rectBounds)
        glProgram.setFloatsUniform("uOffset", floatArrayOf(effect.xOffset, effect.yOffset))
        glProgram.setFloatUniform("uScale", effect.scale)
        glProgram.setFloatUniform("uOpacity", effect.opacity)

        // Upload AI Cutout Alpha Mask Texture if available for this frame
        if (effect.maskType == CompositeMaskGlEffect.MaskType.AI_SUBJECT_MATTE && effect.maskBitmapProvider != null) {
            val maskBitmap = effect.maskBitmapProvider.invoke(presentationTimeUs)
            if (maskBitmap != null && !maskBitmap.isRecycled) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, maskBitmap, 0)
                glProgram.setFloatUniform("uHasMaskTex", 1.0f)
            } else {
                glProgram.setFloatUniform("uHasMaskTex", 0.0f)
            }
        } else {
            glProgram.setFloatUniform("uHasMaskTex", 0.0f)
        }

        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        if (maskTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(maskTexId), 0)
            maskTexId = 0
        }
        try {
            glProgram.delete()
        } catch (_: Exception) {}
    }
}