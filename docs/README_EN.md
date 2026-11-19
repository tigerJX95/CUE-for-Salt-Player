# CUE for Salt Player

[中文](README.md) | [English](README_EN.md)

A desktop Salt Player plugin for external CUE sheets and CUE data embedded in FLAC files. Browse, search, and play individual tracks in Album Details and Songs.

**Current version v0.6.0 · 2026-11-19**

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

[Version history](CHANGELOG_EN.md)

> [!IMPORTANT]
> **A full-length WAV file requires an external CUE sheet.** Put the matching `.cue` file in the same folder, for example `Album.wav` and `Album.cue`. The plugin does not interpret WAV cue markers as tracks.
>
> Embedded CUE support is limited to FLAC `CUESHEET` text tags and binary CUESHEET metadata blocks. Use an external lyrics tool for lyric matching and synchronization.

## Background

A CD rip can be stored as one audio file, with its track titles and start positions in a separate CUE sheet. The plugin brings those tracks into Salt Player's native interface. Selecting a track seeks to the corresponding position in the original audio file.

## Features

- **Album Details.** Shows the number, title, and duration of each CUE track under its disc. Multi-disc albums group CUE tracks by disc. The album’s Songs count includes visible CUE tracks and regular songs.
- **Songs.** Browse, search, play, and select individual CUE tracks. Entries include artwork, artist, an audio quality badge, and duration.
- **Playback controls.** The playback bar and Now Playing show the current track's title, artist, and track-relative progress. The Windows taskbar progress bar also follows the current CUE track. Sequential mode moves between adjacent CUE tracks and continues through the current queue after the source ends. Random mode selects individual CUE tracks and regular songs from that queue. Multi-disc albums can continue across disc boundaries.
- **Artist credits and desktop titles.** Credits come from the track-level or album-level CUE `PERFORMER`, then the audio file or the library's album artist. Windows taskbar and Ubuntu Dock window titles follow the current track.
- **Folder artwork.** When Salt Player has no artwork for the album, the plugin can use a cover image next to the audio file. For a multi-disc album, the header can also use artwork from the common parent folder of the disc folders.

## Installation and use

1. Import the `.spmod` file in Salt Player's mod manager and enable the plugin.
2. Grant the requested shortcut, library read, and method and UI hook permissions.
3. Add the album audio file to the library. Place an external `.cue` file in the same folder, or add a FLAC file that already contains CUE data.
4. Open the album and select a CUE track in Album Details. You can also search by track title and play it from Songs.

### Playback highlighting and refreshing

The accent color follows CUE track changes and disc transitions in Album Details and Songs. Pausing keeps the current track highlighted. When the current CUE track is visible, its full-length audio entry uses the normal text color.

After editing a title, artist, track count, or timestamp, choose **Refresh CUE** in Album Details or Manage tracks. This forces a re-read even when an editor preserves the CUE file’s size and modification time. Existing entries from other albums are reused. An audio file’s original entry is restored if its CUE match is lost. The current playback file’s CUE data is also re-read to update its title, artist, and track time range.

### Shuffle and playback scope

Select Random using Salt Player’s playback-mode control. Each playable CUE track and each regular song in the current native queue becomes one candidate; the current track is excluded from the immediate next choice. Reaching a CUE boundary and pressing Next both choose a candidate. Previous can return to a previously played track when history is available. Tracks only hidden from Songs remain playable from albums; tracks removed through the plugin are excluded from its shuffle candidates.

The candidate list is prepared in the background and reported under playback/queue diagnostics. If preparation or compatibility checks fail, native skipping remains available rather than forcing the adjacent CUE track. The native queue still displays audio files. Sequential and list-loop modes also preserve the complete queue. After the last CUE track, native playback continues to the next audio source; pressing Next at that boundary does the same. Sequential playback stops at the end of the entire queue, while list loop wraps to the queue’s first entry there. When the native queue owns completion, the plugin does not additionally advance the legacy album sequence. Repeat-one behavior remains unchanged.

Selecting a CUE track in Songs or Album Details preserves an established complete queue. If an earlier version reduced it to one audio file, the current page’s source list can restore the scope and order without adding one audio entry per CUE segment. Select a track again from the desired list to repair an old one-file queue.

### Filter and restore tracks in the CUE window

Press **Ctrl+Alt+U**. When a disc image from a multi-disc album is playing, the window looks up the other discs from the same album in the library and combines their CUE tracks and recovery records. Opening a CUE manually shows the tracks referenced by that file. Search title or artist with case-insensitive, space-separated keywords and combine the search with All, Hidden, or Removed. Tracks carrying both records appear under both status filters.

Check individual boxes or choose Select results, then Restore checked tracks (N). Only checked, displayed entries with a hidden/removal record count toward N. With no boxes checked, N is zero and the action is disabled; highlighting a row is only for playback navigation. Changing the search or status filter clears checked entries.

Restore current results (N) restores hidden or removed tracks in this window’s current filtered results, leaving other CUE sources and filtered-out tracks unchanged. Restore album covers the full album regardless of filters; Restore all covers every album recorded by the plugin.

Playback accent and checkboxes are independent. Locate playback clears filters and scrolls to the playing segment. Multi-audio results are grouped in source order, which may differ from disc-number tags. Double-clicking still seeks within the currently playing audio file; use Album Details or Songs to switch audio files. Jump to track start is disabled when its selected row is filtered out.

### Remove, hide, and restore CUE tracks

Select one or more CUE tracks in Songs and right-click one of them to open the plugin menu. The number in parentheses is the number of CUE tracks the action will affect. If no CUE tracks are selected, the action applies to the track you right-clicked and the menu shows “（1）”.

- **Remove selected CUE tracks from the library (N)** (“从曲库移除所选分轨（N）”). Removes the tracks from Songs, Songs search results, and Album Details.
- **Hide selected CUE tracks from Songs (N)** (“从歌曲页隐藏所选分轨（N）”). Hides the tracks from Songs and its search results. They remain visible and playable in Album Details.

**A mixed selection of CUE tracks and regular songs cannot be removed through one context menu. Use two separate actions.**

1. Select the CUE tracks you want to remove, right-click one of them, and choose **Remove selected CUE tracks from the library (N)**. To hide those tracks from Songs only, choose **Hide selected CUE tracks from Songs (N)**.
2. Select only the regular songs you want to remove, right-click one of them, and choose **Remove from music library** (“从音乐库中移除”) in Salt Player's native menu. Regular songs include standalone tracks and full-length audio entries.

The plugin remembers hidden and removed CUE tracks across restarts. Both actions leave the original audio and CUE files on disk. Open **Manage tracks** (“管理分轨”) in Album Details or press **Ctrl+Alt+U** to open the CUE window.

Consecutive hide/remove operations accumulate their records and update the recovery window. Batch actions use the checked CUE tracks in the current list, including Artist Details, without depending on the main Songs page.

- **Restore checked tracks (N)** (“恢复勾选分轨”). Check one or more hidden or removed tracks, then restore them.
- **Restore current results (N)** (“恢复当前结果”). Restore all hidden or removed tracks in the window’s current filtered results.
- **Restore this album** (“恢复本专辑”). Open **Manage tracks** from Album Details to restore affected tracks in that album.
- **Restore all** (“全部恢复”). Restore all tracks hidden or removed through the plugin.

Choose **Refresh CUE** (“重新识别 CUE”) in Album Details or **Manage tracks** to read that album’s CUE sheets again.

## Where to put CUE sheets and artwork

Put `Album.flac`, `Album.cue`, and an optional `cover.png` in the same folder. The CUE `FILE` line should point to the audio file, for example `FILE "Album.flac" WAVE`. The plugin checks for a matching sheet with the same base name first, then for a unique matching sheet in the folder. It reads UTF-8, GBK, and UTF-16 with a byte order mark.

If no external sheet matches, the plugin reads the CUE embedded in the FLAC file. Text tags take priority and can supply track titles and artists. Binary CUESHEET blocks supply track numbers and positions. Those tracks are displayed as `TRACK 01`, `TRACK 02`, and so on.

If Salt Player has no native cover, the plugin checks these filenames in order and skips images it cannot decode.

| Priority | Filename |
| --- | --- |
| 1 | `cover.png` |
| 2 | `cover.jpg` |
| 3 | `folder.png` |
| 4 | `folder.jpg` |

For example, put the audio and CUE sheet for disc 1 in `Album/CD1/` and those for disc 2 in `Album/CD2/`. Salt Player must group both discs under the same album. A single CUE sheet with multiple `FILE` entries for audio files in the same folder also works.

## Settings

| Setting | Default | Behavior |
| --- | --- | --- |
| 在歌曲页显示 CUE 分轨 (Show CUE tracks on Songs) | On | Lists CUE tracks in Songs and Artist Details; includes them in Songs search |
| 识别 CUE 后隐藏整轨 (Hide the original track when a CUE sheet is found) | Off | In Album Details, hides original rows with visible CUE tracks. On discs with a single full-length entry, the CUE list then follows the disc heading directly. In Songs, hides the original entries that match a CUE sheet |

## Compatibility

Artist Details, restoration across discs, and CUE track progress in the taskbar were verified with v0.6.0 on Salt Player 1.19.3 from the Windows Microsoft Store.

| Platform | Distribution channel | Salt Player version | v0.2.0 verification |
| --- | --- | --- | --- |
| Windows | Microsoft Store | 1.19.3 | Passed |
| Windows | Steam | 1.19.3 | Passed |
| Linux (Ubuntu 26.04.1 LTS) | Steam | 1.19.3 | Passed |

## Diagnostics and limitations

- Album Details follows the album grouping in Salt Player. Discs assigned to the same album can be stored in separate folders.
- Press **Ctrl+Alt+U** to find the current CUE sheet or select an external sheet with **Open CUE file…** (“打开 CUE 文件…”). The CUE window can display sheets that reference several files. Double-click a track in the currently playing file to seek to its start.
- If tracks are missing in Album Details, check that the audio files are in the library and grouped under the expected album. For external sheets, also check the `FILE` line and file locations. Use Diagnostics or the native interface report in the CUE window for details.
- The native UI integrates through Workshop. Some integration points may need to be updated after a Salt Player upgrade.

## Build from source

Use **JDK 25**. The Gradle Wrapper uses **Gradle 9.8.0**. Run the appropriate command from the project root.

| Platform | Command |
| --- | --- |
| Windows | `build.cmd` |
| Linux | `bash gradlew clean test plugin` |

The `.spmod` file is generated in `build/libs/CUE_for_Salt_Player_v0.6.0.spmod`.

Licensed under the [MIT License](../LICENSE).

Development and release checks: [开发与发布检查](DEVELOPMENT.md).
