package com.zakius.roma

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

// The few Material 3 pieces roma uses, under Material's names, built on Compose foundation: the library itself was
// 170 KB of dex for them. Same colour roles, type scale and Android 12+ dynamic colours; simpler motion.

class ColorScheme(
    val primary: Color, val onPrimary: Color, val inversePrimary: Color,
    val secondaryContainer: Color, val onSecondaryContainer: Color,
    val background: Color, val surface: Color, val onSurface: Color, val onSurfaceVariant: Color,
    val surfaceContainer: Color, val surfaceContainerLowest: Color, val surfaceContainerHigh: Color,
    val inverseSurface: Color, val inverseOnSurface: Color,
    val outline: Color, val outlineVariant: Color, val error: Color,
) {
    fun copy(
        primary: Color = this.primary, onPrimary: Color = this.onPrimary, inversePrimary: Color = this.inversePrimary,
        secondaryContainer: Color = this.secondaryContainer, onSecondaryContainer: Color = this.onSecondaryContainer,
        background: Color = this.background, surface: Color = this.surface,
        surfaceContainer: Color = this.surfaceContainer, surfaceContainerLowest: Color = this.surfaceContainerLowest,
    ) = ColorScheme(
        primary, onPrimary, inversePrimary, secondaryContainer, onSecondaryContainer, background, surface, onSurface, onSurfaceVariant,
        surfaceContainer, surfaceContainerLowest, surfaceContainerHigh, inverseSurface, inverseOnSurface, outline, outlineVariant, error,
    )
}

private fun c(argb: Long) = Color(argb)

fun lightColorScheme() = ColorScheme(
    c(0xFF6750A4), c(0xFFFFFFFF), c(0xFFD0BCFF), c(0xFFE8DEF8), c(0xFF1D192B), c(0xFFFEF7FF), c(0xFFFEF7FF), c(0xFF1D1B20), c(0xFF49454F),
    c(0xFFF3EDF7), c(0xFFFFFFFF), c(0xFFECE6F0), c(0xFF322F35), c(0xFFF5EFF7), c(0xFF79747E), c(0xFFCAC4D0), c(0xFFB3261E),
)

fun darkColorScheme() = ColorScheme(
    c(0xFFD0BCFF), c(0xFF381E72), c(0xFF6750A4), c(0xFF4A4458), c(0xFFE8DEF8), c(0xFF141218), c(0xFF141218), c(0xFFE6E0E9), c(0xFFCAC4D0),
    c(0xFF211F26), c(0xFF0F0D13), c(0xFF2B2930), c(0xFFE6E0E9), c(0xFF322F35), c(0xFF938F99), c(0xFF49454F), c(0xFFF2B8B5),
)

// The system's tonal palettes (Android 12+), by tone: _0 is tone 100, _500 tone 50, _1000 tone 0. Tones between two
// resources are blended.
@RequiresApi(31)
fun dynamicLightColorScheme(ctx: Context): ColorScheme {
    fun r(id: Int) = Color(ctx.getColor(id))
    val n1 = { id: Int -> r(id) }
    return ColorScheme(
        r(android.R.color.system_accent1_600), r(android.R.color.system_accent1_0), r(android.R.color.system_accent1_200),
        r(android.R.color.system_accent2_100), r(android.R.color.system_accent2_900),
        lerp(n1(android.R.color.system_neutral1_10), n1(android.R.color.system_neutral1_50), 0.25f),
        lerp(n1(android.R.color.system_neutral1_10), n1(android.R.color.system_neutral1_50), 0.25f),
        r(android.R.color.system_neutral1_900), r(android.R.color.system_neutral2_700),
        lerp(n1(android.R.color.system_neutral1_50), n1(android.R.color.system_neutral1_100), 0.2f), r(android.R.color.system_neutral1_0),
        lerp(n1(android.R.color.system_neutral1_50), n1(android.R.color.system_neutral1_100), 0.6f),
        r(android.R.color.system_neutral1_800), r(android.R.color.system_neutral1_50),
        r(android.R.color.system_neutral2_500), r(android.R.color.system_neutral2_200), c(0xFFB3261E),
    )
}

@RequiresApi(31)
fun dynamicDarkColorScheme(ctx: Context): ColorScheme {
    fun r(id: Int) = Color(ctx.getColor(id))
    val n900 = r(android.R.color.system_neutral1_900)
    val n800 = r(android.R.color.system_neutral1_800)
    val black = r(android.R.color.system_neutral1_1000)
    return ColorScheme(
        r(android.R.color.system_accent1_200), r(android.R.color.system_accent1_800), r(android.R.color.system_accent1_600),
        r(android.R.color.system_accent2_700), r(android.R.color.system_accent2_100),
        lerp(n900, black, 0.4f), lerp(n900, black, 0.4f),
        r(android.R.color.system_neutral1_100), r(android.R.color.system_neutral2_200),
        lerp(n900, n800, 0.2f), lerp(n900, black, 0.6f), lerp(n900, n800, 0.7f),
        r(android.R.color.system_neutral1_100), n800,
        r(android.R.color.system_neutral2_400), r(android.R.color.system_neutral2_700), c(0xFFF2B8B5),
    )
}

class Typography {
    private fun s(size: Int, line: Int, spacing: Float, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp, fontWeight = weight)
    val headlineSmall = s(24, 32, 0f)
    val titleLarge = s(22, 28, 0f)
    val titleMedium = s(16, 24, 0.15f, FontWeight.Medium)
    val titleSmall = s(14, 20, 0.1f, FontWeight.Medium)
    val bodyLarge = s(16, 24, 0.5f)
    val bodyMedium = s(14, 20, 0.25f)
    val bodySmall = s(12, 16, 0.4f)
    val labelLarge = s(14, 20, 0.1f, FontWeight.Medium)
    val labelMedium = s(12, 16, 0.5f, FontWeight.Medium)
    val labelSmall = s(11, 16, 0.5f, FontWeight.Medium)
}

private val LocalColors = staticCompositionLocalOf { lightColorScheme() }
private val defaultTypography = Typography()
val LocalContentColor = compositionLocalOf { Color.Black }
val LocalTextStyle = compositionLocalOf { defaultTypography.bodyLarge }

object MaterialTheme {
    val colorScheme: ColorScheme @Composable get() = LocalColors.current
    val typography: Typography get() = defaultTypography
}

@Composable
fun MaterialTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalColors provides colorScheme, LocalContentColor provides colorScheme.onSurface, content = content)

@Composable
private fun contentColorFor(bg: Color): Color {
    val s = MaterialTheme.colorScheme
    return when (bg) {
        s.primary -> s.onPrimary
        s.secondaryContainer -> s.onSecondaryContainer
        s.inverseSurface -> s.inverseOnSurface
        s.surface, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerLowest, s.background -> s.onSurface
        else -> LocalContentColor.current
    }
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val merged = style.merge(
        TextStyle(
            color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
            fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign ?: TextAlign.Unspecified, lineHeight = lineHeight,
        ),
    )
    BasicText(text, modifier, merged, onTextLayout, overflow, maxLines = maxLines)
}

@Composable
fun Text(text: AnnotatedString, modifier: Modifier = Modifier, onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current) =
    BasicText(text, modifier, style.merge(TextStyle(color = style.color.takeOrElse { LocalContentColor.current })), onTextLayout)

@Composable
fun Icon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(rememberVectorPainter(icon), contentDescription, modifier, tint)

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Image(painter, null, modifier.then(described).defaultMinSize(24.dp, 24.dp), colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint))
}

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val fg = contentColorFor(color)
    // Touches stop here, as on Material's Surface: nothing behind it reacts.
    Box(modifier.clip(shape).background(color).pointerInput(Unit) {}, propagateMinConstraints = true) {
        CompositionLocalProvider(LocalContentColor provides fg, content = content)
    }
}

@Composable
private fun ButtonBase(
    onClick: () -> Unit, modifier: Modifier, enabled: Boolean, bg: Color, fg: Color, padding: PaddingValues, content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.defaultMinSize(minHeight = 40.dp).clip(RoundedCornerShape(50)).background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.38f).padding(padding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides MaterialTheme.typography.labelLarge) { content() }
    }
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ButtonBase(onClick, modifier, enabled, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, PaddingValues(24.dp, 10.dp), content)

@Composable
fun TextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(12.dp, 8.dp), content: @Composable RowScope.() -> Unit,
) = ButtonBase(onClick, modifier, enabled, Color.Transparent, MaterialTheme.colorScheme.primary, contentPadding, content)

@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant, content = content)
    }
}

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val s = MaterialTheme.colorScheme
    val toggle = if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange) else Modifier
    Box(
        modifier.then(toggle).size(52.dp, 32.dp).alpha(if (enabled) 1f else 0.38f).clip(RoundedCornerShape(50))
            .background(if (checked) s.primary else s.surfaceContainerHigh).border(2.dp, if (checked) s.primary else s.outline, RoundedCornerShape(50)),
    ) {
        Box(
            Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = if (checked) 4.dp else 8.dp)
                .size(if (checked) 24.dp else 16.dp).clip(CircleShape).background(if (checked) s.onPrimary else s.outline),
        )
    }
}

@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val s = MaterialTheme.colorScheme
    val toggle = if (onCheckedChange != null) Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange) else Modifier
    Box(modifier.then(toggle).padding(11.dp).size(18.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            if (checked) {
                drawRoundRect(s.primary, cornerRadius = r)
                val w = size.width
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.72f); lineTo(w * 0.8f, w * 0.3f) }
                drawPath(path, s.onPrimary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Square))
            } else {
                drawRoundRect(s.onSurfaceVariant, cornerRadius = r, style = Stroke(2.dp.toPx()), topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()))
            }
        }
    }
}

@Composable
fun RadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val s = MaterialTheme.colorScheme
    val click = if (onClick != null) Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick) else Modifier
    Box(modifier.then(click).padding(12.dp).size(20.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val color = if (selected) s.primary else s.onSurfaceVariant
            drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (selected) drawCircle(color, radius = 5.dp.toPx())
        }
    }
}

/** A seek bar: drag or tap; [onValueChange] while moving, [onValueChangeFinished] when let go. */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val s = MaterialTheme.colorScheme
    var width by remember { mutableFloatStateOf(1f) }
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    fun at(x: Float) = range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val input = if (!enabled) Modifier else Modifier
        .pointerInput(Unit) { detectTapGestures { change(at(it.x)); finish?.invoke() } }
        .pointerInput(Unit) {
            var x = 0f
            detectHorizontalDragGestures(
                onDragStart = { x = it.x; change(at(x)) },
                onDragEnd = { finish?.invoke() },
                onDragCancel = { finish?.invoke() },
            ) { _, dx -> x += dx; change(at(x)) }
        }
    Canvas(
        modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.then(input)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                if (enabled) setProgress { v -> change(v.coerceIn(range.start, range.endInclusive)); finish?.invoke(); true }
            },
    ) {
        val y = size.height / 2
        val x = size.width * fraction
        val track = 4.dp.toPx()
        drawLine(if (enabled) s.secondaryContainer else s.outlineVariant, Offset(x, y), Offset(size.width, y), track, StrokeCap.Round)
        drawLine(if (enabled) s.primary else s.outline, Offset(0f, y), Offset(x, y), track, StrokeCap.Round)
        drawLine(if (enabled) s.primary else s.outline, Offset(x, y - 8.dp.toPx()), Offset(x, y + 8.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
fun TabRow(selectedTabIndex: Int, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surface), content = content)
}

@Composable
fun RowScope.Tab(selected: Boolean, onClick: () -> Unit, text: @Composable () -> Unit) {
    val s = MaterialTheme.colorScheme
    Box(Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) s.primary else s.onSurfaceVariant,
            LocalTextStyle provides MaterialTheme.typography.titleSmall,
            content = text,
        )
        if (selected) Box(Modifier.align(Alignment.BottomCenter).width(48.dp).height(3.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(s.primary))
    }
}

@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    val s = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.height(32.dp).clip(shape).then(if (selected) Modifier.background(s.secondaryContainer) else Modifier.border(1.dp, s.outlineVariant, shape))
            .selectable(selected, role = Role.Checkbox, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) s.onSecondaryContainer else s.onSurfaceVariant,
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
            content = label,
        )
    }
}

@Composable
fun CircularProgressIndicator() {
    val color = MaterialTheme.colorScheme.primary
    val spin by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
    Canvas(Modifier.size(40.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val w = 4.dp.toPx()
        drawArc(color, spin, 270f, false, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun HorizontalDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    title: @Composable () -> Unit = {},
    text: @Composable () -> Unit = {},
) {
    Dialog(onDismissRequest) {
        Surface(Modifier.widthIn(min = 280.dp, max = 560.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp)) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineSmall, content = title)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f, fill = false)) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                        content = text,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton(); confirmButton() }
            }
        }
    }
}

class SheetState
@Composable
fun rememberModalBottomSheetState(skipPartiallyExpanded: Boolean = true) = remember { SheetState() }

/** A menu from the bottom: tap outside, Back or a drag down closes it. Always fully open. */
@Composable
fun ModalBottomSheet(onDismissRequest: () -> Unit, sheetState: SheetState, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var drag by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismissRequest), contentAlignment = Alignment.BottomCenter) {
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().offset { IntOffset(0, drag.roundToInt()) }
                    .clickable(interactionSource = null, indication = null) {}
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(onDragEnd = { if (drag > 100.dp.toPx()) onDismissRequest() else drag = 0f }) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
                    },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                    Box(Modifier.padding(vertical = 22.dp).align(Alignment.CenterHorizontally).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    content()
                }
            }
        }
    }
}

/**
 * A text field: Android's EditText in an outlined box. Compose's own text field brings in about 120 KB of the APK (its
 * text input, selection and context menu); EditText is in the platform. Text set from outside (a picked search
 * suggestion) puts the caret at its end. [onSearch] gives the keyboard a Search key. [autoFocus]: focused, with the
 * keyboard up, as soon as it is on screen (a dialog that is only for typing).
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    onSearch: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val s = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    val change by rememberUpdatedState(onValueChange)
    val search by rememberUpdatedState(onSearch)
    var edit by remember { mutableStateOf<EditText?>(null) }
    // Made when the field is first on screen: an EditText made in a tab built hidden (buildAhead) stays blank, no
    // hint or caret, the first time the tab is shown.
    val shown = remember { BooleanArray(1) }
    if (LocalTabShown.current) shown[0] = true
    Column(
        modifier.defaultMinSize(minWidth = 280.dp, minHeight = 56.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) s.primary else s.outline, shape)
            // The whole box takes a tap, as Compose's field did, not only the line of text.
            .pointerInput(Unit) { detectTapGestures { edit?.let { it.requestFocus(); it.showKeyboard() } } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (label != null) Text(label, Modifier.clearAndSetSemantics {}, color = if (focused) s.primary else s.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        val style = MaterialTheme.typography.bodyLarge
        if (shown[0]) AndroidView(
            factory = { c ->
                EditText(c).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
                    letterSpacing = style.letterSpacing.value / style.fontSize.value
                    if (Build.VERSION.SDK_INT >= 28) lineHeight = (style.lineHeight.value * resources.displayMetrics.scaledDensity).toInt()
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                        (if (singleLine) 0 else android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                    if (singleLine) maxLines = 1
                    imeOptions = when {
                        onSearch != null -> android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        singleLine -> android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        else -> 0
                    }
                    // TalkBack reads the label with the field, as it did when the label was inside Compose's field.
                    if (label != null) accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.hintText = label
                        }
                    }
                    setOnEditorActionListener { _, action, _ ->
                        (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH).also { if (it) search?.invoke() }
                    }
                    setText(value)
                    setSelection(value.length)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(t: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(t: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(t: android.text.Editable) = change(t.toString())
                    })
                    // Focus from code (a new search) brings the keyboard up too, and leaving the field puts it away.
                    setOnFocusChangeListener { v, f -> focused = f; if (f) v.showKeyboard() else v.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0) }
                    edit = this
                    if (autoFocus) post { requestFocus() }
                }
            },
            Modifier.fillMaxWidth(),
            update = { e ->
                if (e.text.toString() != value) { e.setText(value); e.setSelection(value.length) }
                e.setTextColor(s.onSurface.toArgb())
                e.setHintTextColor(s.onSurfaceVariant.toArgb())
                e.hint = placeholder
                e.highlightColor = s.primary.copy(alpha = 0.4f).toArgb()
                if (Build.VERSION.SDK_INT >= 29) {
                    e.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(s.primary.toArgb()); setSize((2 * e.resources.displayMetrics.density).toInt(), 0)
                    }
                    listOf(e.textSelectHandle, e.textSelectHandleLeft, e.textSelectHandleRight).forEach { it?.setTint(s.primary.toArgb()) }
                }
            },
        )
    }
}

// Android ignores the request until the window has focus: a field focused as its dialog opens waits for that.
private fun View.showKeyboard() {
    val show = { post { context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) } }
    if (hasWindowFocus()) { show(); return }
    viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus) return
            viewTreeObserver.removeOnWindowFocusChangeListener(this)
            if (isFocused) show()
        }
    })
}
