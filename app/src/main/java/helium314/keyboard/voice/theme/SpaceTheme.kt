package helium314.keyboard.voice.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

internal val SpaceBackground = Color(0xFF050B16)
internal val SpaceBackgroundTop = Color(0xFF091326)
internal val SpaceBackgroundMid = Color(0xFF07111F)
internal val SpaceBackgroundBottom = Color(0xFF030812)

internal val SpacePanelTop = Color(0xFF182438)
internal val SpacePanelBottom = Color(0xFF0F1828)
internal val SpacePanelStrong = Color(0xFF1D2B41)
internal val SpaceFieldFill = Color(0xFF091320)
internal val SpaceOutline = Color(0x66E8F2FF)
internal val SpaceOutlineStrong = Color(0x99F0F6FF)

internal val SpaceTextPrimary = Color(0xFFF7FBFF)
internal val SpaceTextSecondary = Color(0xFFA3B0C7)
internal val SpaceAccentCyan = Color(0xFF16E5FF)
internal val SpaceAccentBlue = Color(0xFF5A7CFF)
internal val SpaceAccentPink = Color(0xFFFF6ADB)
internal val SpaceSuccessGreen = Color(0xFF2ED470)
internal val SpaceWarningAmber = Color(0xFFFFB457)
internal val SpaceErrorRed = Color(0xFFFF5E78)

internal val SpaceKeyColor = Color(0xFF22314A)
internal val SpaceSpecialKeyColor = Color(0xFF2A3850)

private val backdropStars = listOf(
    Offset(0.07f, 0.09f), Offset(0.12f, 0.18f), Offset(0.22f, 0.05f),
    Offset(0.28f, 0.14f), Offset(0.34f, 0.08f), Offset(0.41f, 0.18f),
    Offset(0.54f, 0.1f), Offset(0.6f, 0.22f), Offset(0.68f, 0.08f),
    Offset(0.74f, 0.16f), Offset(0.83f, 0.12f), Offset(0.9f, 0.2f),
    Offset(0.1f, 0.34f), Offset(0.2f, 0.28f), Offset(0.31f, 0.36f),
    Offset(0.46f, 0.31f), Offset(0.58f, 0.4f), Offset(0.71f, 0.33f),
    Offset(0.83f, 0.28f), Offset(0.94f, 0.38f), Offset(0.16f, 0.54f),
    Offset(0.29f, 0.62f), Offset(0.38f, 0.52f), Offset(0.51f, 0.58f),
    Offset(0.67f, 0.49f), Offset(0.78f, 0.61f), Offset(0.88f, 0.52f),
    Offset(0.08f, 0.78f), Offset(0.19f, 0.72f), Offset(0.35f, 0.82f),
    Offset(0.47f, 0.9f), Offset(0.59f, 0.76f), Offset(0.72f, 0.86f),
    Offset(0.84f, 0.76f), Offset(0.93f, 0.88f)
)

internal enum class SpaceMicVisualVariant {
    IDLE, LISTENING, WARNING, PROCESSING, ERROR
}

internal fun spaceMaterialColors() = darkColors(
    primary = SpaceAccentCyan,
    primaryVariant = SpaceAccentBlue,
    secondary = SpaceAccentBlue,
    background = SpaceBackground,
    surface = SpacePanelBottom,
    onPrimary = SpaceTextPrimary,
    onBackground = SpaceTextPrimary,
    onSurface = SpaceTextPrimary
)

internal fun spacePanelBrush(alpha: Float = 1f): Brush = Brush.linearGradient(
    colors = listOf(
        SpacePanelStrong.copy(alpha = alpha),
        SpacePanelTop.copy(alpha = alpha),
        SpacePanelBottom.copy(alpha = alpha)
    )
)

@Composable
internal fun SpaceBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.drawWithCache {
            val backgroundBrush = Brush.verticalGradient(
                colors = listOf(SpaceBackgroundTop, SpaceBackgroundMid, SpaceBackgroundBottom)
            )
            val nebulaLeft = Brush.radialGradient(
                colors = listOf(
                    SpaceAccentBlue.copy(alpha = 0.22f),
                    SpaceAccentCyan.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.18f, size.height * 0.12f),
                radius = size.minDimension * 0.62f
            )
            val nebulaRight = Brush.radialGradient(
                colors = listOf(
                    SpaceAccentPink.copy(alpha = 0.16f),
                    SpaceAccentBlue.copy(alpha = 0.1f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.82f, size.height * 0.68f),
                radius = size.minDimension * 0.54f
            )

            onDrawBehind {
                drawRect(brush = backgroundBrush)
                drawRect(brush = nebulaLeft)
                drawRect(brush = nebulaRight)

                backdropStars.forEachIndexed { index, star ->
                    val radius = size.minDimension * if (index % 5 == 0) 0.0048f else 0.0025f
                    drawCircle(
                        color = SpaceTextPrimary.copy(
                            alpha = if (index % 5 == 0) 0.72f else 0.4f
                        ),
                        radius = radius,
                        center = Offset(size.width * star.x, size.height * star.y)
                    )
                }
            }
        },
        content = content
    )
}

@Composable
internal fun SpacePanel(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.large,
    borderColor: Color = SpaceOutline,
    backgroundBrush: Brush = spacePanelBrush(),
    contentAlignment: Alignment = Alignment.Center,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundBrush)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
internal fun SpaceWordmark(
    modifier: Modifier = Modifier,
    text: String = "SPACE",
    fontSize: TextUnit = 22.sp,
    textAlign: TextAlign = TextAlign.Center
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = SpaceAccentCyan.copy(alpha = 0.18f),
            fontSize = (fontSize.value + 2f).sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
            textAlign = textAlign,
            modifier = Modifier.scale(1.05f)
        )
        Text(
            text = text,
            color = SpaceAccentCyan,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
            textAlign = textAlign
        )
    }
}

@Composable
internal fun SpaceControlIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = SpaceTextPrimary,
    glowColor: Color = SpaceAccentCyan,
    enabled: Boolean = true
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = glowColor.copy(alpha = if (enabled) 0.28f else 0.12f),
            modifier = Modifier.fillMaxSize().scale(1.12f)
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.fillMaxSize().padding(2.dp)
        )
    }
}

@Composable
internal fun SpaceMicVisual(
    variant: SpaceMicVisualVariant,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "spaceMic")
    val pulseScale = if (variant == SpaceMicVisualVariant.LISTENING || variant == SpaceMicVisualVariant.WARNING) {
        transition.animateFloat(
            initialValue = 1f, targetValue = 1.07f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900), repeatMode = RepeatMode.Reverse
            ), label = "micPulse"
        ).value
    } else 1f

    val orbitAngle = if (variant == SpaceMicVisualVariant.PROCESSING) {
        transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1350, easing = LinearEasing)
            ), label = "micOrbit"
        ).value
    } else 0f

    val sparkleAlpha = if (variant == SpaceMicVisualVariant.LISTENING || variant == SpaceMicVisualVariant.WARNING) {
        transition.animateFloat(
            initialValue = 0.25f, targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850), repeatMode = RepeatMode.Reverse
            ), label = "micSparkle"
        ).value
    } else 0f

    val ringColor = when (variant) {
        SpaceMicVisualVariant.IDLE -> SpaceAccentCyan
        SpaceMicVisualVariant.LISTENING -> SpaceAccentCyan
        SpaceMicVisualVariant.WARNING -> SpaceWarningAmber
        SpaceMicVisualVariant.PROCESSING -> SpaceAccentBlue
        SpaceMicVisualVariant.ERROR -> SpaceErrorRed
    }
    val haloColor = when (variant) {
        SpaceMicVisualVariant.WARNING -> SpaceWarningAmber
        SpaceMicVisualVariant.ERROR -> SpaceErrorRed
        else -> SpaceAccentCyan
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = size.minDimension * 0.47f * pulseScale
            val ringRadius = size.minDimension * 0.36f * pulseScale
            val ringWidth = size.minDimension * 0.055f
            val coreRadius = size.minDimension * 0.27f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        haloColor.copy(alpha = when (variant) {
                            SpaceMicVisualVariant.IDLE -> 0.12f
                            SpaceMicVisualVariant.PROCESSING -> 0.18f
                            SpaceMicVisualVariant.ERROR -> 0.2f
                            else -> 0.32f
                        }),
                        Color.Transparent
                    ),
                    center = center, radius = outerRadius * 1.35f
                ),
                radius = outerRadius * 1.35f, center = center
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SpacePanelStrong, SpacePanelBottom, SpaceBackground),
                    center = center, radius = coreRadius * 1.8f
                ),
                radius = coreRadius * 1.45f, center = center
            )

            when (variant) {
                SpaceMicVisualVariant.PROCESSING -> {
                    drawCircle(
                        color = SpaceOutline.copy(alpha = 0.2f),
                        radius = ringRadius, center = center,
                        style = Stroke(width = ringWidth)
                    )
                    drawArc(
                        color = ringColor, startAngle = orbitAngle, sweepAngle = 138f,
                        useCenter = false,
                        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                        size = Size(ringRadius * 2f, ringRadius * 2f),
                        style = Stroke(width = ringWidth, cap = StrokeCap.Round)
                    )
                    val angleRad = Math.toRadians((orbitAngle + 138f).toDouble())
                    val dotCenter = Offset(
                        x = center.x + cos(angleRad).toFloat() * ringRadius,
                        y = center.y + sin(angleRad).toFloat() * ringRadius
                    )
                    drawCircle(color = SpaceAccentCyan, radius = ringWidth * 0.45f, center = dotCenter)
                }
                else -> {
                    drawCircle(
                        color = ringColor.copy(
                            alpha = if (variant == SpaceMicVisualVariant.IDLE) 0.45f else 0.96f
                        ),
                        radius = ringRadius, center = center,
                        style = Stroke(width = ringWidth, cap = StrokeCap.Round)
                    )
                }
            }

            if (sparkleAlpha > 0f) {
                drawCircle(
                    color = SpaceTextPrimary.copy(alpha = sparkleAlpha),
                    radius = ringWidth * 0.26f,
                    center = Offset(center.x + ringRadius * 0.52f, center.y - ringRadius * 0.88f)
                )
                drawCircle(
                    color = SpaceAccentBlue.copy(alpha = sparkleAlpha * 0.8f),
                    radius = ringWidth * 0.18f,
                    center = Offset(center.x + ringRadius * 0.7f, center.y - ringRadius * 0.66f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize(0.62f)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(SpacePanelTop, SpacePanelBottom, SpaceBackground)
                    )
                )
                .border(
                    width = 1.dp,
                    color = ringColor.copy(alpha = if (variant == SpaceMicVisualVariant.IDLE) 0.35f else 0.5f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            SpaceControlIcon(
                icon = icon,
                tint = if (variant == SpaceMicVisualVariant.ERROR) SpaceTextPrimary else MaterialTheme.colors.onBackground,
                glowColor = ringColor,
                modifier = Modifier.fillMaxSize(0.42f)
            )
        }
    }
}
