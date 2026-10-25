package com.duckreader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * How the page is set: how big the words are, what they are printed on, how far apart the lines sit,
 * how much room the page keeps at its edges, and the face it is written in. Plus night dimming,
 * which is not part of the page at all — it takes the light out of the screen once the page is
 * drawn, below what the phone's own brightness setting offers, which is the whole point of it in a
 * dark room. It dims by taking the screen's brightness down rather than by laying a film over the
 * page: a film would end up inside the copy a page turn holds, and the page being left would be
 * dimmed twice over while the page arriving was dimmed once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSheet(
    fontSize: Int, paperIndex: Int, spacingIndex: Int, insetIndex: Int, fontIndex: Int, dim: Int,
    onSize: (Int) -> Unit, onPaper: (Int) -> Unit, onSpacing: (Int) -> Unit, onInset: (Int) -> Unit,
    onFont: (Int) -> Unit, onDim: (Int) -> Unit, onDimPreview: (Int) -> Unit = {}, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Reading appearance", style = MaterialTheme.typography.headlineSmall)
                Text("Make the page comfortable. Your place stays with the text.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Text size", Modifier.weight(1f))
                    OutlinedButton(onClick = { onSize((fontSize - 2).coerceAtLeast(14)) }, enabled = fontSize > 14,
                        modifier = Modifier.semantics { contentDescription = "Smaller text" }) { Text("A−") }
                    Text("$fontSize", Modifier.padding(horizontal = 16.dp))
                    OutlinedButton(onClick = { onSize((fontSize + 2).coerceAtMost(32)) }, enabled = fontSize < 32,
                        modifier = Modifier.semantics { contentDescription = "Larger text" }) { Text("A+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    papers.forEachIndexed { index, paper ->
                        FilterChip(selected = paperIndex == index, onClick = { onPaper(index) },
                            label = { Text(paper.name) }, leadingIcon = {
                                Box(Modifier.size(12.dp).clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(paper.background))))
                            })
                    }
                }
                ChipRow("Line spacing", ReadingTypography.spacingNames, spacingIndex, onSpacing)
                ChipRow("Margins", ReadingTypography.insetNames, insetIndex, onInset)
                ChipRow("Typeface", ReadingTypography.fontNames, fontIndex, onFont)
                NightDimming(dim, onDimPreview, onDim)
                Text("Night dimming takes the phone's own brightness down, below the setting the phone " +
                    "offers, for reading in the dark. The page, the controls and everything else dim " +
                    "together, so nothing glares.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            // Always within reach, however many choices the sheet is holding.
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp)) { Text("Done") }
        }
    }
}

/**
 * How far the screen is taken down, on a slider rather than in steps: dimming is a matter of taste
 * and of the room, and four presets only ever fit someone who is not standing in it.
 *
 * The screen follows the finger, because the dimming is the thing being chosen and a choice you
 * cannot see is not a choice. What is *written down* waits for the finger to lift, so a drag writes
 * one value instead of sixty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightDimming(dim: Int, onPreview: (Int) -> Unit, onChange: (Int) -> Unit) {
    var level by remember { mutableFloatStateOf(dim.toFloat()) }
    val percent = level.roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Night dimming", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Text(if (percent < 1) "Off" else "$percent %",
                color = if (percent < 1) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = level,
            onValueChange = { level = it; onPreview(it.roundToInt()) },
            onValueChangeFinished = { onChange(level.roundToInt()) },
            valueRange = 0f..maxNightDim.toFloat(),
            // The handle is the sun the app already uses for light, rather than the bar Material
            // draws by default.
            // A filled disc in the track's own colour, so a low value reads as one amber bar running
            // into its handle instead of a bar that vanished behind an icon.
            thumb = {
                Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_daylight), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                }
            },
            // Material's own track keeps the handle's width clear at both ends, which hides the
            // first few percent of amber underneath the handle. This one starts at the real edge,
            // so 7 % looks like 7 % instead of nothing.
            track = {
                val fraction = (level / maxNightDim.toFloat()).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
                    if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                }
            },
            modifier = Modifier.semantics { contentDescription = "Night dimming" })
    }
}

/**
 * One line of choices, named, with the one in force picked out. The chips wrap rather than squeeze:
 * five choices with long names do not fit across a phone, and a chip cut in half is no choice at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, names: List<String>, selected: Int, onChoose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { index, name ->
                FilterChip(selected = selected == index, onClick = { onChoose(index) }, label = { Text(name) })
            }
        }
    }
}
