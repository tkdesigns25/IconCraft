package com.iconcraft.studio.lab

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.palette.graphics.Palette
import com.iconcraft.studio.model.InstalledApp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ProceduralStyle(
    val title: String,
    val subtitle: String
) {
    POSTER_TEXTURE(
        title = "Style A — Poster Texture",
        subtitle = "Cropped photo backdrop + monochrome ~65% foreground silhouette"
    ),
    NEON_GLOW(
        title = "Style B — Neon Glow",
        subtitle = "Dark muted backdrop + BlurMaskFilter vibrant neon outline/tint"
    ),
    MINIMAL_DUOTONE(
        title = "Style C — Minimal Duotone",
        subtitle = "Flat solid palette backdrop + crisp contrasting tinted foreground"
    )
}

data class ExtractedThemePalette(
    val dominantColor: Int = 0xFF1E293B.toInt(),
    val vibrantColor: Int = 0xFF6366F1.toInt(),
    val darkMutedColor: Int = 0xFF0D0D14.toInt(),
    val lightVibrantColor: Int = 0xFFE0E7FF.toInt()
)

object ProceduralThemeEngine {

    /**
     * Extracts `dominantColor`, `vibrantColor`, `darkMutedColor`, and `lightVibrantColor`
     * from the user's inspiration image using `androidx.palette:palette-ktx`.
     */
    fun extractPalette(bitmap: Bitmap): ExtractedThemePalette {
        val palette = Palette.from(bitmap).maximumColorCount(24).generate()
        val dominant = palette.getDominantColor(0xFF1E293B.toInt())
        val vibrant = palette.getVibrantColor(
            palette.getLightVibrantColor(dominant)
        )
        val darkMuted = palette.getDarkMutedColor(
            palette.getDarkVibrantColor(0xFF0D0D14.toInt())
        )
        val lightVibrant = palette.getLightVibrantColor(
            palette.getLightMutedColor(0xFFF8FAFC.toInt())
        )
        return ExtractedThemePalette(
            dominantColor = dominant,
            vibrantColor = ensureBrightNeon(vibrant),
            darkMutedColor = darkenIfNeeded(darkMuted),
            lightVibrantColor = ensureHighContrastLight(lightVibrant)
        )
    }

    /**
     * Extracts the foreground glyph/silhouette of an installed app:
     * - If `AdaptiveIconDrawable`, extracts `adaptiveIcon.foreground` cleanly without the background box.
     * - If legacy `BitmapDrawable`, strips the outer background via luminance/saturation + perimeter color matching.
     */
    fun extractAppForegroundGlyph(
        context: Context,
        app: InstalledApp,
        targetSize: Int = 256
    ): Bitmap {
        val rawForeground = runCatching {
            val pm = context.packageManager
            val drawable = pm.getActivityIcon(ComponentName(app.packageName, app.activityName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
                val fg = drawable.foreground
                if (fg != null) {
                    val bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    // AdaptiveIconDrawable foregrounds have an 18% outer safe-zone margin;
                    // inset bounds slightly outward so the central 72dp glyph fills targetSize cleanly.
                    val inset = -(targetSize * 0.16f).toInt()
                    fg.setBounds(inset, inset, targetSize - inset, targetSize - inset)
                    fg.draw(canvas)
                    bmp
                } else null
            } else {
                null
            }
        }.getOrNull()

        if (rawForeground != null && hasTransparentPerimeter(rawForeground)) {
            return normalizeForegroundAlphaMask(rawForeground)
        }

        // Legacy icon or full-bleed adaptive foreground: strip perimeter background color & extract luminance/saturation silhouette
        val fallbackSource = rawForeground
            ?: app.iconBitmap?.let { Bitmap.createScaledBitmap(it, targetSize, targetSize, true) }
            ?: Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)

        return stripLegacyBackgroundToSilhouette(fallbackSource)
    }

    /**
     * Renders a single app icon in the chosen `ProceduralStyle` at `outputSize` (e.g. 192 for live preview, 512 for APK export).
     */
    fun renderProceduralIcon(
        inspirationBitmap: Bitmap?,
        palette: ExtractedThemePalette,
        foregroundGlyph: Bitmap,
        style: ProceduralStyle,
        outputSize: Int = 512,
        invertSilhouette: Boolean = false
    ): Bitmap {
        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val rect = RectF(0f, 0f, outputSize.toFloat(), outputSize.toFloat())
        val cornerRadius = outputSize * 0.22f
        val clipPath = Path().apply {
            addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        // Foreground glyph target box (~65% size centered on canvas)
        val glyphBoxSize = outputSize * 0.65f
        val glyphOffset = (outputSize - glyphBoxSize) / 2f
        val glyphDestRect = RectF(
            glyphOffset,
            glyphOffset,
            glyphOffset + glyphBoxSize,
            glyphOffset + glyphBoxSize
        )

        val scaledGlyph = if (foregroundGlyph.width != glyphBoxSize.toInt()) {
            Bitmap.createScaledBitmap(
                foregroundGlyph,
                glyphBoxSize.toInt().coerceAtLeast(1),
                glyphBoxSize.toInt().coerceAtLeast(1),
                true
            )
        } else {
            foregroundGlyph
        }

        when (style) {
            ProceduralStyle.POSTER_TEXTURE -> {
                // 1. Draw center-cropped inspiration photo as background
                if (inspirationBitmap != null && inspirationBitmap.width > 0 && inspirationBitmap.height > 0) {
                    val scale = max(
                        outputSize.toFloat() / inspirationBitmap.width.toFloat(),
                        outputSize.toFloat() / inspirationBitmap.height.toFloat()
                    )
                    val matrix = Matrix().apply {
                        postTranslate(-inspirationBitmap.width / 2f, -inspirationBitmap.height / 2f)
                        postScale(scale, scale)
                        postTranslate(outputSize / 2f, outputSize / 2f)
                    }
                    canvas.drawBitmap(inspirationBitmap, matrix, paint)

                    // Subtle contrast scrim so the monochrome silhouette is always legible
                    val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (invertSilhouette) {
                            Color.argb(85, 255, 255, 255)
                        } else {
                            Color.argb(105, 8, 10, 18)
                        }
                    }
                    canvas.drawRect(rect, scrimPaint)
                } else {
                    paint.color = palette.dominantColor
                    canvas.drawRect(rect, paint)
                }

                // 2. Subtle drop shadow under the monochrome silhouette
                val shadowColor = if (invertSilhouette) {
                    Color.argb(90, 255, 255, 255)
                } else {
                    Color.argb(140, 0, 0, 0)
                }
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(shadowColor, PorterDuff.Mode.SRC_IN)
                    maskFilter = BlurMaskFilter(outputSize * 0.025f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawBitmap(scaledGlyph, glyphDestRect.left, glyphDestRect.top + (outputSize * 0.012f), shadowPaint)

                // 3. Clean monochrome (white or dark) silhouette at ~65% size on top
                val silhouetteColor = if (invertSilhouette) {
                    0xFF0D0D14.toInt()
                } else {
                    0xFFF8FAFC.toInt()
                }
                val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(silhouetteColor, PorterDuff.Mode.SRC_IN)
                }
                canvas.drawBitmap(scaledGlyph, glyphDestRect.left, glyphDestRect.top, fgPaint)
            }

            ProceduralStyle.NEON_GLOW -> {
                // 1. Dark background (darkMutedColor or #0D0D0D) + subtle radial neon aura
                val baseBg = if (invertSilhouette) 0xFF0D0D0D.toInt() else palette.darkMutedColor
                paint.color = baseBg
                canvas.drawRect(rect, paint)

                val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        outputSize / 2f,
                        outputSize / 2f,
                        outputSize * 0.55f,
                        intArrayOf(
                            withAlpha(palette.vibrantColor, 72),
                            withAlpha(palette.vibrantColor, 0)
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(rect, auraPaint)

                // 2. Outer Neon Bloom using vibrantColor + BlurMaskFilter
                val outerBlurRadius = (outputSize * 0.065f).coerceAtLeast(4f)
                val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(palette.vibrantColor, PorterDuff.Mode.SRC_IN)
                    maskFilter = BlurMaskFilter(outerBlurRadius, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawBitmap(scaledGlyph, glyphDestRect.left, glyphDestRect.top, outerGlowPaint)

                val innerBlurRadius = (outputSize * 0.025f).coerceAtLeast(2f)
                val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(palette.vibrantColor, PorterDuff.Mode.SRC_IN)
                    maskFilter = BlurMaskFilter(innerBlurRadius, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawBitmap(scaledGlyph, glyphDestRect.left, glyphDestRect.top, innerGlowPaint)

                // 3. Crisp bright neon core tint on top
                val coreColor = blendColors(palette.vibrantColor, Color.WHITE, 0.55f)
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(coreColor, PorterDuff.Mode.SRC_IN)
                }
                canvas.drawBitmap(scaledGlyph, glyphDestRect.left, glyphDestRect.top, corePaint)

                // 4. Subtle neon border rim
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = outputSize * 0.016f
                    color = withAlpha(palette.vibrantColor, 110)
                }
                canvas.drawRoundRect(
                    RectF(
                        borderPaint.strokeWidth,
                        borderPaint.strokeWidth,
                        outputSize - borderPaint.strokeWidth,
                        outputSize - borderPaint.strokeWidth
                    ),
                    cornerRadius,
                    cornerRadius,
                    borderPaint
                )
            }

            ProceduralStyle.MINIMAL_DUOTONE -> {
                // 1. Flat solid background (dominantColor or darkMutedColor)
                val bgColor = if (invertSilhouette) palette.dominantColor else palette.darkMutedColor
                val fgColor = if (invertSilhouette) palette.darkMutedColor else palette.lightVibrantColor

                val bgShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        outputSize.toFloat(),
                        outputSize.toFloat(),
                        bgColor,
                        blendColors(bgColor, palette.dominantColor, 0.28f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(rect, bgShaderPaint)

                // 2. Crisp contrasting tinted icon foreground at ~65% size
                val duotonePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN)
                }
                canvas.drawBitmap(scaledGlyph, glyphDestRect.left, glyphDestRect.top, duotonePaint)
            }
        }

        canvas.restoreToCount(saveCount)
        return out
    }

    private fun hasTransparentPerimeter(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return false
        var transparentCount = 0
        var totalSamples = 0
        val margin = (w * 0.08f).toInt().coerceAtLeast(2)

        for (x in 0 until w step 4) {
            totalSamples += 2
            if (Color.alpha(bmp.getPixel(x, margin)) < 40) transparentCount++
            if (Color.alpha(bmp.getPixel(x, h - 1 - margin)) < 40) transparentCount++
        }
        for (y in 0 until h step 4) {
            totalSamples += 2
            if (Color.alpha(bmp.getPixel(margin, y)) < 40) transparentCount++
            if (Color.alpha(bmp.getPixel(w - 1 - margin, y)) < 40) transparentCount++
        }
        return transparentCount.toFloat() / totalSamples.coerceAtLeast(1) > 0.45f
    }

    /**
     * Converts an extracted adaptive foreground into a crisp alpha-weighted glyph so multi-color
     * inner details (like inner cutouts inside a logo) remain distinct instead of becoming a flat blob.
     */
    private fun normalizeForegroundAlphaMask(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Check if the foreground has internal color contrast (e.g., white glyph inside colored circle)
        var opaqueCount = 0
        var lumSum = 0f
        for (px in pixels) {
            val a = Color.alpha(px)
            if (a > 128) {
                opaqueCount++
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                lumSum += (0.299f * r + 0.587f * g + 0.114f * b)
            }
        }

        val fillRatio = opaqueCount.toFloat() / (w * h).toFloat()
        // If the foreground is already a clean cutout symbol (< 48% of canvas area), keep its alpha + luminance shading
        if (fillRatio < 0.48f) {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (i in pixels.indices) {
                val px = pixels[i]
                val a = Color.alpha(px)
                if (a < 20) {
                    pixels[i] = 0
                } else {
                    pixels[i] = Color.argb(a, 255, 255, 255)
                }
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }

        // Otherwise strip the dominant outer color of the foreground to reveal the inner symbol
        return stripLegacyBackgroundToSilhouette(src)
    }

    /**
     * Strips the background of a legacy `BitmapDrawable` or full-bleed icon using ColorMatrix
     * luminance/saturation + perimeter background color distance, producing a clean alpha glyph.
     */
    private fun stripLegacyBackgroundToSilhouette(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Sample ring at ~16% inset from edges to find the icon's background box color
        val ringInset = (min(w, h) * 0.16f).toInt().coerceAtLeast(2)
        val samples = ArrayList<Int>()
        for (x in ringInset until (w - ringInset) step 4) {
            samples.add(pixels[ringInset * w + x])
            samples.add(pixels[(h - 1 - ringInset) * w + x])
        }
        for (y in ringInset until (h - ringInset) step 4) {
            samples.add(pixels[y * w + ringInset])
            samples.add(pixels[y * w + (w - 1 - ringInset)])
        }

        var bgR = 0L
        var bgG = 0L
        var bgB = 0L
        var validSamples = 0
        for (c in samples) {
            if (Color.alpha(c) > 100) {
                bgR += Color.red(c)
                bgG += Color.green(c)
                bgB += Color.blue(c)
                validSamples++
            }
        }
        if (validSamples > 0) {
            bgR /= validSamples
            bgG /= validSamples
            bgB /= validSamples
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = min(w, h) * 0.44f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val px = pixels[idx]
                val a = Color.alpha(px)
                if (a < 25) {
                    outPixels[idx] = 0
                    continue
                }

                val dx = x - cx
                val dy = y - cy
                val distFromCenter = sqrt(dx * dx + dy * dy)
                if (distFromCenter > maxRadius) {
                    outPixels[idx] = 0
                    continue
                }

                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)

                val colorDist = sqrt(
                    ((r - bgR) * (r - bgR) + (g - bgG) * (g - bgG) + (b - bgB) * (b - bgB)).toDouble()
                ).toFloat()

                val alphaFactor = ((colorDist - 26f) / 70f).coerceIn(0f, 1f)
                val edgeFade = ((maxRadius - distFromCenter) / (maxRadius * 0.12f)).coerceIn(0f, 1f)
                val finalAlpha = (a * alphaFactor * edgeFade).toInt().coerceIn(0, 255)

                outPixels[idx] = if (finalAlpha > 18) {
                    Color.argb(finalAlpha, 255, 255, 255)
                } else {
                    0
                }
            }
        }

        // Fallback if the icon had a complex photo background where colorDist stripped too much:
        // use high-contrast ColorMatrix luminance thresholding
        var survivingPixels = 0
        for (px in outPixels) {
            if (Color.alpha(px) > 64) survivingPixels++
        }
        if (survivingPixels < (w * h) / 40) {
            val lumBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(lumBmp)
            val cm = ColorMatrix().apply { setSaturation(0f) }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(src, 0f, 0f, p)
            lumBmp.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val px = pixels[i]
                val a = Color.alpha(px)
                val lum = Color.red(px)
                val contrastAlpha = ((abs(lum - 128) * 2).coerceIn(40, 255) * a) / 255
                outPixels[i] = Color.argb(contrastAlpha, 255, 255, 255)
            }
        }

        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun ensureBrightNeon(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.65f)
        hsv[2] = hsv[2].coerceAtLeast(0.88f)
        return Color.HSVToColor(hsv)
    }

    private fun darkenIfNeeded(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2].coerceAtMost(0.18f)
        return Color.HSVToColor(hsv)
    }

    private fun ensureHighContrastLight(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2].coerceAtLeast(0.90f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(c1) * inv + Color.red(c2) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(c1) * inv + Color.green(c2) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }
}
