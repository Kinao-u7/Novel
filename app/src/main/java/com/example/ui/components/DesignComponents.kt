@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.theme.AppTheme

data class CoverPalette(val c1: Color, val c2: Color, val accent: Color)

val COVER_PALETTES = listOf(
    CoverPalette(Color(0xFF17463D), Color(0xFF071013), Color(0xFFF3CB83)), // Jade
    CoverPalette(Color(0xFF4A2118), Color(0xFF12090A), Color(0xFFF08A6B)), // Sword
    CoverPalette(Color(0xFF5A2547), Color(0xFF170913), Color(0xFFFFC9DE)), // Plum
    CoverPalette(Color(0xFF124557), Color(0xFF06141A), Color(0xFF7CF2C3)), // Dragon
    CoverPalette(Color(0xFF1F2760), Color(0xFF080A1C), Color(0xFF9DB4FF)), // Star
    CoverPalette(Color(0xFF2F2D6C), Color(0xFF0E0D24), Color(0xFFD8D4FF)), // Moonwell
)

fun coverPaletteFor(title: String): CoverPalette {
    val hash = (title.hashCode() and 0x7FFFFFFF)
    return COVER_PALETTES[hash % COVER_PALETTES.size]
}

fun glyphForTitle(title: String): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "?"
    val firstChar = trimmed.first()
    val block = Character.UnicodeBlock.of(firstChar)
    val isCjk = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES
    return if (isCjk) firstChar.toString() else firstChar.uppercaseChar().toString()
}

/**
 * Shared book cover composable. If [coverUrl] is non-null/non-blank, loads image
 * with Coil; otherwise displays the deterministic procedural calligraphy gradient cover.
 */
@Composable
fun Cover(
    title: String,
    coverUrl: String? = null,
    modifier: Modifier = Modifier,
    tag: String? = null,
    glyphSize: TextUnit = 42.sp,
    titleSize: TextUnit = 10.sp,
    showTitle: Boolean = true,
) {
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 3.dp)
    val f = AppTheme.fonts
    val palette = coverPaletteFor(title)
    val glyph = glyphForTitle(title)

    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.linearGradient(listOf(palette.c1, palette.c2)))
            .drawBehind {
                drawRect(Color(0x24FFFFFF), size = Size(3.dp.toPx(), size.height))
                drawCircle(
                    Brush.radialGradient(
                        listOf(palette.accent.copy(alpha = 0.55f), Color.Transparent),
                        Offset(size.width / 2f, size.height * 0.34f),
                        size.width * 0.28f,
                    ),
                    radius = size.width * 0.28f,
                    center = Offset(size.width / 2f, size.height * 0.34f),
                )
            },
    ) {
        Text(
            text = glyph,
            color = palette.accent,
            fontFamily = f.brush,
            fontSize = glyphSize,
            style = TextStyle(shadow = Shadow(palette.accent, blurRadius = 24f)),
            modifier = Modifier
                .fillMaxWidth()
                .layout { m, cons ->
                    val p = m.measure(cons)
                    layout(p.width, cons.maxHeight) {
                        p.place(0, (cons.maxHeight * 0.34f - p.height / 2).toInt())
                    }
                },
            textAlign = TextAlign.Center,
        )

        if (showTitle) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 6.dp, bottom = 9.dp),
            ) {
                Box(Modifier.size(22.dp, 2.dp).background(palette.accent))
                Text(
                    text = title,
                    color = Color(0xFFF4EFE4),
                    fontFamily = f.heading,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = titleSize,
                    lineHeight = titleSize * 1.25f,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                    style = TextStyle(shadow = Shadow(Color(0x99000000), blurRadius = 8f)),
                )
            }
        }

        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (tag != null) {
            Text(
                text = tag,
                color = AppTheme.colors.go,
                fontFamily = f.ui,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    dashed: Boolean = false,
    onClick: () -> Unit,
) {
    val c = AppTheme.colors
    val color = if (selected) c.ac else c.mu
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(shape)
                .then(
                    if (dashed) {
                        Modifier.drawBehind {
                            drawRoundRect(
                                c.ln,
                                cornerRadius = CornerRadius(20.dp.toPx()),
                                style = Stroke(
                                    1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                                ),
                            )
                        }
                    } else {
                        Modifier.border(1.dp, if (selected) c.ac else c.ln, shape)
                    },
                )
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontFamily = AppTheme.fonts.ui,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun SquareIcon(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    dot: Boolean = false,
) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(shape)
                .border(1.dp, c.ln, shape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, desc, tint = c.tx, modifier = Modifier.size(20.dp))
            if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(8.dp)
                        .background(c.ac, CircleShape),
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = AppTheme.colors.mu,
        fontFamily = AppTheme.fonts.heading,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 1.8.sp,
    )
}

@Composable
fun DesignSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.library_search_hint),
) {
    val c = AppTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = { Text(placeholder, color = c.mu) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.cd,
            unfocusedContainerColor = c.cd,
            focusedBorderColor = c.ac,
            unfocusedBorderColor = c.ln,
            focusedTextColor = c.tx,
            unfocusedTextColor = c.tx,
            cursorColor = c.ac,
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
    )
}

@Composable
fun AppSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.cd,
        contentColor = c.tx,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            Modifier
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

@Composable
fun NewCategoryDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.cd,
        title = { Text(stringResource(R.string.library_new_category_title), color = c.tx) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_category_name_placeholder), color = c.mu) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.cd,
                    unfocusedContainerColor = c.cd,
                    focusedBorderColor = c.ac,
                    unfocusedBorderColor = c.ln,
                    focusedTextColor = c.tx,
                    unfocusedTextColor = c.tx,
                    cursorColor = c.ac,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onCreate(name)
                        onDismiss()
                    }
                },
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.library_create), color = c.ac, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.library_cancel), color = c.mu)
            }
        },
    )
}
