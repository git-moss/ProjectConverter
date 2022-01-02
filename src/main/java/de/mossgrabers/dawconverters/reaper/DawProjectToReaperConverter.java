// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.dawconverters.reaper;

import de.mossgrabers.dawconverters.reaper.project.Chunk;
import de.mossgrabers.dawconverters.reaper.project.Node;
import de.mossgrabers.dawconverters.reaper.project.ReaperProject;
import de.mossgrabers.dawconverters.reaper.project.VstChunkHandler;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.DawProject;
import com.bitwig.dawproject.FolderTrack;
import com.bitwig.dawproject.Metadata;
import com.bitwig.dawproject.MixerRole;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.Send;
import com.bitwig.dawproject.SendType;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.TrackOrFolder;
import com.bitwig.dawproject.Transport;
import com.bitwig.dawproject.Unit;
import com.bitwig.dawproject.device.Device;
import com.bitwig.dawproject.device.Vst2Plugin;
import com.bitwig.dawproject.device.Vst3Plugin;
import com.bitwig.dawproject.timeline.Audio;
import com.bitwig.dawproject.timeline.BoolPoint;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.Clips;
import com.bitwig.dawproject.timeline.IntegerPoint;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Marker;
import com.bitwig.dawproject.timeline.Markers;
import com.bitwig.dawproject.timeline.Notes;
import com.bitwig.dawproject.timeline.Point;
import com.bitwig.dawproject.timeline.Points;
import com.bitwig.dawproject.timeline.RealPoint;
import com.bitwig.dawproject.timeline.TimeSignaturePoint;
import com.bitwig.dawproject.timeline.Timebase;
import com.bitwig.dawproject.timeline.Timeline;
import com.bitwig.dawproject.timeline.Warp;
import com.bitwig.dawproject.timeline.Warps;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;


/**
 * Converts a Reaper project file (the already loaded chunks to be more specific) into a dawproject
 * structure.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public class DawProjectToReaperConverter extends ReaperTags
{
    private static final String ONLY_LINEAR = "Only linear volumes and panoramas are supported.";


    /** Helper structure to create a flat track list. */
    private class TrackInfo
    {
        FolderTrack folder    = null;
        Track       track     = null;
        int         type      = 0;
        int         direction = 0;
    }


    private final Map<Track, Chunk>   chunkMapping = new HashMap<> ();
    private final Map<Track, Integer> trackMapping = new HashMap<> ();

    private final File                inputFile;
    private final Metadata            metadata;
    private final Project             project;
    private final Chunk               rootChunk    = new Chunk ();
    private Lanes                     arrangementLanes;
    private Track                     masterTrack;
    private Points                    tempoEnvelope;
    private Points                    signatureEnvelope;
    private Double                    tempo;
    private int                       numerator;
    private int                       denominator;
    private List<String>              audioFiles   = new ArrayList<> ();
    private boolean                   destinationIsBeats;


    /**
     * Constructor.
     *
     * @param inputFile The dawproject file
     * @param destinationTimebase The time base to use for the destination project, the time base of
     *            the source project will be used if set to null
     * @throws IOException Could not load the file
     */
    public DawProjectToReaperConverter (final File inputFile, final Timebase destinationTimebase) throws IOException
    {
        this.metadata = DawProject.loadMetadata (inputFile);
        this.project = DawProject.loadProject (inputFile);

        this.inputFile = inputFile;

        setNode (this.rootChunk, PROJECT_ROOT, "0.1", "6.33/win64");

        this.convertMetadata ();
        final boolean sourceIsBeats = this.convertArrangement ();
        this.convertTransport ();

        final List<TrackOrFolder> tracks = this.project.tracks;
        if (tracks == null)
            return;

        // Find the master track and handle it separately
        for (final TrackOrFolder trackOrFolder: tracks)
        {
            if (trackOrFolder instanceof final Track track && track.mixerRole == MixerRole.master)
            {
                tracks.remove (track);
                this.masterTrack = track;
                this.convertMaster (track);
                break;
            }
        }

        this.convertTracks (tracks);

        this.destinationIsBeats = destinationTimebase == null ? sourceIsBeats : destinationTimebase == Timebase.beats;
        final String value = this.destinationIsBeats ? "1" : "0";
        addNode (this.rootChunk, PROJECT_TIME_LOCKMODE, value);
        addNode (this.rootChunk, PROJECT_TIME_ENV_LOCKMODE, value);

        // Time values are always in seconds, indicators above seem to be only for visual
        // information
        this.destinationIsBeats = false;

        this.convertLanes (sourceIsBeats);
    }


    /**
     * Stores the filled Reaper structure into the given file.
     *
     * @param outputFile The file to store the structure to
     * @throws IOException Could not write the file
     */
    public void saveProject (final File outputFile) throws IOException
    {
        // Store the project file
        try (final FileWriter writer = new FileWriter (outputFile, StandardCharsets.UTF_8))
        {
            writer.append (ReaperProject.format (this.rootChunk));
        }

        // Store all referenced wave files
        final String folder = outputFile.getParent ();
        File sampleOutputFile;

        for (final String audioFile: this.audioFiles)
        {
            final String name = new File (audioFile).getName ();
            sampleOutputFile = new File (folder, name);

            try (final InputStream in = DawProject.streamEmbedded (this.inputFile, audioFile))
            {
                Files.copy (in, sampleOutputFile.toPath (), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }


    /**
     * Assigns the metadata to different Reaper settings.
     */
    private void convertMetadata ()
    {
        // Only one Authors field in Reaper
        final StringBuilder sb = new StringBuilder ();
        if (this.metadata.artist != null && !this.metadata.artist.isBlank ())
            sb.append (this.metadata.artist);
        if (this.metadata.producer != null && !this.metadata.producer.isBlank ())
        {
            if (!sb.isEmpty ())
                sb.append (", ");
            sb.append (this.metadata.producer);
        }
        if (this.metadata.writer != null && !this.metadata.writer.isBlank ())
        {
            if (!sb.isEmpty ())
                sb.append (", ");
            sb.append (this.metadata.writer);
        }

        final String author = sb.toString ();
        if (!author.isBlank ())
            addNode (this.rootChunk, PROJECT_AUTHOR, author);

        // Comment goes into the Notes
        if (this.metadata.comment != null)
        {
            final Chunk notesChunk = addChunk (this.rootChunk, PROJECT_NOTES, "0");

            final String [] commentLines = this.metadata.comment.split ("\\R");
            for (final String line: commentLines)
            {
                addNode (notesChunk, "|" + line);
            }
        }

        // Fill render metadata
        final Chunk renderChunk = addChunk (this.rootChunk, PROJECT_RENDER_METADATA);
        if (this.metadata.comment != null)
            addNode (renderChunk, METADATA_TAG, "ID3:COMM", this.metadata.comment.replace ("\r", "").replace ("\n", " "));
        if (this.metadata.writer != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TCOM", this.metadata.writer);
        if (this.metadata.genre != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TCON", this.metadata.genre);
        if (this.metadata.copyright != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TCOP", this.metadata.copyright);
        if (this.metadata.producer != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TIPL", this.metadata.producer);
        if (this.metadata.title != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TIT2", this.metadata.title);
        if (this.metadata.artist != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TPE1", this.metadata.artist);
        if (this.metadata.originalArtist != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TPE2", this.metadata.originalArtist);
        if (this.metadata.year != null)
            addNode (renderChunk, METADATA_TAG, "ID3:TYER", this.metadata.year);
    }


    /**
     * Assigns the Arrangement data to different Reaper settings.
     * 
     * @return True if the time base is in beats otherwise seconds
     */
    private boolean convertArrangement ()
    {
        final Arrangement arrangement = this.project.arrangement;
        if (arrangement == null || arrangement.content == null)
            return true;
        if (arrangement.content instanceof final Lanes lanes)
            this.arrangementLanes = lanes;

        return updateIsBeats (arrangement.content, true);
    }


    /**
     * Assigns the Transport data to different Reaper settings.
     */
    private void convertTransport ()
    {
        final Transport transport = this.project.transport;
        if (transport == null)
            return;

        this.tempo = transport.tempo == null ? Double.valueOf (120) : transport.tempo.value;
        this.numerator = transport.timeSignature == null || transport.timeSignature.numerator == null ? 4 : transport.timeSignature.numerator.intValue ();
        this.denominator = transport.timeSignature == null || transport.timeSignature.denominator == null ? 4 : transport.timeSignature.denominator.intValue ();
        addNode (this.rootChunk, PROJECT_TEMPO, this.tempo.toString (), Integer.toString (this.numerator), Integer.toString (this.denominator));
    }


    /**
     * Assigns the Master track data to different Reaper settings.
     *
     * @param masterTrack The master track
     * @throws IOException Units must be linear
     */
    private void convertMaster (final Track masterTrack) throws IOException
    {
        addNode (this.rootChunk, MASTER_NUMBER_OF_CHANNELS, masterTrack.audioChannels == null ? "2" : masterTrack.audioChannels.toString ());

        if (masterTrack.volume != null && masterTrack.pan != null)
        {
            if (masterTrack.volume.unit != Unit.linear || masterTrack.pan.unit != Unit.linear)
                throw new IOException (ONLY_LINEAR);
            addNode (this.rootChunk, MASTER_VOLUME_PAN, masterTrack.volume.value.toString (), masterTrack.pan.value.toString ());
        }

        int state = masterTrack.solo != null && masterTrack.solo.booleanValue () ? 2 : 0;
        if (masterTrack.mute != null && masterTrack.mute.value.booleanValue ())
            state |= 1;
        addNode (this.rootChunk, MASTER_MUTE_SOLO, Integer.toString (state));

        if (masterTrack.color != null)
            addNode (this.rootChunk, MASTER_COLOR, Integer.toString (fromHexColor (masterTrack.color)));

        this.convertDevices (masterTrack.devices, this.rootChunk, MASTER_CHUNK_FXCHAIN);
    }


    /**
     * Assigns the data of all Tracks to different Reaper settings.
     *
     * @param tracks The tracks to convert
     * @throws IOException Units must be linear
     */
    private void convertTracks (final List<TrackOrFolder> tracks) throws IOException
    {
        final List<TrackInfo> flatTracks = new ArrayList<> ();
        this.createTrackStructure (tracks, flatTracks, true);

        for (int i = 0; i < flatTracks.size (); i++)
            this.convertTrack (flatTracks, i);

        // Set sends and create automation
        for (final TrackInfo trackInfo: flatTracks)
        {
            final Track track = trackInfo.track;
            if (track == null)
                continue;

            if (track.sends != null)
            {
                for (final Send send: track.sends)
                {
                    if (send.destination == null || send.value == null)
                        continue;
                    if (send.unit != Unit.linear)
                        throw new IOException (ONLY_LINEAR);
                    final Chunk auxChunk = this.chunkMapping.get (send.destination);
                    final Integer index = this.trackMapping.get (track);
                    if (auxChunk != null && index != null)
                    {
                        final String mode = send.type == null || send.type == SendType.post ? "0" : "1";
                        addNode (auxChunk, TRACK_AUX_RECEIVE, index.toString (), mode, send.value.toString (), "0");
                    }
                }
            }
        }
    }


    /**
     * Assigns the data of all Devices of a track to different Reaper settings.
     *
     * @param devices The devices to convert
     * @param parentChunk The chunk where to add the data
     * @param fxchainName The name of the FX chain chunk
     * @throws IOException Could not create the VST chunks
     */
    private void convertDevices (final List<Device> devices, final Chunk parentChunk, final String fxchainName) throws IOException
    {
        if (devices == null || devices.isEmpty ())
            return;

        final Chunk fxChunk = addChunk (parentChunk, fxchainName);

        for (final Device device: devices)
        {
            if (device.state == null)
                continue;

            final boolean bypass = device.enabled != null && device.enabled.value != null && !device.enabled.value.booleanValue ();
            final boolean offline = device.loaded != null && !device.loaded.booleanValue ();
            addNode (fxChunk, FXCHAIN_BYPASS, bypass ? "1" : "0", offline ? "1" : "0");

            final Chunk vstChunk = addChunk (fxChunk, CHUNK_VST, createDeviceName (device), "\"\"", "0", "\"\"", createDeviceID (device));
            if (device.state.isExternal != null && device.state.isExternal.booleanValue ())
            {
                final File file = new File (this.inputFile.getParent (), device.state.path);
                try (final InputStream in = new FileInputStream (file))
                {
                    handleVstDevice (vstChunk, device, in);
                }
            }
            else
            {
                try (final InputStream in = DawProject.streamEmbedded (this.inputFile, device.state.path))
                {
                    handleVstDevice (vstChunk, device, in);
                }
            }

            // TODO convert VST parameter envelopes -> needs parameter ID fix from Bitwig export
        }
    }


    /**
     * Convert a single track.
     *
     * @param flatTracks All flattened
     * @param trackIndex The index of the track to convert
     * @throws IOException Error converting the track
     */
    private void convertTrack (final List<TrackInfo> flatTracks, int trackIndex) throws IOException
    {
        final TrackInfo trackInfo = flatTracks.get (trackIndex);
        final Chunk trackChunk = addChunk (this.rootChunk, CHUNK_TRACK);

        final Track track = trackInfo.track;
        createTrack (trackChunk, trackInfo.folder == null ? trackInfo.track : trackInfo.folder, trackInfo.type, trackInfo.direction);

        if (track == null)
            return;

        this.chunkMapping.put (track, trackChunk);
        this.trackMapping.put (track, Integer.valueOf (trackIndex));

        // Number of channels
        if (track.audioChannels != null)
            addNode (trackChunk, TRACK_NUMBER_OF_CHANNELS, track.audioChannels.toString ());

        // Volume & Panorama
        if (track.volume != null)
        {
            if (track.volume.unit != Unit.linear || track.pan != null && track.pan.unit != Unit.linear)
                throw new IOException (ONLY_LINEAR);
            addNode (trackChunk, TRACK_VOLUME_PAN, track.volume.value.toString (), track.pan == null ? "0" : track.pan.value.toString ());
        }

        // Mute & Solo
        int state = track.solo != null && track.solo.booleanValue () ? 2 : 0;
        if (track.mute != null && track.mute.value.booleanValue ())
            state |= 1;
        addNode (trackChunk, TRACK_MUTE_SOLO, Integer.toString (state));

        // Convert all FX devices
        this.convertDevices (track.devices, trackChunk, CHUNK_FXCHAIN);
    }


    /**
     * Recursively convert grouped clips into top level media items, since Reaper does not support
     * to wrap clips into a parent clip.
     * 
     * @param trackChunk
     * @param clips The clips to convert
     * @param parentPosition The time at which the parent clip starts
     * @param parentDuration The duration of the parent clip
     * @param parentOffset An offset to the play start in the clip
     * @param sourceIsBeats If true the source time base is in beats otherwise seconds
     */
    private void convertItems (final Chunk trackChunk, final Clips clips, final double parentPosition, final double parentDuration, final double parentOffset, final boolean sourceIsBeats)
    {
        if (clips.clips == null)
            return;

        for (final Clip clip: clips.clips)
        {
            final boolean isBeats = updateIsBeats (clips, sourceIsBeats);

            // Cannot group clips in clips in Reaper, therefore only create the most inner clips
            if (clip.content instanceof final Clips groupedClips)
            {
                convertItems (trackChunk, groupedClips, parentPosition + clip.time, clip.duration, clip.playStart == null ? 0 : clip.playStart.doubleValue (), isBeats);
                continue;
            }

            // Ignore clips outside of the view of the parent clip
            final double clipTimeEnd = clip.time + clip.duration;
            if (clipTimeEnd <= parentOffset || clip.time >= parentDuration)
                continue;

            final Chunk itemChunk = addChunk (trackChunk, CHUNK_ITEM);

            if (clip.name != null)
                addNode (itemChunk, ITEM_NAME, clip.name);

            // Check if clip start is left to the parents start, if true limit it
            double start = clip.time;
            double duration = clip.duration;
            double offset = 0;
            if (start < parentOffset)
            {
                final double diff = parentOffset - start;
                duration -= diff;
                offset = diff;
                start = 0;
            }
            // Limit to max duration depending on the surrounding clip
            if (duration > parentDuration)
                duration = parentDuration;
            start += parentPosition;
            addNode (itemChunk, ITEM_POSITION, Double.toString (this.handleTime (start, isBeats)));
            addNode (itemChunk, ITEM_LENGTH, Double.toString (this.handleTime (duration, isBeats)));
            addNode (itemChunk, ITEM_SAMPLE_OFFSET, Double.toString (this.handleTime (offset, isBeats)));

            // FADEIN 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
            addNode (itemChunk, ITEM_FADEIN, "1", this.handleTime (clip.fadeInTime, isBeats).toString (), "0");

            // FADEOUT 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
            addNode (itemChunk, ITEM_FADEOUT, "1", this.handleTime (clip.fadeOutTime, isBeats).toString (), "0");

            if (clip.content instanceof Notes notes)
                convertMIDI (clip, notes, itemChunk);
            else if (clip.content instanceof Audio audio)
                convertAudio (audio, itemChunk, 1);
            else if (clip.content instanceof Warps warps)
            {
                final double playrate = calcPlayrate (warps.events, sourceIsBeats);

                if (warps.content instanceof Audio audio)
                    convertAudio (audio, itemChunk, playrate);
            }
            else
            {
                System.out.println (clip.content.getClass ());
            }
        }
    }


    /**
     * Calculate the play rate of the media item in Reaper from the a list of warp events. Since
     * there is only one play rate the logic takes only the first 2 warp events into account.
     *
     * @param events The warp events
     * @param sourceIsBeats True if the time is in beats otherwise in seconds
     * @return The play rate (1 = normal playback, < 1 slower, > 1 faster)
     */
    private double calcPlayrate (final List<Warp> events, final boolean sourceIsBeats)
    {
        // Can only map a simple warp to one play rate
        if (events.size () < 2)
            return 1;

        // First warp must be at the beginning
        final Warp warp = events.get (0);
        if (warp.time != 0 || warp.warped != 0)
            return 1;

        // Calculate play rate from the 2nd, if there are more warps the result will also not be
        // correct
        final Warp warp2 = events.get (1);
        final double duration = sourceIsBeats ? this.toTime (warp2.time) : warp2.time;
        return warp2.warped / duration;
    }


    /**
     * Convert an audio clip.
     *
     * @param audio The audio file
     * @param itemChunk The item chunk
     * @param playrate The play rate of the media item
     */
    private void convertAudio (final Audio audio, final Chunk itemChunk, final double playrate)
    {
        if (audio.file == null)
            return;

        final File sourceFile = new File (audio.file.path);
        this.audioFiles.add (audio.file.path);

        // field 1, float, play rate
        // field 2, integer (boolean), preserve pitch while changing rate
        // field 3, float, pitch adjust, in semitones.cents
        // field 4, integer, pitch shifting/time stretch mode and preset: -1 - project default
        addNode (itemChunk, ITEM_PLAYRATE, Double.toString (playrate), "1", "0.000", "-1");

        final Chunk sourceChunk = addChunk (itemChunk, CHUNK_ITEM_SOURCE, "WAVE");
        addNode (sourceChunk, SOURCE_FILE, sourceFile.getName ());
    }


    /**
     * Convert a MIDI clip.
     *
     * @param clip The clip to convert
     * @param notes The notes of the clip
     * @param itemChunk The chunk of the media item to fill
     */
    private void convertMIDI (final Clip clip, Notes notes, final Chunk itemChunk)
    {
        final Chunk sourceChunk = addChunk (itemChunk, CHUNK_ITEM_SOURCE, "MIDI");

        // TODO Implement MIDI clip conversion
    }


    /**
     * Convert all lanes. Assigns the Markers data to different Reaper settings.
     * 
     * @param sourceIsBeats If true the source time base is in beats otherwise seconds
     */
    private void convertLanes (final boolean sourceIsBeats)
    {
        if (this.arrangementLanes == null || this.arrangementLanes.lanes == null)
            return;

        for (final Timeline timeline: this.arrangementLanes.lanes)
        {
            if (timeline instanceof final Markers markers)
            {
                if (markers.markers == null)
                    continue;

                final boolean isBeats = updateIsBeats (markers, sourceIsBeats);

                for (int i = 0; i < markers.markers.size (); i++)
                {
                    final Marker marker = markers.markers.get (i);
                    final double position = this.handleTime (marker.time, isBeats);
                    final int color = marker.color == null ? 0 : fromHexColor (marker.color);
                    addNode (this.rootChunk, PROJECT_MARKER, Integer.toString (i), Double.toString (position), marker.name, "0", Integer.toString (color));
                }
            }
            else if (timeline instanceof final Lanes lanes && lanes.track != null)
            {
                final Track track = lanes.track;
                final Chunk trackChunk = track == this.masterTrack ? this.rootChunk : this.chunkMapping.get (track);
                if (trackChunk != null)
                {
                    for (final Timeline trackTimeline: lanes.lanes)
                    {
                        final boolean isBeats = updateIsBeats (trackTimeline, sourceIsBeats);

                        if (trackTimeline instanceof final Points trackEnvelope)
                            this.handleEnvelopeParameter (track, trackChunk, trackEnvelope, isBeats);
                        else if (trackTimeline instanceof final Clips clips)
                            this.convertItems (trackChunk, clips, 0, 0, 0, isBeats);
                    }
                }
            }
        }

        this.createTempoSignatureEnvelope (sourceIsBeats);
    }


    /**
     * Combines the tempo and signature automation into the Reaper tempo envelope.
     *
     * @param sourceIsBeats If true the source time base is in beats otherwise seconds
     */
    private void createTempoSignatureEnvelope (final boolean sourceIsBeats)
    {
        // Combine tempo and signature automation envelopes
        final Map<Double, List<Point>> combined = new TreeMap<> ();
        if (this.tempoEnvelope != null)
        {
            final boolean isBeats = updateIsBeats (this.tempoEnvelope, sourceIsBeats);
            for (final Point point: this.tempoEnvelope.points)
                combined.put (this.handleTime (point.time, isBeats), new ArrayList<> (Collections.singleton (point)));
        }
        if (this.signatureEnvelope != null)
        {
            final boolean isBeats = updateIsBeats (this.signatureEnvelope, sourceIsBeats);
            for (final Point point: this.signatureEnvelope.points)
                combined.computeIfAbsent (this.handleTime (point.time, isBeats), key -> new ArrayList<> (Collections.singleton (new RealPoint ()))).add (point);
        }
        if (combined.isEmpty ())
            return;

        final Chunk tempoChunk = addChunk (this.rootChunk, PROJECT_TEMPO_ENVELOPE);

        Double tempoValue = this.tempo;
        int numeratorValue = this.numerator;
        int denominatorValue = this.denominator;

        for (final Entry<Double, List<Point>> e: combined.entrySet ())
        {
            final Double time = e.getKey ();
            final Double position = this.handleTime (time, sourceIsBeats);
            final List<Point> points = e.getValue ();
            final RealPoint tempoPoint = (RealPoint) points.get (0);
            if (tempoPoint.value != null)
                tempoValue = tempoPoint.value;
            if (points.size () == 2)
            {
                final TimeSignaturePoint signature = (TimeSignaturePoint) points.get (1);
                numeratorValue = signature.numerator.intValue ();
                denominatorValue = signature.denominator.intValue ();
            }
            final String signatureValue = Integer.toString ((denominatorValue << 16) + numeratorValue);
            addNode (tempoChunk, ENVELOPE_POINT, position.toString (), tempoValue.toString (), "0", signatureValue);
        }
    }


    private void handleEnvelopeParameter (final Track track, final Chunk trackChunk, final Points envelope, final boolean sourceIsBeats)
    {
        // Track Volume Parameter
        if (track.volume == envelope.target.parameter)
        {
            final String envelopeName = track == this.masterTrack ? MASTER_VOLUME_ENVELOPE : TRACK_VOLUME_ENVELOPE;
            this.createEnvelope (trackChunk, envelopeName, envelope, true, sourceIsBeats);
            return;
        }

        // Track Panorama Parameter
        if (track.pan == envelope.target.parameter)
        {
            final String envelopeName = track == this.masterTrack ? MASTER_PANORAMA_ENVELOPE : TRACK_PANORAMA_ENVELOPE;
            this.createEnvelope (trackChunk, envelopeName, envelope, true, sourceIsBeats);
            return;
        }

        // Track Mute Parameter
        if (track.mute == envelope.target.parameter)
        {
            this.createEnvelope (trackChunk, TRACK_MUTE_ENVELOPE, envelope, false, sourceIsBeats);
            return;
        }

        // Track Sends Parameter
        if (track.sends != null)
        {
            for (final Send send: track.sends)
            {
                if (send == envelope.target.parameter)
                {
                    final Chunk auxChunk = this.chunkMapping.get (send.destination);
                    if (auxChunk != null)
                        this.createEnvelope (auxChunk, TRACK_AUX_ENVELOPE, envelope, true, sourceIsBeats);
                    return;
                }
            }
        }

        // Transport Tempo and Signature Parameter needs to be integrated if both are found
        final Transport transport = this.project.transport;
        if (transport == null)
            return;
        if (transport.tempo == envelope.target.parameter)
            this.tempoEnvelope = envelope;
        else if (transport.timeSignature == envelope.target.parameter)
            this.signatureEnvelope = envelope;
    }


    private void createEnvelope (final Chunk trackChunk, final String envelopeName, final Points trackEnvelope, final boolean interpolate, final boolean sourceIsBeats)
    {
        final Chunk envelopeChunk = addChunk (trackChunk, envelopeName);

        final boolean isBeats = updateIsBeats (trackEnvelope, sourceIsBeats);

        for (final Point point: trackEnvelope.points)
        {
            if (point.time == null)
                continue;

            final Double position = this.handleTime (point.time, isBeats);

            String value = null;
            // Note: The unit of the value must already been matched at the parameter itself
            if (point instanceof final RealPoint realPoint && realPoint.value != null)
                value = realPoint.value.toString ();
            else if (point instanceof final IntegerPoint integerPoint && integerPoint.value != null)
                value = integerPoint.value.toString ();
            else if (point instanceof final BoolPoint boolPoint && boolPoint.value != null)
                value = boolPoint.value.booleanValue () ? "1" : "0";

            if (value != null)
                addNode (envelopeChunk, ENVELOPE_POINT, position.toString (), value, interpolate ? "0" : "1");
        }
    }


    /**
     * Set the basic track information, like structure, name and color.
     *
     * @param trackChunk The track chunk to add the information
     * @param trackOrFolder The dawproject object
     * @param type The folder type
     * @param direction The level direction
     */
    private static void createTrack (final Chunk trackChunk, final TrackOrFolder trackOrFolder, final int type, final int direction)
    {
        addNode (trackChunk, TRACK_NAME, trackOrFolder.name);
        addNode (trackChunk, TRACK_STRUCTURE, Integer.toString (type), Integer.toString (direction));
        if (trackOrFolder.color != null)
            addNode (trackChunk, TRACK_COLOR, Integer.toString (fromHexColor (trackOrFolder.color)));
    }


    /**
     * Convert the track hierarchy into a flat list in which two parameters indicate the track state
     * (1 = start track, 2 = end of track) and a direction the number of levels to move into our out
     * of folders (1 = move into a folder, -X = X number of levels to move up).
     *
     * @param tracks The current list of sub-folders and -tracks.
     * @param flatTracks The list with all flat tracks so far
     * @param isTop True if this is the top level
     */
    private void createTrackStructure (final List<TrackOrFolder> tracks, final List<TrackInfo> flatTracks, final boolean isTop)
    {
        final int size = tracks.size ();
        for (int i = 0; i < size; i++)
        {
            final TrackOrFolder trackOrFolder = tracks.get (i);
            final TrackInfo trackInfo = new TrackInfo ();
            flatTracks.add (trackInfo);

            if (trackOrFolder instanceof final FolderTrack folder)
            {
                trackInfo.folder = folder;

                if (folder.tracks == null)
                {
                    trackInfo.type = 2;
                }
                else
                {
                    final List<TrackOrFolder> children = new ArrayList<> (folder.tracks);

                    // Find the track among the child tracks which acts as the mix master for the
                    // folder, this will be combined with the parent folder
                    for (final TrackOrFolder child: folder.tracks)
                    {
                        if (child instanceof final Track childTrack && childTrack.mixerRole == MixerRole.master)
                        {
                            trackInfo.track = childTrack;
                            children.remove (childTrack);
                            break;
                        }
                    }

                    trackInfo.type = children.isEmpty () ? 2 : 1;
                    if (trackInfo.type == 1)
                        trackInfo.direction = 1;

                    this.createTrackStructure (children, flatTracks, false);
                }
            }
            else if (trackOrFolder instanceof final Track track)
            {
                trackInfo.track = track;
            }
        }

        // Increase the number of levels to move up, but do not move out of the top level
        if (!flatTracks.isEmpty () && !isTop)
        {
            final TrackInfo trackInfo = flatTracks.get (flatTracks.size () - 1);
            trackInfo.direction--;
            trackInfo.type = 2;
        }
    }


    /**
     * Creates the Reaper device ID for the VST chunk.
     *
     * @param device The device for which to create an ID
     * @return The ID
     */
    private static String createDeviceID (final Device device)
    {
        final StringBuilder id = new StringBuilder ();
        if (device instanceof Vst2Plugin)
        {
            final StringBuilder fakeVst3ID = new StringBuilder ("VST");
            // VST2 ID transformed to ASCII text
            final int vstID = Integer.parseInt (device.deviceID);
            fakeVst3ID.append (intToText (vstID));
            // First 9 lower case characters of the device name
            String fakeID = fakeVst3ID.append (device.name.toLowerCase (Locale.US)).toString ();
            fakeID = fakeID.substring (0, Math.min (16, fakeID.length ()));

            id.append (device.deviceID).append ("<");
            for (int i = 0; i < 16; i++)
                id.append (i < fakeID.length () ? Integer.toHexString (fakeID.charAt (i)) : "00");
            id.append (">");
        }
        else if (device instanceof Vst3Plugin)
            id.append ("{").append (device.deviceID).append ("}");
        return id.toString ();
    }


    /**
     * Creates the Reaper device name for the VST chunk.
     *
     * @param device The device for which to create a name
     * @return The name
     */
    private static String createDeviceName (final Device device)
    {
        final StringBuilder name = new StringBuilder ();
        if (device instanceof Vst2Plugin)
            name.append (PLUGIN_VST_2);
        else if (device instanceof Vst3Plugin)
            name.append (PLUGIN_VST_3);
        name.append (": ").append (device.deviceName).append (" (").append (device.deviceVendor).append (")");
        return name.toString ();
    }


    /**
     * Creates a device chunk. Currently supports VST2 and VST3.
     *
     * @param deviceChunk The device chunk
     * @param device The VST device
     * @param in The input stream from which to read the devices' state
     * @throws IOException Could not read the device state
     */
    private static void handleVstDevice (final Chunk deviceChunk, final Device device, final InputStream in) throws IOException
    {
        if (device instanceof final Vst2Plugin)
            handleVst2Device (deviceChunk, in);
        else if (device instanceof final Vst3Plugin)
            handleVst3Device (deviceChunk, in);
    }


    /**
     * Fill the VST 2 chunk.
     *
     * @param vstChunk THe chunk to fill
     * @param in The input stream from which to read the VST 2 device state
     * @throws IOException Could not read the state
     */
    private static void handleVst2Device (final Chunk vstChunk, final InputStream in) throws IOException
    {
        final VstChunkHandler vstChunkHandler = new VstChunkHandler ();
        vstChunkHandler.readVST2Preset (in);
        vstChunkHandler.create (vstChunk);
    }


    /**
     * Fill the VST 3 chunk.
     *
     * @param vstChunk THe chunk to fill
     * @param in The input stream from which to read the VST 3 device state
     * @throws IOException Could not read the state
     */
    private static void handleVst3Device (final Chunk vstChunk, final InputStream in) throws IOException
    {
        final VstChunkHandler vstChunkHandler = new VstChunkHandler ();
        vstChunkHandler.readVST3Preset (in);
        vstChunkHandler.create (vstChunk);
    }


    /**
     * Create and add a chunk to a parent chunk.
     *
     * @param parentChunk The parent chunk to which to add the new chunk
     * @param childName The name of the new chunk
     * @param parameters The parameters to add to the chunk
     * @return The newly created chunk
     */
    private static Chunk addChunk (final Chunk parentChunk, final String childName, final String... parameters)
    {
        return (Chunk) addNode (parentChunk, new Chunk (), childName, parameters);
    }


    /**
     * Create and add a node to a parent chunk.
     *
     * @param parentChunk The parent chunk to which to add the new node
     * @param childName The name of the new node
     * @param parameters The parameters to add to the node
     * @return The newly created node
     */
    private static Node addNode (final Chunk parentChunk, final String childName, final String... parameters)
    {
        return addNode (parentChunk, new Node (), childName, parameters);
    }


    /**
     * Add a node to a parent chunk.
     *
     * @param parentChunk The parent chunk to which to add the new node
     * @param child The child node to add to the parent chunk
     * @param childName The name to set for the child node
     * @param parameters The parameters to add to the node
     * @return The added node
     */
    private static Node addNode (final Chunk parentChunk, final Node child, final String childName, final String... parameters)
    {
        parentChunk.addChildNode (child);
        setNode (child, childName, parameters);
        return child;
    }


    /**
     * Set the name and parameters of a node.
     *
     * @param node The node
     * @param nodeName The name to set for the node
     * @param parameters The parameters to add to the node
     */
    private static void setNode (final Node node, final String nodeName, final String... parameters)
    {
        node.setName (nodeName);
        node.getParameters ().addAll (Arrays.asList (parameters));
    }


    /**
     * Parse a hex string to a ARGB color.
     *
     * @param hexColor The hex color to parse
     * @return The color
     */
    private static int fromHexColor (final String hexColor)
    {
        final int c = Integer.decode (hexColor).intValue ();
        final int r = c & 0xFF;
        final int g = c >> 8 & 0xFF;
        final int b = c >> 16 & 0xFF;
        return (r << 16) + (g << 8) + b + 0x01000000;
    }


    private static String intToText (final int number)
    {
        final StringBuilder sb = new StringBuilder (4);
        for (int i = 3; i >= 0; i--)
            sb.append ((char) (number >> 8 * i & 0xFF));
        return sb.toString ();
    }


    private Double handleTime (final Double time, final boolean sourceIsBeats)
    {
        return Double.valueOf (time == null ? 0 : handleTime (time.doubleValue (), sourceIsBeats));
    }


    private double handleTime (final double time, final boolean sourceIsBeats)
    {
        if (sourceIsBeats == this.destinationIsBeats)
            return time;
        return sourceIsBeats ? this.toTime (time) : this.toBeats (time);
    }


    private static boolean updateIsBeats (final Timeline timeline, final boolean isBeats)
    {
        return timeline.timebase == null ? isBeats : timeline.timebase == Timebase.beats;
    }


    /**
     * Convert the time value to beats.
     *
     * @param timeInSeconds The value in time
     * @return The value in beats
     */
    private double toBeats (final double timeInSeconds)
    {
        final double beatsPerSecond = this.tempo.doubleValue () / 60.0;
        return beatsPerSecond * timeInSeconds;
    }


    /**
     * Convert the beats value to time.
     *
     * @param timeInBeats The value in beats
     * @return The value in time
     */
    private double toTime (final double timeInBeats)
    {
        final double beatsPerSecond = this.tempo.doubleValue () / 60.0;
        return timeInBeats / beatsPerSecond;
    }
}
