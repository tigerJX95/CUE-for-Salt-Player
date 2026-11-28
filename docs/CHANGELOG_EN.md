# Changelog

[中文](CHANGELOG.md) | [English](CHANGELOG_EN.md)

## v0.8.0 — 2026-11-25

- **Play or select a CUE track.** After adding CUE audio to a library that already has regular songs, click or double-click the body of a CUE row to play it. Use the checkbox or Ctrl+A for multiple selection.
- **Playback artwork transitions.** The playback controls and now-playing view transition directly to the folder cover. During rapid track changes, only the current track’s artwork is applied. The default music note appears when no artwork is available.
- **Shared artwork for multi-disc albums.** Disc folders such as CD1 and CD2 can share a cover in the album folder. The playback controls, lyrics view, and Digital Disc view can use this cover.
- **Reuse and refresh playback artwork.** Track changes reuse unchanged folder covers. Choose **Refresh CUE** while playing an album to reload its playback cover immediately.
- **Songs page at startup.** When Salt Player already has a song list, the plugin uses it to identify and display CUE tracks.
- **Track visibility diagnostics.** The Songs diagnostics report separate counts for hidden and removed tracks. If all recognized playable tracks are excluded, the report points to **Manage tracks** to restore them.

## v0.7.0 — 2026-11-20

- **Follow the Songs list order.** Sequential playback and list loop follow the sort order and search results in use when playback starts. Automatic transitions and Previous/Next follow that order across regular songs, full-length audio, and individual CUE tracks.
- **Continue across discs in Album Details.** Sequential playback and list loop follow the album’s disc and track order. The last CUE track on CD1 can continue into the first playable CUE track on CD2.
- **Album-wide CUE actions.** Refresh CUE and Manage tracks appear to the right of the first visible disc heading and apply to the entire album.
- **Play full-length audio from Songs.** Selecting the original row plays the entire audio file. Its title remains visible, and the accent color follows the actual CUE track during CUE playback.
- **Artwork for full-length WAV files in Songs.** Fixes the default placeholder on full-length WAV rows with a matching external CUE sheet. Visible full-length rows can display folder artwork with or without individual CUE tracks shown.
- **Locate playback and keep Songs steady.** Locate Current Song finds the playing CUE track or its full-length audio by row key and keeps the scroll position when the row is already fully visible. Songs retains the current results and loaded artwork during equivalent host refreshes to prevent flicker or jumping to the end of the list.
- **Search Artist Details.** The Songs tab finds CUE tracks alongside regular songs and applies existing hidden and removed records.
- **Play across files in the CUE window.** Select a track in another library-matched audio file from the same CUE sheet or multi-disc album.
- **Hover stability.** The Songs locate hint and seek preview are rendered within the page to avoid pointer events going to a separate hover layer during navigation.

## v0.6.0 — 2026-11-19

- **Shuffle individual CUE tracks.** In the host’s Random mode, CUE sheets in the current queue are expanded into individual candidates alongside regular songs. Finishing a CUE track or pressing Next no longer forces playback into the next segment of the same audio file.
- **Preserve the playback scope.** When switching to an audio file already in the current queue, keep the complete queue rather than replacing it with one full-length file. Shuffle does not add songs from outside that queue.
- **Continue after the full-length file.** Sequential playback continues to the song after the current CUE source. Preserve complete queues and repair a former one-file queue from the current page; match by audio path when a dragged item has a different library ID.
- **Consecutive skip state.** Sequential and random transitions release their waiting state when the target is reached, so completed switches do not keep blocking skips until timeout. Disabling clears pending transition state.
- **Batch restoration and status filters.** Check multiple tracks or all displayed results and restore them together. Combine title and artist search with Hidden or Removed filters.
- **Multi-disc management and playback location.** Group entries by source audio without renumbering groups after filtering. The playing track is marked; Locate playback clears the filters and scrolls to it.
- **Artwork on artist pages.** Fill in missing album covers for single- and multi-disc albums that use external CUE sheets.
- **Songs in Artist Details.** Open an artist from their avatar and select Songs to see CUE tracks from single- and multi-disc albums. The song count follows the list, and CUE tracks can be played, selected, hidden, or removed.
- **Restoration across discs.** While a multi-disc album is playing, the CUE window combines hidden and removed records for its discs. Tracks across discs can be selected and restored together.
- **Windows taskbar progress.** The progress bar uses the start and duration of the current CUE track and resets at the start of the next track.
- **Digital Disc integration.** With SPW Digital Disc v1.11.0 enabled, switching CUE tracks triggers the disc eject animation.

## v0.5.0 — 2026-11-18

- **Playback highlighting.** Album Details and Songs use the accent color for the playing CUE track, following track and disc changes. Its original audio row uses the normal color while the CUE track is visible.
- **Search in the CUE window.** Filter by title or artist using multiple keywords and see the matching count. Playback and restoration still target the original track. Clear the search to restore the full list.
- **External CUE cache.** Library snapshots reuse parsing results for unchanged files. The cache limits file and track counts and removes files no longer encountered during scanning.
- **Scoped refresh and original-entry restoration.** Refreshing an album rebuilds CUE entries only for affected audio files, including multiple library records pointing to the same file. The original audio entry returns if its CUE match is lost.
- **Refresh playback metadata.** Refresh CUE forces the affected files to be read again and re-reads the current playback file’s CUE data, updating the title, artist, and track time range without switching audio files first.

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
