# CUE for Salt Player

[中文](README.md) | [English](README_EN.md)

A desktop Salt Player plugin for external CUE sheets and CUE data embedded in FLAC files. Browse, search, and play individual tracks in Album Details and Songs.

**Current version v0.7.0 · 2026-11-20**

- **Follow the Songs list order.** Sequential playback and list loop follow the sort order and search results in use when playback starts. Automatic transitions and Previous/Next follow that order across regular songs, full-length audio, and individual CUE tracks.
- **Continue across discs in Album Details.** Sequential playback and list loop follow the album’s disc and track order. The last CUE track on CD1 can continue into the first playable CUE track on CD2.
- **Album-wide CUE actions.** Refresh CUE and Manage tracks appear to the right of the first visible disc heading and apply to the entire album.
- **Play full-length audio from Songs.** Selecting the original row plays the entire audio file. Its title remains visible, and the accent color follows the actual CUE track during CUE playback.
- **Artwork for full-length WAV files in Songs.** Fixes the default placeholder on full-length WAV rows with a matching external CUE sheet. Visible full-length rows can display folder artwork with or without individual CUE tracks shown.
- **Locate playback and keep Songs steady.** Locate Current Song finds the playing CUE track or its full-length audio by row key and keeps the scroll position when the row is already fully visible. Songs retains the current results and loaded artwork during equivalent host refreshes to prevent flicker or jumping to the end of the list.
- **Search Artist Details.** The Songs tab finds CUE tracks alongside regular songs and applies existing hidden and removed records.
- **Play across files in the CUE window.** Select a track in another library-matched audio file from the same CUE sheet or multi-disc album.
- **Hover stability.** The Songs locate hint and seek preview are rendered within the page to avoid pointer events going to a separate hover layer during navigation.

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
- **Digital Disc integration.** With SPW Digital Disc v1.11.0 enabled, switching CUE tracks triggers the disc eject animation.

## Installation and use

1. Import the `.spmod` file in Salt Player's mod manager and enable the plugin.
2. Grant the requested shortcut, library read, and method and UI hook permissions.
3. Add the album audio file to the library. Place an external `.cue` file in the same folder, or add a FLAC file that already contains CUE data.
4. Open the album and select a CUE track in Album Details. You can also search by track title and play it from Songs.

### Playback highlighting and refreshing

The accent color follows CUE track changes and disc transitions in Album Details and Songs. Pausing keeps the current track highlighted. When the current CUE track is visible, its full-length audio entry uses the normal text color.

After editing a title, artist, track count, or timestamp, choose **Refresh CUE** beside the first disc heading in Album Details or in the Manage tracks window. This forces a re-read even when an editor preserves the CUE file’s size and modification time. Existing entries from other albums are reused. An audio file’s original entry is restored if its CUE match is lost. The current playback file’s CUE data is also re-read to update its title, artist, and track time range.

### Shuffle and playback scope

Select Random using Salt Player’s playback-mode control. Each playable CUE track and each regular song in the current native queue becomes one candidate; the current track is excluded from the immediate next choice. Reaching a CUE boundary and pressing Next both choose a candidate. Previous can return to a previously played track when history is available. Tracks only hidden from Songs remain playable from albums; tracks removed through the plugin are excluded from its shuffle candidates.

The candidate list is prepared in the background and reported under playback/queue diagnostics. Salt Player handles skipping while candidates are unavailable or the host integration is incompatible. The native queue still displays audio files.

When playback starts from the main Songs page, sequential playback and list loop use the displayed list order, including the active sort and search results. Regular songs, visible full-length entries, and individual CUE tracks each retain their position in the list. Selecting a full-length entry plays the entire file.

When playback starts from Album Details, CUE tracks follow the album’s disc and track order. The last track on a disc can continue into the first playable track on the next disc. When there is no next CUE disc, playback continues according to Salt Player’s playback mode.

### Filter and restore tracks in the CUE window

Press **Ctrl+Alt+U**. When a disc image from a multi-disc album is playing, the window looks up the other discs from the same album in the library and combines their CUE tracks and recovery records. Opening a CUE manually shows the tracks referenced by that file. Search title or artist with case-insensitive, space-separated keywords and combine the search with All, Hidden, or Removed. Tracks carrying both records appear under both status filters.

Check individual boxes or choose Select results, then Restore checked tracks (N). Only checked, displayed entries with a hidden/removal record count toward N. With no boxes checked, N is zero and the action is disabled; highlighting a row is only for playback navigation. Changing the search or status filter clears checked entries.

Restore current results (N) restores hidden or removed tracks in this window’s current filtered results, leaving other CUE sources and filtered-out tracks unchanged. Restore album covers the full album regardless of filters; Restore all covers every album recorded by the plugin.

Playback accent and checkboxes are independent. Locate playback clears filters and scrolls to the playing segment. Multi-audio results are grouped in source order, which may differ from disc-number tags. Double-click a library-matched track to play it. The button shows Jump to track start for a track in the current file and Play track for another matched file. Playback is disabled when the selected row is filtered out.

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

For full-length WAV entries in Songs, the plugin checks the audio folder for artwork first. If there is no usable cover and the file is in a disc folder such as `CD1` or `CD2`, it looks in the parent folder for shared artwork.

For example, put the audio and CUE sheet for disc 1 in `Album/CD1/` and those for disc 2 in `Album/CD2/`. Salt Player must group both discs under the same album. A single CUE sheet with multiple `FILE` entries for audio files in the same folder also works.

## Settings

| Setting | Default | Behavior |
| --- | --- | --- |
| 在歌曲页显示 CUE 分轨 (Show CUE tracks on Songs) | On | Lists CUE tracks in Songs and Artist Details; includes them in Songs search |
| 识别 CUE 后隐藏整轨 (Hide the original track when a CUE sheet is found) | Off | In Album Details, hides original rows with visible CUE tracks. On discs with a single full-length entry, the CUE list then follows the disc heading directly. In Songs, hides the original entries that match a CUE sheet |

## Compatibility

| Platform | Distribution channel | Salt Player version | v0.7.0 verification |
| --- | --- | --- | --- |
| Windows | Microsoft Store | 1.19.3 | Host regression passed |
| Windows | Steam | 1.19.3 | Desktop test passed before the album action layout change |

Desktop testing on Linux (Ubuntu 26.04.1 LTS), Steam channel, was completed for v0.6.0.

## Diagnostics and limitations

- Album Details follows the album grouping in Salt Player. Discs assigned to the same album can be stored in separate folders.
- Press **Ctrl+Alt+U** to find the current CUE sheet or select an external sheet with **Open CUE file…** (“打开 CUE 文件…”). The CUE window can display sheets that reference several files. Double-click a library-matched track to play it from the start.
- If tracks are missing in Album Details, check that the audio files are in the library and grouped under the expected album. For external sheets, also check the `FILE` line and file locations. Use Diagnostics or the native interface report in the CUE window for details.
- The native UI integrates through Workshop. Some integration points may need to be updated after a Salt Player upgrade.

## Build from source

Use **JDK 25**. The Gradle Wrapper uses **Gradle 9.8.0**. Run the appropriate command from the project root.

| Platform | Command |
| --- | --- |
| Windows | `build.cmd` |
| Linux | `bash gradlew clean test plugin` |

The `.spmod` file is generated in `build/libs/CUE_for_Salt_Player_v0.7.0.spmod`.

Licensed under the [MIT License](../LICENSE).

Development and release checks: [开发与发布检查](DEVELOPMENT.md).
