---
title: The ProjectConverter Manual
author: 
- Jürgen Moßgraber
header-includes:
    \usepackage{fancyhdr}
    \pagestyle{fancy}
geometry: margin=2.5cm
papersize: a4paper
date:   \today
...

<div style="page-break-after: always; visibility: hidden"> 
\pagebreak 
</div>

# Welcome to the ProjectConverter Documentation

This tool converts from Cockos Reaper projects to the open DAWproject format and back.

## Installation

[Download][1] and run the matching installer for your operating system.
After that you can start the application ConvertWithMoss.

> **Note**
>
> macOS users should read [README-MACOS.md][2] document for important notices.

## Usage

1. Select a source project file.
2. Select the output folder where you want to create the converted project.
3. The select the source and destination format accordingly. Choosing the same source and destination format is not prevented and might be helpful for checking which information is converted and what is lost.
3. Press the *Convert* button to start the conversion.
   The progress is shown with notification messages in the log area.

## Reaper project as the destination

This destination format has the option to create an arrangement from the Clip Launcher Data. This puts all clips from the Scenes in the DAWproject in the Arranger. Each scene section has the length of the longest clip in the scene. Additionally, range markers are created for each scene using the name of the scene.

## DAWproject as the destination

This destination format has the option to create an arrangement from the Clip Launcher Data similar to the Reaper destination (see above). This might be helpful for DAWs which support the DAWproject format but do not have a Clip Launcher (e.g. Presonus Studio One).

The Reaper source has an option to not add audio files to the DAWproject file and keep their original (relative or absolute) file paths.

## Converted data

The following data can currently be converted between the 2 formats.

**Metadata**

* Project comment and author field; other info taken/written from/to export ID3 tags

**Project**

* Time-base seconds or beats
* Tempo and signature

**Markers**

* Name
* Position
* Color

Range markers are not supported.

**Master Track**

* Number of audio channels
* Volume
* Panorama
* Mute
* Solo
* Color

**Tracks**

* Folder structure
* Track type - MIDI and audio, AUX if it has receives
* Active state - Reaper has no active state for tracks, instead the track controls are set to locked and all plugins of the track are set to offline.
* Number of audio channels
* Volume
* Panorama
* Mute
* Solo
* Color

**Device**

* VST 2, VST 3 and CLAP devices with their state
* Enabled (bypass)
* Loaded (offline)

**Items**

* Name
* Position
* Fade in, fade out

**MIDI Items**

* Notes
* Polyphonic / Channel Aftertouch
* Continuous Controller (CC)
* Program Change
* Pitch Bend

**Audio Items**

* The sample

**Automation**

* Project tempo
* Project signature
* Track volume
* Track panorama
* Track mute

### Known Issues

* No clips in clips in Reaper: nested clips in a DAWproject are tried to be *flattened*, this might cause issues.
* Same for fades which are not on the top level. As a workaround consolidate all clips before export.
* Currently, the following features of the DAWproject format are not supported:
   * Built-in devices.
   * AU plugins.
   * Video clips.
   * Panorama on sends (including modulation envelope).
   * VST parameter envelopes.
   * MIDI parameter envelopes.
   * Complex routings (beyond normal sends).
   * Continuous tempo changes.


<div style="page-break-after: always; visibility: hidden"> 
\pagebreak 
</div>

## Changes

### 1.2.7

* Fixed: Group was missing when it was the 1st track in a group.

### 1.2.6

* Several accessibility improvements and fixes: 
  * Button mnemonics were partially broken.
  * Improved order of tabulator traversal.
  * Added more tooltip info
  * Set default button states, can be execute by pressing *Return*.

### 1.2.5

* New: Huge speed up of media file extraction from DAWproject.
* Fixed: Clips could be missing if a parent wrapper clip had no duration set.

### 1.2.4

* Fixed: Texts in tabs did not show up on Linux.

### 1.2.3

* Fixed: Tempo unit was set to 'linear' but needs to 'bpm'. Bitwig ignores this setting but Studio One does not.

### 1.2.2

* Fixed: New build to fix crash on Macos.

### 1.2.1

* New: Added option on Reaper source to not add audio files to the DAWproject file and keep their original (relative or absolute) file path.
* New: Do not stop DAWproject file creation if an audio file is missing.

### 1.2.0

* New: DAWproject Clip Launcher clips can be converted into the Reaper arranger.
* New: DAWproject Clip Launcher clips can be converted into the DAWproject arranger.
* New: Improved user interface.
* New: Improved layout of manual.
* Fixed: WAV files in 32-bit float and OGG-files had a duration of 0 set in the DAWproject target.
* Fixed: Media files were not added to the destination project when converting DAWproject to DAWproject.

### 1.1.4

* Fixed: Support MIDI clips wrapped in another Lane as exported from Studio One.
* Fixed: File reference converted from Reaper could contain mixed slashes (missing audio in Studio One).
* Fixed: Top level buses are now created as well (Studio One source).

### 1.1.3

* Fixed: A DAWproject which did not confirm to the project XML schema could not be converted but only a warning should have been displayed.
* Fixed: A DAWproject which contained a VST 2 device with an empty (preset) name could not be converted.

### 1.1.2

* New: Only set DAWproject tracks to hybrid if both audio and MIDI is present.
* Fixed: Fixed a crash when a Reaper project contained an empty node.
* Fixed: An error was shown if the last Reaper top track was set as the end of a folder.
* Fixed: Wrong message was logged when presets were about to be saved.

### 1.1.1

* Fixed: Workaround for incorrect color format in Studio One exports.

### 1.1.0

* New: Added more logging and cancellation option when compressing audio files into a DAWproject.
* New: Implemented converting looped audio clips in both directions.
* New: Added support for OGG files when converting from Reaper.
* New: Added conversion of clip comments.
* Fixed: Converting VST3 states from Reaper could crash.

<div style="page-break-after: always; visibility: hidden"> 
\pagebreak 
</div>

[1]: https://mossgrabers.de/Software/ProjectConverter/ProjectConverter.html
[2]: README-MACOS.md
