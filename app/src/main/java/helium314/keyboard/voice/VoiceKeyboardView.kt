package helium314.keyboard.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.voice.theme.Shapes
import helium314.keyboard.voice.theme.SpaceAccentBlue
import helium314.keyboard.voice.theme.SpaceAccentCyan
import helium314.keyboard.voice.theme.SpaceBackdrop
import helium314.keyboard.voice.theme.SpaceControlIcon
import helium314.keyboard.voice.theme.SpaceErrorRed
import helium314.keyboard.voice.theme.SpaceMicVisual
import helium314.keyboard.voice.theme.SpaceMicVisualVariant
import helium314.keyboard.voice.theme.SpaceOutline
import helium314.keyboard.voice.theme.SpaceOutlineStrong
import helium314.keyboard.voice.theme.SpacePanel
import helium314.keyboard.voice.theme.SpaceTextPrimary
import helium314.keyboard.voice.theme.SpaceTextSecondary
import helium314.keyboard.voice.theme.SpaceWarningAmber
import helium314.keyboard.voice.theme.SpaceWordmark
import helium314.keyboard.voice.theme.spaceMaterialColors
import helium314.keyboard.voice.theme.spacePanelBrush
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class VoiceKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private val prefs by speakKeysPreferenceModel()

    var state by mutableIntStateOf(STATE_INITIAL)
    var errorMessage by mutableStateOf(context.getString(R.string.mic_info_error))
    var actionLabel by mutableStateOf(context.getString(R.string.ime_action_enter))
    var actionVisual by mutableStateOf(EnterActionVisual.ENTER)

    private var listener: Listener? = null

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    @Composable
    override fun Content() {
        val configuration = LocalConfiguration.current
        val keyboardHeightFraction = when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> prefs.keyboardHeightLandscape.get()
            else -> prefs.keyboardHeightPortrait.get()
        }
        val panelHeight = (configuration.screenHeightDp * keyboardHeightFraction)
            .toInt()
            .coerceAtLeast(240)
            .dp

        val controlsEnabled = state != STATE_LISTENING &&
            state != STATE_PROCESSING &&
            state != STATE_LIMIT_WARNING

        MaterialTheme(
            colors = spaceMaterialColors(),
            shapes = Shapes
        ) {
            SpaceBackdrop(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    DragHandle(
                        onClick = { (context as? InputMethodService)?.requestHideSelf(0) }
                    )

                    SymbolsBar(
                        symbols = VOICE_SYMBOLS,
                        enabled = controlsEnabled,
                        onSymbolPress = { listener?.buttonClicked(it) }
                    )

                    MicArea(
                        state = state,
                        errorMessage = errorMessage,
                        onMicPressStart = { listener?.micPressStart() },
                        onMicPressEnd = { listener?.micPressEnd() },
                        onErrorClick = { listener?.settingsClicked() }
                    )

                    BottomBar(
                        enabled = controlsEnabled,
                        actionLabel = actionLabel,
                        actionVisual = actionVisual,
                        onToggleMode = { listener?.toggleKeyboardMode() },
                        onInsertSpace = { listener?.buttonClicked(" ") },
                        onCursorLeft = { listener?.cursorLeftClicked() },
                        onCursorRight = { listener?.cursorRightClicked() },
                        onBackspace = { listener?.backspaceClicked() },
                        onSettings = { listener?.settingsClicked() },
                        onEnter = { listener?.enterClicked() }
                    )
                }
            }
        }
    }

    enum class EnterActionVisual { ENTER, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

    interface Listener {
        fun micPressStart()
        fun micPressEnd()
        fun backspaceClicked()
        fun settingsClicked()
        fun buttonClicked(text: String)
        fun toggleKeyboardMode()
        fun cursorLeftClicked()
        fun cursorRightClicked()
        fun enterClicked()
    }

    companion object {
        private val VOICE_SYMBOLS = listOf("?", "!", ",", ".", "\"", "(", ")", "-", ":", ";", "'", "/", "@")

        const val STATE_INITIAL = 0
        const val STATE_LOADING = 1
        const val STATE_READY = 2
        const val STATE_LISTENING = 3
        const val STATE_PAUSED = 4
        const val STATE_ERROR = 5
        const val STATE_PROCESSING = 6
        const val STATE_LIMIT_WARNING = 7
    }
}

@Composable
private fun DragHandle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(SpaceOutlineStrong.copy(alpha = 0.82f))
        )
    }
}

@Composable
private fun SymbolsBar(
    symbols: List<String>,
    enabled: Boolean,
    onSymbolPress: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(symbols) { symbol ->
            SpacePanel(
                modifier = Modifier
                    .width(46.dp)
                    .height(40.dp)
                    .clickable(enabled = enabled) { onSymbolPress(symbol) }
                    .alpha(if (enabled) 1f else 0.5f),
                shape = RoundedCornerShape(20.dp),
                borderColor = if (enabled) {
                    SpaceOutlineStrong.copy(alpha = 0.8f)
                } else {
                    SpaceOutline.copy(alpha = 0.24f)
                },
                backgroundBrush = spacePanelBrush(alpha = if (enabled) 0.92f else 0.56f)
            ) {
                Text(
                    text = symbol,
                    color = SpaceTextPrimary.copy(alpha = if (enabled) 0.96f else 0.42f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MicArea(
    state: Int,
    errorMessage: String,
    onMicPressStart: () -> Unit,
    onMicPressEnd: () -> Unit,
    onErrorClick: () -> Unit
) {
    val visualVariant = micVisualVariant(state)
    val icon = micIcon(state)
    val statusColor = when (visualVariant) {
        SpaceMicVisualVariant.IDLE -> SpaceTextSecondary
        SpaceMicVisualVariant.LISTENING -> SpaceAccentCyan
        SpaceMicVisualVariant.WARNING -> SpaceWarningAmber
        SpaceMicVisualVariant.PROCESSING -> SpaceAccentBlue
        SpaceMicVisualVariant.ERROR -> SpaceErrorRed
    }

    val statusText = when (state) {
        VoiceKeyboardView.STATE_LOADING -> stringResource(id = R.string.mic_info_preparing)
        VoiceKeyboardView.STATE_INITIAL,
        VoiceKeyboardView.STATE_READY,
        VoiceKeyboardView.STATE_PAUSED -> stringResource(id = R.string.mic_info_hold_to_talk)
        VoiceKeyboardView.STATE_LISTENING -> stringResource(id = R.string.mic_info_release_to_send)
        VoiceKeyboardView.STATE_LIMIT_WARNING -> stringResource(id = R.string.mic_info_release_soon)
        VoiceKeyboardView.STATE_PROCESSING -> stringResource(id = R.string.mic_info_processing)
        else -> errorMessage
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center
    ) {
        val micSize = (maxHeight * 0.50f).coerceAtMost(140.dp)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .size(micSize)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onMicPressStart()
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    onMicPressEnd()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                SpaceMicVisual(
                    variant = visualVariant,
                    icon = icon,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = statusText.uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                textAlign = TextAlign.Center,
                color = statusColor.copy(alpha = 0.96f),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .then(
                        if (state == VoiceKeyboardView.STATE_ERROR) {
                            Modifier.clickable(onClick = onErrorClick)
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}

@Composable
private fun BottomBar(
    enabled: Boolean,
    actionLabel: String,
    actionVisual: VoiceKeyboardView.EnterActionVisual,
    onToggleMode: () -> Unit,
    onInsertSpace: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit,
    onBackspace: () -> Unit,
    onSettings: () -> Unit,
    onEnter: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlButton(
            icon = Icons.Default.Keyboard,
            enabled = enabled,
            accentColor = SpaceAccentCyan,
            onClick = onToggleMode
        )

        SpaceBar(
            enabled = enabled,
            onInsertSpace = onInsertSpace,
            onCursorLeft = onCursorLeft,
            onCursorRight = onCursorRight,
            modifier = Modifier.weight(1f)
        )

        BackspaceButton(
            enabled = enabled,
            onBackspace = onBackspace
        )

        ControlButton(
            icon = Icons.Default.Settings,
            enabled = enabled,
            accentColor = SpaceAccentBlue,
            onClick = onSettings
        )

        ActionButton(
            label = actionLabel,
            visual = actionVisual,
            enabled = enabled,
            onClick = onEnter
        )
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    enabled: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SpacePanel(
        modifier = modifier
            .size(52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        borderColor = if (enabled) SpaceOutline.copy(alpha = 0.82f) else SpaceOutline.copy(alpha = 0.28f),
        backgroundBrush = spacePanelBrush(alpha = if (enabled) 0.95f else 0.56f)
    ) {
        SpaceControlIcon(
            icon = icon,
            tint = SpaceTextPrimary,
            glowColor = accentColor,
            enabled = enabled,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun SpaceBar(
    enabled: Boolean,
    onInsertSpace: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dragStepPx = with(LocalDensity.current) { 18.dp.toPx() }
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }

    SpacePanel(
        modifier = modifier
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onInsertSpace)
            .alpha(if (enabled) 1f else 0.52f),
        shape = RoundedCornerShape(16.dp),
        borderColor = if (enabled) {
            SpaceOutlineStrong.copy(alpha = 0.86f)
        } else {
            SpaceOutline.copy(alpha = 0.26f)
        },
        backgroundBrush = spacePanelBrush(alpha = if (enabled) 0.98f else 0.58f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { accumulatedDrag = 0f },
                        onDragCancel = { accumulatedDrag = 0f },
                        onDragEnd = { accumulatedDrag = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        accumulatedDrag += dragAmount

                        while (abs(accumulatedDrag) >= dragStepPx) {
                            if (accumulatedDrag > 0f) {
                                onCursorRight()
                                accumulatedDrag -= dragStepPx
                            } else {
                                onCursorLeft()
                                accumulatedDrag += dragStepPx
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            SpaceWordmark(
                text = "SPACE",
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun BackspaceButton(
    enabled: Boolean,
    onBackspace: () -> Unit
) {
    SpacePanel(
        modifier = Modifier
            .size(52.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        var down = true
                        coroutineScope {
                            val repeatJob = launch {
                                delay(Constants.BackspaceRepeatStartDelay)
                                var repeatDelay = Constants.BackspaceRepeatDelay
                                while (down) {
                                    onBackspace()
                                    delay(repeatDelay)
                                    repeatDelay = (repeatDelay * 85 / 100).coerceAtLeast(20)
                                }
                            }
                            launch {
                                tryAwaitRelease()
                                down = false
                                repeatJob.cancel()
                            }
                        }
                    },
                    onTap = { onBackspace() }
                )
            },
        shape = RoundedCornerShape(16.dp),
        borderColor = if (enabled) SpaceOutline.copy(alpha = 0.82f) else SpaceOutline.copy(alpha = 0.28f),
        backgroundBrush = spacePanelBrush(alpha = if (enabled) 0.95f else 0.56f)
    ) {
        SpaceControlIcon(
            icon = Icons.Default.Backspace,
            tint = SpaceTextPrimary,
            glowColor = SpaceAccentCyan,
            enabled = enabled,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    visual: VoiceKeyboardView.EnterActionVisual,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SpacePanel(
        modifier = Modifier
            .size(52.dp)
            .semantics {
                contentDescription = label
            }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        borderColor = if (enabled) SpaceOutline.copy(alpha = 0.82f) else SpaceOutline.copy(alpha = 0.28f),
        backgroundBrush = spacePanelBrush(alpha = if (enabled) 0.95f else 0.56f)
    ) {
        SpaceControlIcon(
            icon = enterActionIcon(visual),
            tint = SpaceTextPrimary,
            glowColor = SpaceAccentBlue,
            enabled = enabled,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun enterActionIcon(visual: VoiceKeyboardView.EnterActionVisual): ImageVector {
    return when (visual) {
        VoiceKeyboardView.EnterActionVisual.ENTER -> Icons.Default.KeyboardReturn
        VoiceKeyboardView.EnterActionVisual.GO -> Icons.Default.ArrowForward
        VoiceKeyboardView.EnterActionVisual.SEARCH -> Icons.Default.Search
        VoiceKeyboardView.EnterActionVisual.SEND -> Icons.Default.Send
        VoiceKeyboardView.EnterActionVisual.NEXT -> Icons.Default.ArrowForward
        VoiceKeyboardView.EnterActionVisual.DONE -> Icons.Default.Check
        VoiceKeyboardView.EnterActionVisual.PREVIOUS -> Icons.Default.ArrowBack
    }
}

private fun micVisualVariant(state: Int): SpaceMicVisualVariant {
    return when (state) {
        VoiceKeyboardView.STATE_LISTENING -> SpaceMicVisualVariant.LISTENING
        VoiceKeyboardView.STATE_LIMIT_WARNING -> SpaceMicVisualVariant.WARNING
        VoiceKeyboardView.STATE_LOADING,
        VoiceKeyboardView.STATE_PROCESSING -> SpaceMicVisualVariant.PROCESSING
        VoiceKeyboardView.STATE_ERROR -> SpaceMicVisualVariant.ERROR
        else -> SpaceMicVisualVariant.IDLE
    }
}

private fun micIcon(state: Int): ImageVector {
    return when (state) {
        VoiceKeyboardView.STATE_INITIAL,
        VoiceKeyboardView.STATE_READY,
        VoiceKeyboardView.STATE_PAUSED -> Icons.Default.MicNone
        VoiceKeyboardView.STATE_ERROR -> Icons.Default.MicOff
        else -> Icons.Default.Mic
    }
}
