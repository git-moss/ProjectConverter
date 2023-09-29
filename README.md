# ProjectConverter

Converts from Cockos Reaper projects to the open DAWproject format and back.

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

## Conversion Reaper project / dawproject

The following data can currently be converted between the 2 formats.

### Metadata 

* Project comment and author field; other info taken/written from/to export ID3 tags

### Project

* Timebase seconds or beats
* Tempo and signature

### Markers

* Name
* Position
* Color

Range markers are not supported.

### Master Track

* Number of audio channels
* Volume
* Panorama
* Mute
* Solo
* Color

### Tracks

* Folder structure
* Track type - midi and audio, aux if it has receives
* Number of audio channels
* Volume
* Panorama
* Mute
* Solo
* Color

### Device

* VST 2, VST 3 and CLAP devices with their state
* Enabled (bypass)
* Loaded (offline)

### Items

* Name
* Position
* Fade in, fade out

### MIDI Items

* Notes
* Polyphonic / Channel Aftertouch
* Continuous Controller (CC)
* Program Change
* Pitch Bend

### Audio Items

* The sample

### Automation

* Project tempo
* Project signature
* Track volume
* Track panorama
* Track mute

[1]: https://mossgrabers.de/Software/ProjectConverter/ProjectConverter.html
[2]: README-MACOS.md
