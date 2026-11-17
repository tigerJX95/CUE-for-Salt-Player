# CUE for Salt Player

[中文](README.md) | [English](README_EN.md)

A desktop Salt Player plugin for external CUE sheets and CUE data embedded in FLAC files. Browse, search, and play individual tracks in Album Details and Songs.

**Current version v0.4.0**

- **Playback across discs.** Start a CUE track in Album Details to use Previous and Next across discs. Playback also continues to the next adjacent disc with CUE tracks at the end of the current disc.
- **Refresh CUE.** After editing or replacing a CUE sheet, choose **Refresh CUE** in Album Details to update the track lists, Songs, and album count.
- **Track management.** Restore the selected track, all affected tracks in the current album, or every hidden and removed track. The CUE window follows Salt Player styling and shows each track’s duration in one centered column.

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
- **Playback controls.** The playback bar and Now Playing show the current track's title, artist, and track-relative progress. Previous and Next can move between adjacent CUE tracks, including across disc boundaries when playback starts from a CUE track in Album Details. Playback can also continue to the next disc automatically.
- **Artist credits and desktop titles.** Credits come from the track-level or album-level CUE `PERFORMER`, then the audio file or the library's album artist. Windows taskbar and Ubuntu Dock window titles follow the current track.
- **Folder artwork.** When Salt Player has no artwork for the album, the plugin can use a cover image next to the audio file. For a multi-disc album, the header can also use artwork from the common parent folder of the disc folders.

## Installation and use

1. Import the `.spmod` file in Salt Player's mod manager and enable the plugin.
2. Grant the requested shortcut, library read, and method and UI hook permissions.
3. Add the album audio file to the library. Place an external `.cue` file in the same folder, or add a FLAC file that already contains CUE data.
4. Open the album and select a CUE track in Album Details. You can also search by track title and play it from Songs.

### Remove, hide, and restore CUE tracks

Select one or more CUE tracks in Songs and right-click one of them to open the plugin menu. The number in parentheses is the number of CUE tracks the action will affect. If no CUE tracks are selected, the action applies to the track you right-clicked and the menu shows “（1）”.

- **Remove selected CUE tracks from the library (N)** (“从曲库移除所选分轨（N）”). Removes the tracks from Songs, Songs search results, and Album Details.
- **Hide selected CUE tracks from Songs (N)** (“从歌曲页隐藏所选分轨（N）”). Hides the tracks from Songs and its search results. They remain visible and playable in Album Details.

**A mixed selection of CUE tracks and regular songs cannot be removed through one context menu. Use two separate actions.**

1. Select the CUE tracks you want to remove, right-click one of them, and choose **Remove selected CUE tracks from the library (N)**. To hide those tracks from Songs only, choose **Hide selected CUE tracks from Songs (N)**.
2. Select only the regular songs you want to remove, right-click one of them, and choose **Remove from music library** (“从音乐库中移除”) in Salt Player's native menu. Regular songs include standalone tracks and full-length audio entries.

The plugin remembers hidden and removed CUE tracks across restarts. Both actions leave the original audio and CUE files on disk. Open **Manage tracks** (“管理分轨”) in Album Details or press **Ctrl+Alt+U** to open the CUE window.

- **Restore selected track** (“恢复所选分轨”). Select a track marked as hidden or removed, then click the button to restore it.
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
| 在歌曲页显示 CUE 分轨 (Show CUE tracks on Songs) | On | Lists CUE tracks and includes them in Songs search |
| 识别 CUE 后隐藏整轨 (Hide the original track when a CUE sheet is found) | Off | In Album Details, hides original rows with visible CUE tracks. On discs with a single full-length entry, the CUE list then follows the disc heading directly. In Songs, hides the original entries that match a CUE sheet |

## Compatibility

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

The `.spmod` file is generated in `build/libs/CUE_for_Salt_Player_v0.4.0.spmod`.

Licensed under the [MIT License](../LICENSE).
