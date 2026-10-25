package com.duckreader.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var folder: File
    private lateinit var context: Context
    private lateinit var store: EpubStore

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "settings-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        store = EpubStore(context)
    }

    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun swipingAndEdgeTapsAreSeparateChoicesThatAreNeverBothOn() {
        assertTrue(store.swipeToTurn())
        assertFalse(store.tapToTurn())
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}) } }
        val swipe = compose.onNodeWithText("Swipe between pages")
        val edges = compose.onNodeWithText("Tap the screen edges")
        swipe.assertIsOn()
        edges.assertIsOff()
        // Choosing edge taps switches swiping off, so the two never share one gesture.
        edges.performClick()
        compose.runOnIdle { assertFalse(store.swipeToTurn()); assertTrue(store.tapToTurn()) }
        swipe.assertIsOff()
        edges.assertIsOn()
        // The only way left on cannot be switched off: pages must always be turnable.
        edges.performClick()
        compose.runOnIdle { assertTrue(store.tapToTurn()) }
        edges.assertIsOn()
        swipe.performClick()
        compose.runOnIdle { assertTrue(store.swipeToTurn()); assertFalse(store.tapToTurn()) }
        swipe.assertIsOn()
        edges.assertIsOff()
    }

    @Test fun keepingTheScreenOnStartsOffAndPersists() {
        assertFalse(store.keepAwake())
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}) } }
        val keepAwake = compose.onNodeWithText("Keep the screen on while reading").performScrollTo()
        keepAwake.assertIsOff()
        keepAwake.performClick()
        compose.runOnIdle { assertTrue(store.keepAwake()) }
        keepAwake.assertIsOn()
    }

    /**
     * The theme is the reader's own choice: the switch opens on the state it was given and reports
     * every change. Nothing is saved by the screen itself, so the app stays the one place that
     * decides where a setting lives.
     */
    @Test fun theThemeIsTheReadersChoiceAndIsRemembered() {
        var chosen: Boolean? = null
        compose.setContent { MaterialTheme {
            SettingsScreen(store, onBack = {}, darkTheme = false, onDarkTheme = { chosen = it })
        } }
        // One control with two halves: choosing a side is the whole gesture, so each is pressed
        // in turn rather than toggled.
        compose.onNodeWithContentDescription("Dark theme").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(chosen == true) }
        compose.onNodeWithContentDescription("Light theme").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(chosen == false) }
    }

    /** The theme is the reader's own choice, and it is kept where the app can find it again. */
    @Test fun theThemeIsKept() {
        store.saveDarkTheme(true)
        assertTrue(store.darkTheme())
        store.saveDarkTheme(false)
        assertFalse(store.darkTheme())
    }

    /** Settings is a room of the app now, with no back arrow of its own: the promise is what matters here. */
    @Test fun thePrivacyPromiseIsVisible() {
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}) } }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        scrollToText("ABOUT")
        compose.onNodeWithText("ABOUT").assertIsDisplayed()
        compose.onNodeWithText("Your books, progress, bookmarks and highlights stay on this device and are never uploaded.")
            .assertIsDisplayed()
    }

    @Test fun aBackupAndARestoreAreOfferedAndRestoringAsksFirst() {
        var backingUp = false
        var restoring = false
        compose.setContent { MaterialTheme {
            SettingsScreen(store, onBack = {}, onBackup = { backingUp = true }, onRestore = { restoring = true })
        } }
        scrollToText("Back up your reading")
        compose.onNodeWithText("Back up your reading").performClick()
        compose.runOnIdle { assertTrue(backingUp) }
        // Restoring asks before a file is chosen, and says what it will and will not do.
        scrollToText("Restore from a backup")
        compose.onNodeWithText("Restore from a backup").performClick()
        compose.onNodeWithText("Restore from a backup?").assertIsDisplayed()
        compose.onNodeWithText("Choose a file").performClick()
        compose.runOnIdle { assertTrue(restoring) }
    }

    @Test fun whileABackupIsBeingWrittenTheRowsAreBusyAndSaySo() {
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}, backingUp = true) } }
        scrollToText("Back up your reading")
        compose.onNodeWithText("Back up your reading").assertIsNotEnabled()
        compose.onNodeWithText("Restore from a backup").assertIsNotEnabled()
        scrollToText("Saving your library…")
        compose.onNodeWithText("Saving your library…").assertIsDisplayed()
    }

    /**
     * The reader's own folders are added from here and taken away from here, and taking one away is
     * asked about first because its books leave the shelf.
     */
    @Test fun foldersAreListedAndCanBeAddedAndRemoved() {
        val books = SavedFolder("tree:books", "Books")
        var adding = false
        var removed: SavedFolder? = null
        compose.setContent { MaterialTheme {
            SettingsScreen(store, onBack = {}, folders = listOf(books),
                onAddFolder = { adding = true }, onRemoveFolder = { removed = it })
        } }
        compose.onNodeWithText("Books").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add a folder").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(adding) }
        compose.onNodeWithContentDescription("Remove the folder Books").performScrollTo().performClick()
        compose.onNodeWithText("Stop reading from “Books”?").assertIsDisplayed()
        compose.onNodeWithText("Remove folder").performClick()
        compose.runOnIdle { assertEquals(books, removed) }
    }

    @Test fun booksTakenOffTheShelfCanBeBroughtBackFromSettings() {
        var broughtBack = false
        compose.setContent { MaterialTheme {
            SettingsScreen(store, onBack = {}, setAside = 2, onRestoreSetAside = { broughtBack = true })
        } }
        scrollToText("Bring back removed books")
        compose.onNodeWithText("Bring back removed books").performClick()
        compose.runOnIdle { assertTrue(broughtBack) }
    }

    @Test fun whileTheFoldersAreBeingLookedThroughTheRowsSaySo() {
        compose.setContent { MaterialTheme { SettingsScreen(store, onBack = {}, scanningFolders = true) } }
        scrollToText("Looking in your folders…")
        compose.onNodeWithText("Looking in your folders…").assertIsDisplayed()
        scrollToText("Add a folder")
        compose.onNodeWithText("Add a folder").assertIsNotEnabled()
    }

    /** Moves the sheet by its own scroll until a row far enough down is composed and reachable. */
    private fun scrollToText(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }
}
