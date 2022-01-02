# dawconverters

Convert from a specific DAW project format to generic dawproject.

# Conversion Reaper project / dawproject

## Metadata 

* Project comment and author field; other info taken/written from/to export ID3 tags

## Project

* Timebase seconds or beats
* Tempo and signature

## Markers

* Name
* Position
* Color

Range markers are not supported.

## Master Track

* Number of audio channels
* Volume
* Panorama
* Mute
* Solo
* Color

## Tracks

* Folder structure
* Track type - midi and audio, aux if it has receives
* Number of audio channels
* Volume
* Panorama
* Mute
* Solo
* Color

## Device

* VST 2 and VST 3 devices with their state
* Enabled (bypass)
* Loaded (offline)

## Items

* Name
* Position
* Fade in, fade out

## MIDI Items    -> TODO dawproject -> Reaper

* Notes
* Polyphonic / Channel Aftertouch
* Continuous Controller (CC)
* Program Change
* Pitch Bend

## Audio Items

* The sample

## Automation

* Project tempo
* Project signature
* Track volume
* Track panorama
* Track mute
