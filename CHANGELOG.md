# Release notes

## 1.0 — 25 October 2026

The first release of Duck Reader: a quiet, folder-first ebook reader for Android.

**Reading**

- One page at a time, turned by a swipe or by tapping the screen edges — you choose which, and the
  two never compete for the same touch. A swipe acts when the finger is lifted.
- A page turn that reads like paper: the arriving page travels in from the edge of the screen over
  the page being left, which darkens under its shadow and slips aside.
- The page and nothing else while you read. Tap the middle for the pages-left hint, the page number,
  and Contents, **Aa**, Bookmarks and Search.
- Text size, line spacing, margins and typeface, applied to the page already open.
- White, sepia and night paper; night dimming that takes the screen's own brightness below what the
  phone normally offers.
- A daylit theme and a warm night one, either chosen or following the phone.

**The shelf**

- **+** adds a folder of books already on the phone through Android's own folder picker. The shelf
  scans it, including subfolders, and remembers the grant.
- Books are read where they lie. Each one is unpacked into the app's cache when it is read; nothing
  is copied into the library or written back to the folder, and a cleared cache is rebuilt from the
  same file.
- Rows with cover, title, author and when the book was last put down, or a wall of covers. Order by
  most recently read, title or author.
- Progress on every row, filters for what you are reading, what is unread and what is finished, and
  a single **Continue reading** line for the book opened most recently.
- Title and author search, a **Finished** mark on the last page, and a bookmark kept for every book.
- Remove a folder book from the shelf and its file is untouched; **Settings → Bring back removed
  books** puts it back. Removing a folder stops the shelf reading from it and keeps its books' marks.
- Books are not imported. **Open with** or a share says so and points at the folder route; books
  imported by an earlier build stay on the shelf and remain readable.

**Marks and search**

- Whole-book search, listing each match with the words around it and the book's own chapter name;
  tapping a result reopens that passage.
- Highlights in yellow, mint, rose or lavender, notes on a marked passage, and bookmarks — each
  listed under the chapter name the book gives it, in **Contents**.
- A notebook that gathers every note from every book, where a note can be rewritten or dropped and
  the whole notebook saved as a Markdown file.
- Reading places anchored to the text, so a change of font or screen size keeps your place in the
  words.

**Backup**

- **Settings → Back up your reading** writes one zip holding every reading place, bookmark,
  highlight, note and setting. Books are not copied; they stay in their folders.
- Restoring only ever adds: a reading place moves only when the backup's is further along, and a note
  written since is never taken back. A backup made by an earlier build can still restore its books.

**Formats**

- **EPUB 2 and 3** as it is: the book's own package, chapters, covers and navigation.
- **FictionBook (FB2)** converted into the same shape as an EPUB: a chapter per titled section, a
  contents list, images and linked footnotes; UTF-8 and windows-1251.
- **MOBI** (PalmDOC) converted: records decompressed, split at the book's page breaks, with pictures
  and cover.
- Refused with a plain explanation rather than half-read: DRM-protected books, AZW3, MOBI compressed
  with Amazon's Huffman dictionary, encrypted fonts, and fixed-layout or scripted EPUBs.

**Privacy**

- No account, no analytics, no ads, and no network permission at all. Reading places, bookmarks,
  highlights, notes and settings stay on the device; books stay in the folders they belong to.

**Under the hood**

- Kotlin and Jetpack Compose, `minSdk 26`, `targetSdk 37`, GPL-3.0.
- 117 unit tests and 163 tests that run on a phone, including hand-built EPUB, FictionBook and MOBI
  files put through the real unpacking path.
