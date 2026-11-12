# CUE for Salt Player

[中文](README.md) | [English](README_EN.md)

A desktop Salt Player plugin that reads an external CUE sheet next to an album image. Browse, search, and play individual tracks in Album Details and Songs.

**Current version v0.1.0** · 2026-11-12 · [Changelog](CHANGELOG_EN.md)

> [!IMPORTANT]
> This version supports playback from external CUE sheets only. Embedded sheets, including those stored in FLAC files, are planned for a later version. Use an external lyrics tool for lyric matching and synchronization.

## Background

A CD rip can be stored as one audio file, with its track titles and start positions in a separate CUE sheet. The plugin brings those tracks into Salt Player's native interface. Selecting a track seeks to the corresponding position in the original audio file.

## Features

- **Album Details.** Shows the number, title, and duration of each CUE track under its disc. The disc heading uses Salt Player's native layout.
- **Songs.** Browse, search, play, and select individual CUE tracks. Entries include artwork, artist, an audio quality badge, and duration. Tracks can be hidden individually and restored from the CUE window.
- **Playback controls.** The playback bar and Now Playing show the current track's title, artist, and track-relative progress. Previous and Next can move between adjacent CUE tracks.
- **Artist credits and desktop titles.** Credits come from the track-level or album-level CUE `PERFORMER`, then the audio file or the library's album artist. Windows taskbar and Ubuntu Dock window titles follow the current track.
- **Folder artwork.** When Salt Player has no artwork for the album, the plugin can use a cover image next to the audio file. All tracks share that cover.

## Installation and use

1. Import the `.spmod` file in Salt Player's mod manager and enable the plugin.
2. Grant the requested shortcut, library read, and method and UI hook permissions.
3. Add the album audio file to the library and place its `.cue` file in the same folder.
4. Open the album and select a CUE track in Album Details. You can also search by track title and play it from Songs.

### Hide and restore individual tracks

1. In Songs, right-click the track and choose “从歌曲页隐藏此分轨” (Hide this track from Songs). The entry disappears from the list and search results, and the setting survives a restart.
2. Press **Ctrl+Alt+U** to open the CUE window, then choose “恢复隐藏分轨” (Restore hidden tracks). The button appears when tracks are hidden and restores all hidden entries at once.

Hidden tracks are still visible and playable in Album Details. Hiding a track only changes its visibility on Songs and leaves the audio and CUE files intact.

## Where to put CUE sheets and artwork

Put `Album.flac`, `Album.cue`, and an optional `cover.png` in the same folder. The CUE `FILE` line should point to the audio file, for example `FILE "Album.flac" WAVE`. The plugin checks for a matching sheet with the same base name first, then for a unique matching sheet in the folder. It reads UTF-8, GBK, and UTF-16 with a byte order mark.

If Salt Player has no native cover, the plugin checks these filenames in order and skips images it cannot decode.

| Priority | Filename |
| --- | --- |
| 1 | `cover.png` |
| 2 | `cover.jpg` |
| 3 | `folder.png` |
| 4 | `folder.jpg` |

## Settings

| Setting | Default | Behavior |
| --- | --- | --- |
| 在歌曲页显示 CUE 分轨 (Show CUE tracks on Songs) | On | Lists CUE tracks and includes them in Songs search |
| 识别 CUE 后隐藏整轨 (Hide the original track when a CUE sheet is found) | Off | Shows the CUE tracks and hides the matching original audio entry |

## Compatibility

| Platform | Distribution channel | Salt Player version |
| --- | --- | --- |
| Windows | Microsoft Store | 1.19.3 |
| Windows | Steam | 1.19.3 |
| Linux (Ubuntu 26.04.1 LTS) | Steam | 1.19.3 |

## Diagnostics and limitations

- Native Album Details integration covers albums with a single audio file in the library. The library and queue continue to use the original file. Salt Player's native bulk actions apply to selections containing only ordinary songs.
- Press **Ctrl+Alt+U** to find the current CUE sheet or open one manually. The CUE window can display sheets that reference several files. Double-click a track in the currently playing file to seek to its start.
- If tracks are missing in Album Details, check the `FILE` line and file locations. Use Diagnostics or the native interface report in the CUE window for details. Include the platform, distribution channel, player version, and plugin version when reporting a problem.
- The native UI integrates through Workshop. Some integration points may need to be updated after a Salt Player upgrade.

## Build from source

Use **JDK 25**. The Gradle Wrapper uses **Gradle 9.8.0**. Run the appropriate command from the project root.

| Platform | Command |
| --- | --- |
| Windows | `build.cmd` |
| Linux | `bash gradlew clean test plugin` |

The output is `build/libs/CUE-for-Salt-Player-v0.1.0.spmod`.

Licensed under the [MIT License](../LICENSE).
