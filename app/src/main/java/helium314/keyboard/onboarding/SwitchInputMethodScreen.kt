// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.onboarding.components.CtaVariant
import helium314.keyboard.onboarding.components.GhostLink
import helium314.keyboard.onboarding.components.PrimaryCta
import helium314.keyboard.onboarding.components.StepRail

@Composable
fun SwitchInputMethodScreen(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val systemBarInsets = WindowInsets.systemBars.asPaddingValues()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val context = LocalContext.current
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    var imeCurrent by remember {
        mutableStateOf(UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The system IME picker is a dialog, so it never pauses this activity and
    // ON_RESUME doesn't fire when the user makes a selection. Watch the setting
    // directly so the CTA updates the moment SpeakKeys becomes the default.
    DisposableEffect(Unit) {
        val resolver = context.contentResolver
        val uri = Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                imeCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpeakKeysColors.Bg)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        SpeakKeysColors.BrandSoft.copy(alpha = 0.33f),
                        Color.Transparent,
                    ),
                    radius = 900f,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = statusBarTop + 28.dp,
                    start = 24.dp,
                    end = 24.dp,
                    bottom = systemBarInsets.calculateBottomPadding() + 16.dp,
                ),
        ) {
            StepRail(current = 3)
            Spacer(Modifier.height(28.dp))
            Hero(
                title = stringResource(R.string.onboarding_switch_title),
                body = stringResource(R.string.onboarding_switch_body),
            )
            Spacer(Modifier.height(24.dp))
            InputMethodSheetCard(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            val ctaLabel = stringResource(
                if (imeCurrent) R.string.onboarding_switch_cta_done
                else R.string.onboarding_switch_cta,
            )
            val ctaSublabel = stringResource(
                if (imeCurrent) R.string.onboarding_switch_cta_done_sub
                else R.string.onboarding_switch_cta_sub,
            )
            PrimaryCta(
                label = ctaLabel,
                sublabel = ctaSublabel,
                onClick = {
                    if (imeCurrent) onContinue()
                    else imm.showInputMethodPicker()
                },
                icon = {
                    Text(
                        text = "\u21C5",
                        style = SpeakKeysType.Button.copy(color = SpeakKeysColors.CtaTextOnBrand),
                    )
                },
                variant = CtaVariant.Brand,
                accessibilityLabel = ctaLabel,
            )
            Spacer(Modifier.height(6.dp))
            GhostLink(
                label = stringResource(R.string.onboarding_switch_skip),
                onClick = onSkip,
            )
        }
    }
}

@Composable
private fun Hero(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = SpeakKeysType.HeroSmall.copy(color = SpeakKeysColors.Fg),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = SpeakKeysType.Body,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

@Composable
private fun InputMethodSheetCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SpeakKeysColors.BgElev)
            .border(1.dp, SpeakKeysColors.Border, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        SheetHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_switch_sheet_header).uppercase(),
            style = SpeakKeysType.InputMethodSheetHeader,
        )
        Spacer(Modifier.height(10.dp))
        InputMethodRow(
            name = stringResource(R.string.onboarding_enable_row_gboard),
            selected = false,
            highlighted = false,
            iconPlaceholderColor = Color(0xFF4285F4),
        )
        Divider()
        InputMethodRow(
            name = stringResource(R.string.onboarding_enable_row_speakkeys),
            selected = true,
            highlighted = true,
            iconPainterResId = R.drawable.ic_speakkeys_logo,
            asideLabel = stringResource(R.string.onboarding_switch_aside_pick_me),
        )
        Divider()
        InputMethodRow(
            name = stringResource(R.string.onboarding_enable_row_samsung),
            selected = false,
            highlighted = false,
            iconPlaceholderColor = Color(0xFF1428A0),
        )
    }
}

@Composable
private fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(SpeakKeysColors.Border),
    )
}

@Composable
private fun Divider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SpeakKeysColors.Hairline),
    )
}

@Composable
private fun InputMethodRow(
    name: String,
    selected: Boolean,
    highlighted: Boolean,
    iconPainterResId: Int? = null,
    iconPlaceholderColor: Color? = null,
    asideLabel: String? = null,
) {
    val rowBg = if (highlighted) SpeakKeysColors.BgElev2 else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconPlaceholderColor ?: SpeakKeysColors.BrandSoft),
            contentAlignment = Alignment.Center,
        ) {
            if (iconPainterResId != null) {
                Image(
                    painter = painterResource(iconPainterResId),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = SpeakKeysType.InputMethodRowName.copy(color = SpeakKeysColors.Fg),
            modifier = Modifier.weight(1f),
        )
        if (asideLabel != null) {
            PickMeAside(label = asideLabel)
            Spacer(Modifier.width(8.dp))
        }
        RadioMark(selected = selected)
    }
}

@Composable
private fun PickMeAside(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = SpeakKeysType.Handwritten)
        Spacer(Modifier.width(4.dp))
        DottedArrow(modifier = Modifier.size(width = 28.dp, height = 18.dp))
    }
}

@Composable
private fun DottedArrow(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        val dotted = PathEffect.dashPathEffect(floatArrayOf(3f, 4f), 0f)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h * 0.85f)
            cubicTo(
                w * 0.2f, h * 0.1f,
                w * 0.6f, h * 0.1f,
                w * 0.85f, h * 0.55f,
            )
        }
        drawPath(
            path = path,
            color = SpeakKeysColors.BrandGlow,
            style = Stroke(width = stroke, pathEffect = dotted),
        )
        val tipX = w * 0.85f
        val tipY = h * 0.55f
        drawLine(
            color = SpeakKeysColors.BrandGlow,
            start = androidx.compose.ui.geometry.Offset(tipX, tipY),
            end = androidx.compose.ui.geometry.Offset(tipX - 6f, tipY - 4f),
            strokeWidth = stroke,
        )
        drawLine(
            color = SpeakKeysColors.BrandGlow,
            start = androidx.compose.ui.geometry.Offset(tipX, tipY),
            end = androidx.compose.ui.geometry.Offset(tipX - 6f, tipY + 4f),
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val borderColor = if (selected) SpeakKeysColors.Brand else SpeakKeysColors.Border
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(2.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(SpeakKeysColors.Brand),
            )
        }
    }
}
