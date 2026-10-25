package com.duckreader.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App-wide preferences, laid out the way the shelf is: a heading, then one sheet carrying sections of
 * rows — a drawing, a name, and the one control that changes it. Text size and paper stay in the
 * reader's Aa sheet, where they can be judged against the page they change.
 */
@Composable
internal fun SettingsScreen(
    store: EpubStore,
    onBack: () -> Unit,
    backingUp: Boolean = false,
    restoring: Boolean = false,
    darkTheme: Boolean = false,
    onDarkTheme: (Boolean) -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    /** The reader's own folders, which the shelf scans for books to read where they lie. */
    folders: List<SavedFolder> = emptyList(),
    scanningFolders: Boolean = false,
    setAside: Int = 0,
    onAddFolder: () -> Unit = {},
    onRemoveFolder: (SavedFolder) -> Unit = {},
    onRestoreSetAside: () -> Unit = {},
    onScanFolders: () -> Unit = {},
    /** Kept for callers that show Settings with a way back of its own. */
    @Suppress("UNUSED_PARAMETER") showBack: Boolean = true,
) {
    var swipeToTurn by remember { mutableStateOf(store.swipeToTurn()) }
    var tapToTurn by remember { mutableStateOf(store.tapToTurn()) }
    var keepAwake by remember { mutableStateOf(store.keepAwake()) }
    var dark by remember { mutableStateOf(darkTheme) }
    var confirmingRestore by remember { mutableStateOf(false) }
    var removingFolder by remember { mutableStateOf<SavedFolder?>(null) }
    val busy = backingUp || restoring || scanningFolders
    // The two ways of turning a page are exclusive, and one of them always stays on.
    val chooseSwipe: (Boolean) -> Unit = { enabled ->
        swipeToTurn = enabled
        tapToTurn = !enabled
        store.saveSwipeToTurn(enabled)
    }
    val context = LocalContext.current
    val version = remember(context) { packageVersion(context) }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 26.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        ShelfSheet(Modifier.weight(1f).padding(start = 14.dp, end = 14.dp, top = 16.dp)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 132.dp)) {
                item { SectionLabel("Turning pages") }
                item {
                    SettingSwitch(R.drawable.ic_swipe, "Swipe between pages",
                        "Swipe left or right, and the page turns over the way it does when tapped.",
                        swipeToTurn) { enabled -> if (enabled || tapToTurn) chooseSwipe(enabled) }
                }
                item {
                    SettingSwitch(R.drawable.ic_tap, "Tap the screen edges",
                        "Tapping the left or right edge turns the page. Links and highlights keep their own taps.",
                        tapToTurn) { enabled -> if (enabled || swipeToTurn) chooseSwipe(!enabled) }
                }
                item { SectionLabel("Appearance") }
                item { ThemeChoice(dark) { dark = it; onDarkTheme(it) } }
                item { SectionLabel("Reading") }
                item {
                    SettingSwitch(R.drawable.ic_daylight, "Keep the screen on while reading",
                        "Stops the display from sleeping while a book is open.",
                        keepAwake) { keepAwake = it; store.saveKeepAwake(it) }
                }
                item { SectionLabel("Your folders") }
                item { FolderIntro() }
                items(folders, key = { it.tree }) { folder ->
                    FolderRow(folder, enabled = !busy, onRemove = { removingFolder = folder })
                }
                item {
                    SettingAction(R.drawable.ic_folder, "Add a folder",
                        "Read the books already in it, where they are. Nothing is copied or moved.",
                        enabled = !busy) { onAddFolder() }
                }
                if (folders.isNotEmpty()) item {
                    SettingAction(R.drawable.ic_search, "Look in your folders again",
                        "Picks up books added since the shelf last looked, and drops ones taken away.",
                        enabled = !busy) { onScanFolders() }
                }
                if (setAside > 0) item {
                    SettingAction(R.drawable.ic_restore, "Bring back removed books",
                        if (setAside == 1) "1 book taken off the shelf is still in its folder."
                        else "$setAside books taken off the shelf are still in their folders.",
                        enabled = !busy) { onRestoreSetAside() }
                }
                if (scanningFolders) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Looking in your folders…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { SectionLabel("Your library") }
                item {
                    SettingAction(R.drawable.ic_export, "Back up your reading",
                        "Saves your places, bookmarks, highlights, notes and settings. Books are not copied.",
                        enabled = !busy) { onBackup() }
                }
                item {
                    SettingAction(R.drawable.ic_restore, "Restore from a backup",
                        "Brings back what a backup holds. Nothing already on this phone is removed.",
                        enabled = !busy) { confirmingRestore = true }
                }
                if (backingUp || restoring) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (backingUp) "Saving your library…" else "Putting your library back…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { SectionLabel("About") }
                item { About(version) }
            }
        }
    }
    if (confirmingRestore) AlertDialog(onDismissRequest = { confirmingRestore = false },
        title = { Text("Restore from a backup?") },
        text = {
            Text("Reading places, bookmarks, highlights, notes and reading settings from the backup " +
                "are added to this phone, and your reading settings are set to the ones it holds. " +
                "Books are read from their folders; a backup made by this version carries no book " +
                "files. Nothing already here is removed, and a book’s place only moves when the " +
                "backup’s is further along.")
        },
        confirmButton = {
            TextButton(onClick = { confirmingRestore = false; onRestore() }) { Text("Choose a file") }
        },
        dismissButton = { TextButton(onClick = { confirmingRestore = false }) { Text("Cancel") } })
    removingFolder?.let { folder ->
        AlertDialog(onDismissRequest = { removingFolder = null },
            title = { Text("Stop reading from “${folder.label}”?") },
            text = {
                Text("Its books leave the shelf. Your reading places, bookmarks and highlights are kept, " +
                    "and nothing in the folder is moved or deleted. You can add the folder again at any time.")
            },
            confirmButton = {
                TextButton(onClick = { removingFolder = null; onRemoveFolder(folder) }) { Text("Remove folder") }
            },
            dismissButton = { TextButton(onClick = { removingFolder = null }) { Text("Keep it") } })
    }
}

/** Why a folder is worth adding, said once above the list rather than on every row. */
@Composable
private fun FolderIntro() {
    Text("Duck Reader can read the books you already keep on your phone, straight from their folder. " +
            "A book is unpacked into the app’s cache only while it is being read, so the folder stays yours.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp))
}

/** One granted folder: its name, and the one way to stop reading from it. */
@Composable
private fun FolderRow(folder: SavedFolder, enabled: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_folder), contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.label, style = MaterialTheme.typography.bodyLarge)
            Text("Read where it is", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = "Remove the folder ${folder.label}")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.8.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
}

/**
 * Light or dark, offered as one control with two halves rather than a switch: a switch says on or
 * off, and this is a choice between two rooms of the same app.
 */
@Composable
private fun ThemeChoice(dark: Boolean, onTheme: (Boolean) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
        .clip(RoundedCornerShape(50)).background(colours.outlineVariant.copy(alpha = .55f)).padding(3.dp)) {
        listOf(false to "Light", true to "Dark").forEach { (isDark, label) ->
            val active = isDark == dark
            Row(Modifier.weight(1f).clip(RoundedCornerShape(50))
                .background(if (active) colours.primary else Color.Transparent)
                .clickable { onTheme(isDark) }
                .semantics {
                    selected = active
                    contentDescription = "$label theme"
                }
                .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (isDark) R.drawable.ic_night else R.drawable.ic_daylight),
                    contentDescription = null, modifier = Modifier.size(17.dp),
                    tint = if (active) colours.onPrimary else colours.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (active) colours.onPrimary else colours.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    icon: Int, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting that opens something rather than toggling, so it carries a chevron instead of a switch. */
@Composable
private fun SettingAction(
    icon: Int, title: String, detail: String, enabled: Boolean, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (!enabled) disabled() }
        .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Text("›", style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun About(version: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("Duck Reader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Version $version · Offline by design", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text("Your books, progress, bookmarks and highlights stay on this device and are never uploaded.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(8.dp))
    }
}

@Suppress("DEPRECATION")
private fun packageVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty().ifEmpty { "1.0" }
