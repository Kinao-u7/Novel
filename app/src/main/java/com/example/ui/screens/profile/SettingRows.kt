package com.example.ui.screens.profile

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.roundToInt

/** One setting row. Dimmed + inert when row.enabled == false. [first] hides the top divider. */
@Composable
internal fun SettingRowItem(
    row: SettingRow,
    first: Boolean,
    actions: ProfileActions,
    onActionRow: (ActionRow) -> Unit
) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().alpha(if (row.enabled) 1f else 0.4f)) {
        if (!first) HorizontalDivider(color = c.ln)
        when (row) {
            is ToggleRow -> LineRow(
                row.title, row.subtitle,
                Modifier.toggleable(value = row.checked, enabled = row.enabled, role = Role.Switch) {
                    actions.onToggle(row.key, it)
                }
            ) { XSwitch(row.checked) }

            is SegmentedRow -> StackRow(row.title, row.subtitle) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.options.forEach { o ->
                        Pill(o.label, o.id == row.selectedId, row.enabled, Modifier.weight(1f), RoundedCornerShape(12.dp)) {
                            actions.onSelect(row.key, o.id)
                        }
                    }
                }
            }

            is ChipsRow -> StackRow(row.title, row.subtitle) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(row.options, key = { it.id }) { o ->
                        Pill(o.label, o.id == row.selectedId, row.enabled, Modifier, CircleShape) {
                            actions.onSelect(row.key, o.id)
                        }
                    }
                }
            }

            is SliderRow -> StackRow(row.title, row.subtitle, row.valueLabel) {
                var v by remember(row.value) { mutableFloatStateOf(row.value) }
                val steps = (((row.max - row.min) / row.step).roundToInt() - 1).coerceAtLeast(0)
                Slider(
                    value = v,
                    onValueChange = { v = it; actions.onSlide(row.key, it) },
                    enabled = row.enabled,
                    valueRange = row.min..row.max,
                    steps = steps,
                    colors = SliderDefaults.colors(
                        thumbColor = c.ac, activeTrackColor = c.ac, inactiveTrackColor = c.ln,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier.semantics {
                        row.valueLabel?.let { stateDescription = it }
                    }
                )
            }

            is SwatchRow -> StackRow(row.title, row.subtitle) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.options.forEach { o ->
                        val sel = o.id == row.selectedId
                        val base = Color(o.color)
                        val acc = o.accent?.let { Color(it) }
                        Box(
                            Modifier.size(48.dp)
                                .border(3.dp, if (sel) c.go.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
                                .padding(3.dp)
                                .selectable(selected = sel, enabled = row.enabled, role = Role.RadioButton) {
                                    actions.onSelect(row.key, o.id)
                                }
                                .semantics { contentDescription = o.label }
                        ) {
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape)
                                    .background(
                                        if (acc != null) Brush.linearGradient(0f to base, 0.55f to base, 0.56f to acc, 1f to acc)
                                        else SolidColor(base)
                                    )
                                    .border(2.dp, if (sel) c.go else c.ln, CircleShape)
                            )
                        }
                    }
                }
            }

            is TimeRow -> {
                var show by remember { mutableStateOf(false) }
                val is24 = DateFormat.is24HourFormat(LocalContext.current)
                LineRow(row.title, row.subtitle, Modifier.clickable(enabled = row.enabled, role = Role.Button) { show = true }) {
                    Text(formatTime(row.minutesOfDay, is24), color = c.ac, fontFamily = AppTheme.fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    XIcon(XIcons.Chevron, c.mu, 16.dp)
                }
                if (show) TimeDialog(row.title, row.minutesOfDay, is24, { show = false }) {
                    actions.onTimeChosen(row.key, it); show = false
                }
            }

            is StorageRow -> StorageBlock(row)

            is TextPreviewRow -> Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 14.dp)) {
                val text = buildAnnotatedString {
                    if (row.sample.isNotEmpty()) {
                        withStyle(SpanStyle(fontFamily = AppTheme.fonts.heading, fontWeight = FontWeight.SemiBold, color = c.go, fontSize = (row.fontSizeSp * 2.1f).sp)) {
                            append(row.sample.first())
                        }
                        append(row.sample.drop(1))
                    }
                }
                Text(
                    text, color = c.tx, fontFamily = FontFamily.Serif,
                    fontSize = row.fontSizeSp.sp, lineHeight = (row.fontSizeSp * row.lineSpacing).sp,
                    textAlign = if (row.justified) TextAlign.Justify else TextAlign.Start,
                    modifier = Modifier.semantics { contentDescription = row.title }
                )
            }

            is ActionRow -> LineRow(
                row.title, row.subtitle,
                Modifier.clickable(enabled = row.enabled, role = Role.Button) { onActionRow(row) },
                danger = row.isDanger
            ) {
                row.trailing?.let {
                    Text(it, color = c.ac, fontFamily = AppTheme.fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                if (!row.isDanger) XIcon(XIcons.Chevron, c.mu, 16.dp)
            }
        }
    }
}

@Composable
private fun LineRow(
    title: String, subtitle: String?, modifier: Modifier = Modifier, danger: Boolean = false,
    trailing: @Composable RowScope.() -> Unit
) {
    val c = AppTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 2.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) MaterialTheme.colorScheme.error else c.tx, fontFamily = AppTheme.fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = c.mu, fontFamily = AppTheme.fonts.ui, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun StackRow(title: String, subtitle: String?, valueLabel: String? = null, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = c.tx, fontFamily = AppTheme.fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (subtitle != null) Text(subtitle, color = c.mu, fontFamily = AppTheme.fonts.ui, fontSize = 12.sp)
            }
            if (valueLabel != null) Text(valueLabel, color = c.ac, fontFamily = AppTheme.fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        content()
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, shape: Shape, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        modifier.heightIn(min = 40.dp).clip(shape)
            .background(if (selected) c.ln else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) c.ac else c.ln), shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) c.ac else c.mu, fontFamily = AppTheme.fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun XSwitch(checked: Boolean) {
    val c = AppTheme.colors
    val track by animateColorAsState(if (checked) c.ac else c.ln, label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 3.dp, label = "thumb")
    Box(Modifier.size(48.dp, 29.dp).clip(CircleShape).background(track)) {
        Box(Modifier.offset(x, 3.dp).size(23.dp).clip(CircleShape).background(c.tx))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageBlock(row: StorageRow) {
    val c = AppTheme.colors
    val total = row.chaptersBytes + row.coversBytes + row.cacheBytes
    val chaptersLabel = stringResource(R.string.profile_storage_chapters)
    val coversLabel = stringResource(R.string.profile_storage_covers)
    val cacheLabel = stringResource(R.string.profile_storage_cache)
    val parts = listOf(
        Triple(chaptersLabel, row.chaptersBytes, c.ac),
        Triple(coversLabel, row.coversBytes, c.go),
        Triple(cacheLabel, row.cacheBytes, c.mu)
    )
    StackRow(row.title, row.subtitle, formatBytes(total)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(10.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (total <= 0L) Box(Modifier.fillMaxSize().background(c.ln))
            else parts.forEach { (_, b, col) ->
                if (b > 0) Box(Modifier.weight(b.toFloat()).fillMaxSize().background(col))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            parts.forEach { (name, b, col) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                    Spacer(Modifier.width(6.dp))
                    Text("$name ${formatBytes(b)}", color = c.mu, fontFamily = AppTheme.fonts.ui, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minutes: Int, is24: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = AppTheme.colors
    val st = rememberTimePickerState(minutes / 60, minutes % 60, is24)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = c.cd) {
            Column(Modifier.padding(20.dp)) {
                Text(title, color = c.tx, fontFamily = AppTheme.fonts.heading, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                Spacer(Modifier.height(16.dp))
                TimePicker(
                    state = st,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.ln, clockDialSelectedContentColor = c.on, clockDialUnselectedContentColor = c.tx,
                        selectorColor = c.ac, containerColor = c.cd, periodSelectorBorderColor = c.ln,
                        periodSelectorSelectedContainerColor = c.ac.copy(alpha = 0.2f),
                        periodSelectorUnselectedContainerColor = Color.Transparent,
                        periodSelectorSelectedContentColor = c.ac, periodSelectorUnselectedContentColor = c.mu,
                        timeSelectorSelectedContainerColor = c.ac.copy(alpha = 0.2f),
                        timeSelectorUnselectedContainerColor = c.ln,
                        timeSelectorSelectedContentColor = c.ac, timeSelectorUnselectedContentColor = c.tx
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.profile_cancel), color = c.mu) }
                    TextButton({ onPick(st.hour * 60 + st.minute) }) { Text(stringResource(R.string.profile_time_set), color = c.ac, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

internal fun formatTime(minutesOfDay: Int, is24: Boolean): String {
    val h = (minutesOfDay / 60) % 24
    val m = minutesOfDay % 60
    if (is24) return String.format(Locale.getDefault(), "%02d:%02d", h, m)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return String.format(Locale.getDefault(), "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
}
