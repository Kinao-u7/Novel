package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Standardized Material 3 Switch colors ensuring clean contrast in dark/light themes.
 * When OFF: renders cleanly with an outlined gray pill and gray thumb, never a solid black fill.
 * When ON: renders with the app's amber primary accent.
 */
@Composable
fun appSwitchColors(): SwitchColors {
    return SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = appSwitchColors()
    )
}
