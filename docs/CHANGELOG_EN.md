# Changelog

[中文](CHANGELOG.md) | [English](CHANGELOG_EN.md)

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
