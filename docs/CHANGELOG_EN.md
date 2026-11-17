# Changelog

[中文](CHANGELOG.md) | [English](CHANGELOG_EN.md)

## v0.4.0

- **Playback across discs.** After starting a CUE track in Album Details, Next at the end of a disc goes to the first track on the next disc, and Previous at the start goes to the last track on the previous disc. Playback can continue automatically to the next adjacent disc with CUE tracks.
- **Refresh CUE.** Re-read an album’s CUE sheets from Album Details or Manage tracks. The track lists, Songs, and album count are recalculated. Songs shows the original audio entry again if its CUE match is lost.
- **Scoped track restoration.** Restore the selected track, the current album’s affected tracks, or all tracks. The CUE window distinguishes tracks hidden from Songs from tracks removed from the library.
- **CUE window.** Updated buttons, list styling, and selection colors, clarified the **Open CUE file…** button, and replaced the start and end columns with one centered duration column.

## v0.3.0 — 2026-11-15

- **Multi-disc albums.** Supports a separate external CUE sheet for each disc or a single sheet with multiple `FILE` entries for audio files in the same folder. Album Details follows Salt Player’s disc order and shows CUE tracks directly after the corresponding full-length entry. Selecting a track loads the correct audio file and seeks to its start.
- **Album count and artwork.** The Songs count in the album header includes visible CUE tracks and regular songs. If native artwork is missing, the plugin checks the album’s common parent folder first and then each disc folder in disc order.
- **Permissions and lifecycle.** Shortcut registration and native UI integration check their own permissions. With only shortcut permission, the CUE window can still open external sheets for preview. Settings load in the background. Disabling the plugin cancels its tasks and cleans up its windows and listeners.
- **Fixed hiding original tracks in multi-disc albums.** With the hide option enabled, Album Details hides an original row when it has visible CUE tracks. On discs with a single full-length entry, the CUE count and track list then appear directly below the disc heading. Discs without visible CUE tracks keep their original rows.

## v0.2.0 — 2026-11-13

- **Embedded FLAC CUE.** Supports `CUESHEET` text tags and binary CUESHEET metadata blocks. A matching external sheet takes priority. Full-length WAV files use an external sheet in the same folder.
- **Batch hide and remove.** The Songs context menu can hide or remove one or more CUE tracks and shows the number affected. Hiding affects Songs and its search results. Removing also takes tracks out of Album Details. Press **Ctrl+Alt+U** to restore tracks hidden or removed through the plugin.
- **List display.** Aligns full-length and CUE track durations in Album Details, uses tabular numerals for CUE track durations, and supplies folder artwork for full-length WAV entries in Songs.

## v0.1.0 — 2026-11-12

- **External CUE sheets.** Finds a matching sheet next to an album audio file. Album Details shows the track number, title, and duration. Selecting a track plays it from its `INDEX 01` position. The original audio entry can be hidden in settings.
- **CUE tracks on Songs.** Individual entries can be searched, played, and selected. Hide a track from its context menu and restore hidden entries from the CUE window. Album Details continues to show all tracks.
- **Track playback controls.** The playback bar and Now Playing show the current CUE track and its progress. Previous and Next can move between adjacent tracks.
- **Artist credits and desktop titles.** Uses valid CUE performer credits with audio and album artist fallbacks. Windows taskbar and Ubuntu Dock window titles update as tracks change.
- **Folder artwork.** Supplies artwork to album cards, Album Details, the Songs list, and playback views when Salt Player has no native cover.
- **Separate CUE window.** Press **Ctrl+Alt+U** to find the current sheet, open one manually, restore hidden Songs entries, or view the native interface report.

This version supports playback from external CUE sheets. Embedded sheets are planned for a later version.
