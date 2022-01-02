// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.dawconverters.reaper;

import de.mossgrabers.dawconverters.reaper.project.Chunk;
import de.mossgrabers.dawconverters.reaper.project.Node;
import de.mossgrabers.dawconverters.reaper.project.ReaperMidiEvent;
import de.mossgrabers.dawconverters.reaper.project.VstChunkHandler;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.BoolParameter;
import com.bitwig.dawproject.DawProject;
import com.bitwig.dawproject.ExpressionType;
import com.bitwig.dawproject.FileReference;
import com.bitwig.dawproject.FolderTrack;
import com.bitwig.dawproject.Interpolation;
import com.bitwig.dawproject.Metadata;
import com.bitwig.dawproject.MixerRole;
import com.bitwig.dawproject.Parameter;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.RealParameter;
import com.bitwig.dawproject.Referencable;
import com.bitwig.dawproject.Send;
import com.bitwig.dawproject.SendType;
import com.bitwig.dawproject.TimeSignatureParameter;
import com.bitwig.dawproject.TimelineRole;
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
import com.bitwig.dawproject.timeline.Note;
import com.bitwig.dawproject.timeline.Notes;
import com.bitwig.dawproject.timeline.Point;
import com.bitwig.dawproject.timeline.Points;
import com.bitwig.dawproject.timeline.RealPoint;
import com.bitwig.dawproject.timeline.TimeSignaturePoint;
import com.bitwig.dawproject.timeline.Timebase;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFileFormat.Type;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Converts a Reaper project file (the already loaded chunks to be more specific) into a dawproject
 * structure.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public class ReaperToDawProjectConverter extends ReaperTags
{
    private static final Pattern PATTERN_VST_DESCRIPTION = Pattern.compile ("(VST|VSTi|VST3|VST3i)?:\\s(.*)\\s\\((.*)\\)");
    private static final Pattern PATTERN_VST2_ID         = Pattern.compile ("(.*)<.*");
    private static final Pattern PATTERN_VST3_ID         = Pattern.compile (".*\\{(.*)\\}");


    private enum MidiBytes
    {
        ONE,
        TWO,
        BOTH
    }


    private final Deque<List<TrackOrFolder>> folderStack      = new LinkedList<> ();
    private List<TrackOrFolder>              folderTracks;
    private final File                       sourcePath;
    private final Map<File, String>          embeddedFiles    = new HashMap<> ();

    private final Metadata                   metadata         = new Metadata ();
    private final Project                    project          = new Project ();
    private final Lanes                      arrangementLanes = new Lanes ();

    private final Map<Integer, List<Send>>   sendMapping      = new HashMap<> ();
    private final Map<Track, Lanes>          trackLanesMap    = new HashMap<> ();
    private final Map<Send, Chunk>           sendChunkMapping = new HashMap<> ();

    private boolean                          sourceIsBeats;
    private boolean                          sourceIsEnvelopeBeats;
    private boolean                          destinationIsBeats;


    /**
     * Constructor.
     *
     * @param sourcePath The path where the source project is located.
     * @param rootChunk The already parsed chunk structure
     * @param destinationTimebase The time base to use for the destination project, the time base of
     *            the source project will be used if set to null
     * @throws ParseException Could not parse Reaper project file
     */
    public ReaperToDawProjectConverter (final File sourcePath, final Chunk rootChunk, final Timebase destinationTimebase) throws ParseException
    {
        Referencable.resetID ();

        this.sourcePath = sourcePath;
        this.folderTracks = this.project.tracks;

        this.project.application.name = "Cockos Reaper";
        final List<String> parameters = rootChunk.getParameters ();
        this.project.application.version = parameters.size () > 1 ? parameters.get (1) : "Unknown";

        this.convertMetadata (rootChunk);

        this.convertArrangement (rootChunk);
        this.destinationIsBeats = destinationTimebase == null ? this.sourceIsBeats : destinationTimebase == Timebase.beats;

        this.convertTransport (rootChunk);
        this.convertMarkers (rootChunk);
        this.convertMaster (rootChunk);
        this.convertTracks (rootChunk);
    }


    /**
     * Stores the filled dawproject structure into the given file.
     *
     * @param outputFile The file to store the structure to
     * @throws IOException Could not write the file
     */
    public void saveProject (final File outputFile) throws IOException
    {
        DawProject.save (this.project, this.metadata, this.embeddedFiles, outputFile);
    }


    /**
     * Fills the metadata description file.
     *
     * @param rootChunk The project root chunk
     */
    private void convertMetadata (final Chunk rootChunk)
    {
        // Get the author and comment settings from the project (File -> Project settings...)
        final Optional<Node> authorParameter = rootChunk.getChildNode (PROJECT_AUTHOR);
        if (authorParameter.isPresent ())
        {
            final List<String> parameters = authorParameter.get ().getParameters ();
            if (!parameters.isEmpty ())
            {
                String author = parameters.get (0);
                if (!author.isBlank ())
                {
                    if (author.length () > 1 && author.startsWith ("\"") && author.endsWith ("\""))
                        author = author.substring (1, author.length () - 2);
                    this.metadata.artist = author;
                    this.metadata.producer = author;
                    this.metadata.writer = author;
                }
            }
        }
        final Optional<Node> notesParameter = rootChunk.getChildNode (PROJECT_NOTES);
        if (notesParameter.isPresent () && notesParameter.get ()instanceof final Chunk notesChunk)
        {
            final StringBuilder comment = new StringBuilder ();
            for (final Node commentLine: notesChunk.getChildNodes ())
            {
                final String line = commentLine.getLine ();
                final int pos = line.indexOf ('|');
                if (pos != -1)
                {
                    if (!comment.isEmpty ())
                        comment.append ("\r\n");
                    comment.append (line.substring (pos + 1, line.length ()));
                }
            }
            this.metadata.comment = comment.toString ();
        }

        // Use metadata from the render metadata setting (File -> Project Render Metadata)
        final Optional<Node> renderMetadataParameter = rootChunk.getChildNode (PROJECT_RENDER_METADATA);
        if (renderMetadataParameter.isEmpty ())
            return;

        final Node node = renderMetadataParameter.get ();
        if (node instanceof final Chunk renderMetadataChunk)
        {
            for (final Node tagNode: renderMetadataChunk.getChildNodes ())
                this.handleMetadataTag (tagNode);
        }
    }


    /**
     * Check if some useful metadata can be extracted from a render chunk.
     *
     * @param tagNode A sub node of a render chunk
     */
    private void handleMetadataTag (final Node tagNode)
    {
        if (!"TAG".equals (tagNode.getName ()))
            return;

        final List<String> parameters = tagNode.getParameters ();
        if (parameters.size () < 2)
            return;

        String value = parameters.get (1);
        if (value.length () > 1 && value.startsWith ("\"") && value.endsWith ("\""))
            value = value.substring (1, value.length () - 2);

        switch (parameters.get (0))
        {
            case "ID3:COMM":
                this.metadata.comment = value;
                break;
            case "ID3:TCOM":
                this.metadata.writer = value;
                break;
            case "ID3:TCON":
                this.metadata.genre = value;
                break;
            case "ID3:TCOP":
                this.metadata.copyright = value;
                break;
            case "ID3:TIPL":
                this.metadata.producer = value;
                break;
            case "ID3:TIT2":
                this.metadata.title = value;
                break;
            case "ID3:TPE1":
                this.metadata.artist = value;
                break;
            case "ID3:TPE2":
                this.metadata.originalArtist = value;
                break;
            case "ID3:TYER":
                this.metadata.year = value;
                break;
            default:
                // No more supported
                break;
        }
    }


    /**
     * Fill the arrangement structure.
     *
     * @param rootChunk The root chunk
     */
    private void convertArrangement (final Chunk rootChunk)
    {
        final Arrangement arrangement = new Arrangement ();
        this.project.arrangement = arrangement;
        arrangement.content = this.arrangementLanes;

        final Optional<Node> timelockModeNode = rootChunk.getChildNode (PROJECT_TIME_LOCKMODE);
        this.arrangementLanes.timebase = getIntParam (timelockModeNode, 1) == 0 ? Timebase.seconds : Timebase.beats;
        this.sourceIsBeats = this.arrangementLanes.timebase == Timebase.beats;

        final Optional<Node> timelockEnvelopeModeNode = rootChunk.getChildNode (PROJECT_TIME_ENV_LOCKMODE);
        this.sourceIsEnvelopeBeats = getIntParam (timelockEnvelopeModeNode, 1) == 1;

        // Time values are always in seconds, indicators above seem to be only for visual
        // information
        this.sourceIsBeats = false;
        this.sourceIsEnvelopeBeats = false;
    }


    /**
     * Create all markers.
     *
     * @param rootChunk The root chunk
     */
    private void convertMarkers (final Chunk rootChunk)
    {
        final Markers cueMarkers = new Markers ();
        this.arrangementLanes.lanes.add (cueMarkers);

        for (final Node node: rootChunk.getChildNodes ())
        {
            // Is it a simple marker?
            if (!PROJECT_MARKER.equals (node.getName ()) || getIntParam (node, 3, 0) > 0)
                continue;

            // If the marker has no name use the index
            String name = getParam (node, 2, "");
            if (name.isBlank () || "\"\"".equals (name))
                name = getParam (node, 0, "0");

            final Marker marker = new Marker ();
            marker.time = this.handleTime (getDoubleParam (node, 1, 0), false);
            marker.name = name;
            final int c = getIntParam (node, 4, 0);
            if (c > 0)
                marker.color = toHexColor (c);
            cueMarkers.markers.add (marker);
        }
    }


    /**
     * Fill the transport structure.
     *
     * @param rootChunk The root chunk
     */
    private void convertTransport (final Chunk rootChunk)
    {
        this.project.transport = new Transport ();
        final TimeSignatureParameter timeSignatureParameter = new TimeSignatureParameter ();
        this.project.transport.timeSignature = timeSignatureParameter;

        final Optional<Node> parameter = rootChunk.getChildNode (PROJECT_TEMPO);
        if (parameter.isPresent ())
        {
            final double [] transParams = getDoubleParams (parameter, -1);
            if (transParams.length > 0)
                this.project.transport.tempo = createRealParameter (Unit.linear, 1.0, 960.0, transParams[0]);

            if (transParams.length >= 3)
            {
                timeSignatureParameter.numerator = Integer.valueOf ((int) transParams[1]);
                timeSignatureParameter.denominator = Integer.valueOf ((int) transParams[2]);
            }
        }
    }


    /**
     * Fill the master track structure.
     *
     * @param rootChunk The root chunk
     * @throws ParseException Could not parse the master
     */
    private void convertMaster (final Chunk rootChunk) throws ParseException
    {
        final Track masterTrack = new Track ();
        this.project.tracks.add (masterTrack);
        masterTrack.name = "Master";
        masterTrack.mixerRole = MixerRole.master;

        final int numberOfChannels = getIntParam (rootChunk.getChildNode (MASTER_NUMBER_OF_CHANNELS), -1);
        masterTrack.audioChannels = Integer.valueOf (numberOfChannels > 0 ? numberOfChannels : 2);

        // Volume & Panorama
        final double [] volPan = getDoubleParams (rootChunk.getChildNode (MASTER_VOLUME_PAN), -1);
        if (volPan.length >= 1)
        {
            masterTrack.volume = createRealParameter (Unit.linear, 0.0, 1.0, Math.min (1, valueToDB (volPan[0], 0)));
            masterTrack.pan = createRealParameter (Unit.linear, -1.0, 1.0, volPan[1]);
        }

        // Mute & Solo
        final int muteSolo = getIntParam (rootChunk.getChildNode (MASTER_MUTE_SOLO), -1);
        if (muteSolo > 0)
        {
            masterTrack.mute = new BoolParameter ();
            masterTrack.mute.value = Boolean.valueOf ((muteSolo & 1) > 0);
            masterTrack.solo = Boolean.valueOf ((muteSolo & 2) > 0);
        }

        // Set track color
        final int color = getIntParam (rootChunk.getChildNode (MASTER_COLOR), -1);
        if (color >= 0)
            masterTrack.color = toHexColor (color);

        // Convert all FX devices
        masterTrack.devices = this.convertDevices (masterTrack, rootChunk, MASTER_CHUNK_FXCHAIN);

        final Lanes masterTrackLanes = this.createTrackLanes (masterTrack);
        this.convertAutomation (masterTrack, rootChunk, MASTER_VOLUME_ENVELOPE, masterTrack.volume, true);
        this.convertAutomation (masterTrack, rootChunk, MASTER_PANORAMA_ENVELOPE, masterTrack.pan, true);
        this.convertTempoAutomation (rootChunk, masterTrackLanes);
    }


    /**
     * Fill the track structure.
     *
     * @param rootChunk The root chunk
     * @throws ParseException Could not parse the tracks
     */
    private void convertTracks (final Chunk rootChunk) throws ParseException
    {
        final List<Track> tracks = new ArrayList<> ();
        for (final Node node: rootChunk.getChildNodes ())
        {
            if (node instanceof final Chunk subChunk && CHUNK_TRACK.equals (subChunk.getName ()))
                tracks.add (this.convertTrack (subChunk));
        }

        // In the second run assign the collected sends
        for (int i = 0; i < tracks.size (); i++)
        {
            final Track track = tracks.get (i);
            track.sends = this.sendMapping.get (Integer.valueOf (i));

            if (track.sends != null)
            {
                for (final Send send: track.sends)
                    this.convertAutomation (track, this.sendChunkMapping.get (send), TRACK_AUX_ENVELOPE, send, true);
            }
        }
    }


    /**
     * Fill the track structure.
     *
     * @param trackChunk The track chunk
     * @return The created track
     * @throws ParseException Could not parse the track info
     */
    private Track convertTrack (final Chunk trackChunk) throws ParseException
    {
        final var track = new Track ();

        // Set track name
        final Optional<Node> nameNode = trackChunk.getChildNode (TRACK_NAME);
        track.name = nameNode.isPresent () ? nameNode.get ().getParameters ().get (0) : "Track";

        // Set track color
        final int color = getIntParam (trackChunk.getChildNode (TRACK_COLOR), -1);
        if (color >= 0)
            track.color = toHexColor (color);

        // track.comment - Track comment neither in Bitwig nor Reaper

        // track.loaded - no loaded state in Reaper

        final int numberOfChannels = getIntParam (trackChunk.getChildNode (TRACK_NUMBER_OF_CHANNELS), -1);
        track.audioChannels = Integer.valueOf (numberOfChannels > 0 ? numberOfChannels : 2);

        // Create and store Sends for assignment in second phase
        final List<Node> auxReceive = trackChunk.getChildNodes (TRACK_AUX_RECEIVE);
        if (auxReceive.isEmpty ())
        {
            // Reaper tracks are always hybrid
            track.timelineRole = new TimelineRole []
            {
                TimelineRole.notes,
                TimelineRole.audio
            };
        }
        else
        {
            for (final Node sendNode: auxReceive)
            {
                final int trackIndex = getIntParam (sendNode, 0, 0);
                final int mode = getIntParam (sendNode, 1, 0);
                final double sendVolume = getDoubleParam (sendNode, 2, 1);

                final Send send = Send.create (valueToDB (sendVolume, 12), Unit.linear);
                send.name = "Send";
                send.min = Double.valueOf (0);
                send.max = Double.valueOf (1);
                send.type = mode == 0 ? SendType.post : SendType.pre;
                send.destination = track;
                this.sendMapping.computeIfAbsent (Integer.valueOf (trackIndex), key -> new ArrayList<> ()).add (send);
                this.sendChunkMapping.put (send, trackChunk);

                track.mixerRole = MixerRole.returnTrack;
                track.timelineRole = new TimelineRole []
                {
                    TimelineRole.audio
                };
            }
        }

        // track.destination -> too much options in Reaper to support this with a single destination

        // Volume & Panorama
        final double [] volPan = getDoubleParams (trackChunk.getChildNode (TRACK_VOLUME_PAN), -1);
        if (volPan.length >= 1)
        {
            track.volume = createRealParameter (Unit.linear, 0.0, 1.0, valueToDB (volPan[0], 0));
            track.pan = createRealParameter (Unit.linear, -1.0, 1.0, volPan[1]);
        }

        // Mute & Solo
        final int [] muteSolo = getIntParams (trackChunk.getChildNode (TRACK_MUTE_SOLO), -1);
        if (muteSolo.length > 0)
        {
            track.mute = new BoolParameter ();
            track.mute.value = Boolean.valueOf (muteSolo[0] > 0);
        }
        if (muteSolo.length > 1)
            track.solo = Boolean.valueOf (muteSolo[1] > 0);

        // Folder handling
        final Optional<Node> structureNode = trackChunk.getChildNode (TRACK_STRUCTURE);
        if (structureNode.isEmpty ())
            return track;

        final int [] structure = getIntParams (structureNode, -1);
        if (structure.length == 2)
        {
            switch (structure[0])
            {
                // A top level track or track in a folder
                default:
                case 0:
                    this.folderTracks.add (track);
                    break;

                // Folder track
                case 1:
                    // Folder tracks are stored as a folder and a master track, which is inside of
                    // the folder
                    track.mixerRole = MixerRole.master;

                    final FolderTrack folderTrack = new FolderTrack ();
                    folderTrack.name = track.name;
                    track.name = track.name + " Master";
                    folderTrack.comment = track.comment;
                    folderTrack.color = track.color;
                    this.folderTracks.add (folderTrack);
                    this.folderStack.add (this.folderTracks);
                    this.folderTracks = folderTrack.tracks;
                    this.folderTracks.add (track);
                    break;

                // Last track in the folder
                case 2:
                    this.folderTracks.add (track);
                    if (this.folderStack.isEmpty ())
                        throw new ParseException ("Unsound folder structure.", 0);
                    for (int i = 0; i < Math.abs (structure[1]); i++)
                        this.folderTracks = this.folderStack.removeLast ();
                    break;
            }
        }

        final Lanes trackLanes = this.createTrackLanes (track);

        // Convert all FX devices
        track.devices = this.convertDevices (track, trackChunk, CHUNK_FXCHAIN);

        this.convertItems (trackLanes, trackChunk);
        this.convertAutomation (track, trackChunk);

        return track;
    }


    /**
     * Fill the envelope structure.
     *
     * @param track The track to add the media item clips
     * @param trackChunk The track chunk
     */
    private void convertAutomation (final Track track, final Chunk trackChunk)
    {
        this.convertAutomation (track, trackChunk, TRACK_VOLUME_ENVELOPE, track.volume, true);
        this.convertAutomation (track, trackChunk, TRACK_PANORAMA_ENVELOPE, track.pan, true);
        this.convertAutomation (track, trackChunk, TRACK_MUTE_ENVELOPE, track.mute, false);
    }


    private void convertAutomation (final Track track, final Chunk trackChunk, final String envelopeName, final Parameter parameter, final boolean interpolate)
    {
        final Optional<Node> envelopeNode = trackChunk.getChildNode (envelopeName);
        if (envelopeNode.isPresent () && envelopeNode.get ()instanceof final Chunk envelopeChunk)
        {
            final Lanes lanes = this.trackLanesMap.get (track);
            if (lanes == null)
                return;
            final Points envelope = new Points ();
            lanes.lanes.add (envelope);

            if (interpolate)
            {
                envelope.unit = Unit.linear;
                envelope.interpolation = Interpolation.linear;
            }
            else
                envelope.interpolation = Interpolation.hold;

            envelope.target.parameter = parameter;

            for (final Node pointNode: envelopeChunk.getChildNodes (ENVELOPE_POINT))
            {
                final Point point = interpolate ? new RealPoint () : new BoolPoint ();
                final double timeValue = getDoubleParam (pointNode, 0, 0);
                point.time = Double.valueOf (this.handleTime (timeValue, true));

                if (interpolate)
                    ((RealPoint) point).value = Double.valueOf (getDoubleParam (pointNode, 1, 0));
                else
                    ((BoolPoint) point).value = Boolean.valueOf (getDoubleParam (pointNode, 1, 0) > 0);
                envelope.points.add (point);
            }
        }
    }


    private void convertTempoAutomation (final Chunk rootChunk, final Lanes masterTrackLanes)
    {
        final Optional<Node> envelopeNode = rootChunk.getChildNode (PROJECT_TEMPO_ENVELOPE);
        if (envelopeNode.isPresent () && envelopeNode.get ()instanceof final Chunk envelopeChunk)
        {
            final Points tempoEnvelope = new Points ();
            masterTrackLanes.lanes.add (tempoEnvelope);
            tempoEnvelope.unit = Unit.bpm;
            tempoEnvelope.interpolation = Interpolation.linear;
            tempoEnvelope.target.parameter = this.project.transport.tempo;

            final Points signatureEnvelope = new Points ();
            signatureEnvelope.interpolation = Interpolation.hold;
            signatureEnvelope.target.parameter = this.project.transport.timeSignature;

            for (final Node pointNode: envelopeChunk.getChildNodes (ENVELOPE_POINT))
            {
                final RealPoint point = new RealPoint ();
                final double timeValue = getDoubleParam (pointNode, 0, 0);
                point.time = Double.valueOf (this.handleTime (timeValue, true));
                point.value = Double.valueOf (getDoubleParam (pointNode, 1, 0));
                tempoEnvelope.points.add (point);

                final int signature = getIntParam (pointNode, 3, 0);
                if (signature > 0)
                {
                    final TimeSignaturePoint timeSigPoint = new TimeSignaturePoint ();
                    timeSigPoint.time = point.time;
                    timeSigPoint.numerator = Integer.valueOf (signature & 0xFFFF);
                    timeSigPoint.denominator = Integer.valueOf (signature >> 16 & 0xFFFF);
                    signatureEnvelope.points.add (timeSigPoint);
                }
            }

            if (!signatureEnvelope.points.isEmpty ())
            {
                masterTrackLanes.lanes.add (signatureEnvelope);

                // Make sure there is a signature marker at position 0
                if (signatureEnvelope.points.get (0).time.doubleValue () > 0)
                {
                    final TimeSignaturePoint timeSigPoint = new TimeSignaturePoint ();
                    timeSigPoint.time = Double.valueOf (0);
                    timeSigPoint.numerator = this.project.transport.timeSignature.numerator;
                    timeSigPoint.denominator = this.project.transport.timeSignature.denominator;
                    signatureEnvelope.points.add (0, timeSigPoint);
                }
            }
        }
    }


    /**
     * Fill the devices structure.
     *
     * @param track The track
     * @param trackChunk The track chunk
     * @param chunkName The name of the FX list chunk
     * @return The list with the parsed devices
     * @throws ParseException Could not parse the track info
     */
    private List<Device> convertDevices (final Track track, final Chunk trackChunk, final String chunkName) throws ParseException
    {
        final List<Device> devices = new ArrayList<> ();

        final Optional<Node> fxChainNode = trackChunk.getChildNode (chunkName);
        if (fxChainNode.isEmpty ())
            return Collections.emptyList ();

        final Node fxNode = fxChainNode.get ();
        if (fxNode instanceof final Chunk fxChainChunk)
        {
            boolean bypass = false;
            boolean offline = false;
            Device device = null;

            for (final Node node: fxChainChunk.getChildNodes ())
            {
                final String nodeName = node.getName ();

                if (FXCHAIN_BYPASS.equals (nodeName))
                {
                    final int [] params = getIntParams (node, 0);
                    bypass = params.length > 0 && params[0] > 0;
                    offline = params.length > 1 && params[1] > 0;
                }
                else if (CHUNK_VST.equals (nodeName) && node instanceof final Chunk vstChunk)
                {
                    device = this.handleFX (vstChunk, bypass, offline);
                    if (device != null)
                        devices.add (device);
                }
                else if (FXCHAIN_PARAMETER_ENVELOPE.equals (nodeName) && node instanceof final Chunk paramEnvChunk)
                {
                    this.createAutomationParameters (track, device, paramEnvChunk);
                }
            }
        }

        return devices;
    }


    private void createAutomationParameters (final Track track, final Device device, final Chunk paramEnvChunk)
    {
        final RealParameter param = new RealParameter ();

        String id = getParam (paramEnvChunk, 0, "0");
        id = id.split (":")[0];
        int paramID;
        try
        {
            paramID = Integer.parseInt (id);
        }
        catch (final NumberFormatException ex)
        {
            paramID = 0;
        }

        param.parameterID = Integer.valueOf (paramID);
        param.min = Double.valueOf (getDoubleParam (paramEnvChunk, 1, 0));
        param.max = Double.valueOf (getDoubleParam (paramEnvChunk, 2, 0));
        param.value = Double.valueOf (getDoubleParam (paramEnvChunk, 3, 0));
        param.unit = Unit.linear;

        device.automatedParameters.add (param);

        final Lanes lanes = this.trackLanesMap.get (track);

        final Points envelope = new Points ();
        lanes.lanes.add (envelope);
        envelope.unit = Unit.linear;
        envelope.interpolation = Interpolation.linear;
        envelope.target.parameter = param;

        for (final Node pointNode: paramEnvChunk.getChildNodes (ENVELOPE_POINT))
        {
            final RealPoint point = new RealPoint ();
            point.time = Double.valueOf (getDoubleParam (pointNode, 0, 0));
            point.value = Double.valueOf (getDoubleParam (pointNode, 1, 0));
            envelope.points.add (point);
        }
    }


    /**
     * Analyze one FX device.
     *
     * @param vstChunk The VST chunk
     * @param offline
     * @param bypass
     * @return The created device
     * @throws ParseException Error during parsing
     */
    private Device handleFX (final Chunk vstChunk, final boolean bypass, final boolean offline) throws ParseException
    {
        final List<String> parameters = vstChunk.getParameters ();
        if (parameters.size () < 5)
            return null;

        final Matcher descMatcher = PATTERN_VST_DESCRIPTION.matcher (parameters.get (0));
        if (!descMatcher.matches ())
            return null;

        final Device device;
        final String fileEnding;
        final Pattern idPattern;
        boolean isVST2;
        switch (descMatcher.group (1))
        {
            case PLUGIN_VST_2:
            case PLUGIN_VST_2_INSTRUMENT:
                device = new Vst2Plugin ();
                fileEnding = ".fxp";
                idPattern = PATTERN_VST2_ID;
                isVST2 = true;
                break;

            case PLUGIN_VST_3:
            case PLUGIN_VST_3_INSTRUMENT:
                device = new Vst3Plugin ();
                fileEnding = ".vstpreset";
                idPattern = PATTERN_VST3_ID;
                isVST2 = false;
                break;

            default:
                // Not supported but should never be reached
                return null;
        }

        final Matcher idMatcher = idPattern.matcher (parameters.get (4));
        if (!idMatcher.matches ())
            return null;

        device.name = descMatcher.group (2);
        // device.pluginVersion -> information not available
        device.deviceName = device.name;
        device.deviceVendor = descMatcher.group (3);
        device.deviceID = idMatcher.group (1);
        device.enabled = new BoolParameter ();
        device.enabled.value = Boolean.valueOf (!bypass);
        device.enabled.name = "On/Off";
        device.loaded = Boolean.valueOf (!offline);

        device.state = new FileReference ();
        final String filename = UUID.randomUUID ().toString () + fileEnding;
        device.state.path = "plugins/" + filename;

        try
        {
            final File tempFile = File.createTempFile ("dawproject-", "-converter");
            tempFile.deleteOnExit ();

            try (final FileOutputStream out = new FileOutputStream (tempFile))
            {
                final VstChunkHandler vstChunkHandler = new VstChunkHandler ();
                vstChunkHandler.parse (vstChunk);
                if (isVST2)
                    vstChunkHandler.writeVST2Preset (out);
                else
                    vstChunkHandler.writeVST3Preset (out, device.deviceID);
            }

            this.embeddedFiles.put (tempFile, device.state.path);
        }
        catch (final IOException ex)
        {
            throw new ParseException ("Could not store plugin state: " + ex.getLocalizedMessage (), 0);
        }

        return device;
    }


    /**
     * Add the media item clips to the track structure.
     *
     * @param trackLanes The lanes of the track to add the media item clips
     * @param trackChunk The track chunk
     * @throws ParseException Could not parse the track info
     */
    private void convertItems (final Lanes trackLanes, final Chunk trackChunk) throws ParseException
    {
        final Clips clips = new Clips ();
        trackLanes.lanes.add (clips);

        for (final Node node: trackChunk.getChildNodes ())
        {
            final String chunkName = node.getName ();
            if (!CHUNK_ITEM.equals (chunkName))
                continue;

            if (node instanceof final Chunk itemChunk)
            {
                final Clip clip = this.handleClip (itemChunk);
                if (clip != null)
                    clips.clips.add (clip);
            }
        }
    }


    private Lanes createTrackLanes (final Track track)
    {
        final Lanes trackLanes = new Lanes ();
        trackLanes.track = track;
        ((Lanes) this.project.arrangement.content).lanes.add (trackLanes);
        this.trackLanesMap.put (track, trackLanes);
        return trackLanes;
    }


    /**
     * Parse one item clip.
     *
     * @param itemChunk The item chunk to parse
     * @return The clip
     * @throws ParseException
     */
    private Clip handleClip (final Chunk itemChunk) throws ParseException
    {
        final Clip clip = new Clip ();

        clip.name = getParam (itemChunk.getChildNode (ITEM_NAME), null);
        clip.time = this.handleTime (getDoubleParam (itemChunk.getChildNode (ITEM_POSITION), 0), false);
        clip.duration = this.handleTime (getDoubleParam (itemChunk.getChildNode (ITEM_LENGTH), 1), false);

        // FADEIN 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
        final int [] fadeInParams = getIntParams (itemChunk.getChildNode (ITEM_FADEIN), 0);
        if (fadeInParams.length > 1 && fadeInParams[1] > 0)
            clip.fadeInTime = Double.valueOf (this.handleTime (fadeInParams[1], false));

        // FADEOUT 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
        final int [] fadeOutParams = getIntParams (itemChunk.getChildNode (ITEM_FADEOUT), 0);
        if (fadeOutParams.length > 1 && fadeOutParams[1] > 0)
            clip.fadeOutTime = Double.valueOf (this.handleTime (fadeOutParams[1], false));

        final Optional<Node> source = itemChunk.getChildNode (CHUNK_ITEM_SOURCE);
        if (source.isEmpty ())
            return null;

        final Node sourceNode = source.get ();
        if (sourceNode instanceof final Chunk sourceChunk)
        {
            final List<String> parameters = sourceChunk.getParameters ();
            if (parameters.isEmpty ())
                return null;

            switch (parameters.get (0))
            {
                case "MIDI":
                    this.convertMIDI (clip, sourceChunk);
                    break;

                case "WAVE":
                    this.convertAudio (clip, sourceChunk);
                    break;

                default:
                    // Not supported
                    break;
            }

            return clip;
        }

        return null;
    }


    /**
     * Fill a MIDI clip.
     *
     * @param clip The clip to fill
     * @param sourceChunk The source chunk which contains the clip data
     * @throws ParseException Could not parse the notes
     */
    private void convertMIDI (final Clip clip, final Chunk sourceChunk) throws ParseException
    {
        final Optional<Node> hasData = sourceChunk.getChildNode (SOURCE_HASDATA);
        if (hasData.isEmpty ())
            return;

        final Node hasDataNode = hasData.get ();
        final List<String> hasDataParameters = hasDataNode.getParameters ();
        if (hasDataParameters.size () != 3 || !"1".equals (hasDataParameters.get (0)))
            return;

        final int ticksPerQuarterNote;
        try
        {
            ticksPerQuarterNote = Integer.parseInt (hasDataParameters.get (1));
        }
        catch (final NumberFormatException ex)
        {
            return;
        }

        final Notes notes = new Notes ();
        final Lanes contentLanes = new Lanes ();
        clip.content = contentLanes;
        contentLanes.lanes.add (notes);

        final Map<ExpressionType, Map<Integer, Map<Integer, Points>>> envelopes = new EnumMap<> (ExpressionType.class);

        // Handle all MIDI events
        final List<ReaperMidiEvent> noteStarts = new ArrayList<> ();
        long currentPosition = 0;
        for (final Node childNode: sourceChunk.getChildNodes ())
        {
            final ReaperMidiEvent midiEvent = new ReaperMidiEvent (childNode);
            if (!midiEvent.isMidiEvent ())
                continue;

            currentPosition += midiEvent.getOffset ();
            midiEvent.setPosition (currentPosition);

            final int channel = midiEvent.getChannel ();
            final int code = midiEvent.getCode ();
            switch (code)
            {
                // Note start
                case 0x90:
                    noteStarts.add (midiEvent);
                    break;

                // Note end
                case 0x80:
                    final ReaperMidiEvent noteStart = findNoteStart (noteStarts, midiEvent);
                    if (noteStart == null)
                        throw new ParseException ("Malformed MIDI events in MIDI source section. End note without start note.", 0);
                    noteStarts.remove (noteStart);

                    final Note note = new Note ();
                    note.channel = channel;

                    final double position = noteStart.getPosition () / (double) ticksPerQuarterNote;
                    final double length = (midiEvent.getPosition () - noteStart.getPosition ()) / (double) ticksPerQuarterNote;
                    note.time = Double.valueOf (handleMIDITime (position));
                    note.duration = Double.valueOf (handleMIDITime (length));
                    note.key = noteStart.getData1 ();
                    note.velocity = Double.valueOf (noteStart.getData2 () / 127.0);
                    // note.releaseVelocity -> information not available

                    notes.notes.add (note);
                    break;

                // Polyphonic Aftertouch
                case 0xA0:
                    final Points paPoints = getEnvelopes (envelopes, ExpressionType.polyPressure, channel, midiEvent.getData1 ());
                    addPoint (paPoints, midiEvent, ticksPerQuarterNote, MidiBytes.TWO);
                    break;

                // CC
                case 0xB0:
                    final Points ccPoints = getEnvelopes (envelopes, ExpressionType.channelController, channel, midiEvent.getData1 ());
                    addPoint (ccPoints, midiEvent, ticksPerQuarterNote, MidiBytes.TWO);
                    break;

                // Program Change
                case 0xC0:
                    final Points pcPoints = getEnvelopes (envelopes, ExpressionType.programChange, channel, 0);
                    addPoint (pcPoints, midiEvent, ticksPerQuarterNote, MidiBytes.ONE);
                    break;

                // Channel Aftertouch
                case 0xD0:
                    final Points atPoints = getEnvelopes (envelopes, ExpressionType.channelPressure, channel, 0);
                    addPoint (atPoints, midiEvent, ticksPerQuarterNote, MidiBytes.ONE);
                    break;

                // Pitch Bend
                case 0xE0:
                    final Points pbPoints = getEnvelopes (envelopes, ExpressionType.pitchBend, channel, 0);
                    addPoint (pbPoints, midiEvent, ticksPerQuarterNote, MidiBytes.BOTH);
                    break;

                default:
                    // Not used
                    break;
            }
        }

        // Add all MIDI envelopes
        for (final Map<Integer, Map<Integer, Points>> expEnvelopes: envelopes.values ())
            for (final Map<Integer, Points> envelope: expEnvelopes.values ())
                for (final Points points: envelope.values ())
                    contentLanes.lanes.add (points);
    }


    /**
     * Fill an audio clip.
     *
     * @param clip The clip to fill
     * @param sourceChunk The audio source chunk
     * @throws ParseException Could not retrieve audio file format
     */
    private void convertAudio (final Clip clip, final Chunk sourceChunk) throws ParseException
    {
        final Optional<Node> waveFileOptional = sourceChunk.getChildNode (SOURCE_FILE);
        if (waveFileOptional.isEmpty ())
            return;

        final Node waveFileNode = waveFileOptional.get ();
        final List<String> waveFileNodeParameters = waveFileNode.getParameters ();
        if (waveFileNodeParameters.isEmpty ())
            return;

        final String filename = waveFileNodeParameters.get (0).replaceAll ("^\"|\"$", "");
        final Audio audio = new Audio ();
        audio.file = new FileReference ();
        audio.file.path = "samples/" + filename;
        audio.algorithm = "raw";

        final File sourceFile = new File (this.sourcePath, filename);
        try
        {
            final AudioFileFormat audioFileFormat = AudioSystem.getAudioFileFormat (sourceFile);
            final AudioFormat format = audioFileFormat.getFormat ();
            audio.channels = format.getChannels ();
            audio.samplerate = (int) format.getSampleRate ();
            audio.duration = getDuration (audioFileFormat);
        }
        catch (UnsupportedAudioFileException | IOException ex)
        {
            throw new ParseException ("Could not retrieve audio file format: " + sourceFile.getAbsolutePath (), 0);
        }

        this.embeddedFiles.put (sourceFile, audio.file.path);

        // Necessary to wrap in another Clips/Clip pair to load it successfully in Bitwig
        final Clips clips = new Clips ();
        clip.content = clips;
        final Clip internalClip = new Clip ();
        internalClip.time = 0;
        internalClip.duration = clip.duration;
        clips.clips.add (internalClip);
        internalClip.content = audio;
    }


    /**
     * Find the matching note start event for a note end event.
     *
     * @param noteStarts All note start events
     * @param midiEvent The note end event for which to find the start
     * @return The event or null if not found
     */
    private static ReaperMidiEvent findNoteStart (final List<ReaperMidiEvent> noteStarts, final ReaperMidiEvent midiEvent)
    {
        for (final ReaperMidiEvent event: noteStarts)
        {
            if (event.getChannel () == midiEvent.getChannel () && event.getData1 () == midiEvent.getData1 ())
                return event;
        }
        return null;
    }


    /**
     * Convert the time value to beats.
     *
     * @param value The value in time
     * @return The value in beats
     */
    private double toBeats (final double value)
    {
        final double tempo = this.project.transport.tempo.value.doubleValue ();
        final double bps = tempo / 60.0;
        return bps * value;
    }


    /**
     * Convert the beats value to time.
     *
     * @param value The value in beats
     * @return The value in time
     */
    private double toTime (final double value)
    {
        final double tempo = this.project.transport.tempo.value.doubleValue ();
        final double bps = tempo / 60.0;
        return value / bps;
    }


    /**
     * Get the first parameter value of a node as a string.
     *
     * @param optionalNode The node from which to get the parameter value
     * @param defaultValue The value to return if there is no value present
     * @return The read value of the default value
     */
    private static String getParam (final Optional<Node> optionalNode, final String defaultValue)
    {
        return optionalNode.isEmpty () ? defaultValue : getParam (optionalNode.get (), 0, defaultValue);
    }


    /**
     * Get the first parameter value of a node as a string.
     *
     * @param node The node from which to get the parameter value
     * @param position The index of the parameter
     * @param defaultValue The value to return if there is no value present
     * @return The read value of the default value
     */
    private static String getParam (final Node node, final int position, final String defaultValue)
    {
        final List<String> parameters = node.getParameters ();
        return position < parameters.size () ? parameters.get (position) : defaultValue;
    }


    /**
     * Get the first parameter value of a node as an integer.
     *
     * @param optionalNode The node from which to get the parameter value
     * @param defaultValue The value to return if there is no value present
     * @return The read value of the default value
     */
    private static int getIntParam (final Optional<Node> optionalNode, final int defaultValue)
    {
        return optionalNode.isEmpty () ? defaultValue : getIntParam (optionalNode.get (), 0, defaultValue);
    }


    /**
     * Get all parameter values of a node as integers.
     *
     * @param optionalNode The node from which to get the parameter values
     * @param defaultValue The value to return if there is no value present
     * @return The read values of the default value
     */
    private static int [] getIntParams (final Optional<Node> optionalNode, final int defaultValue)
    {
        return optionalNode.isEmpty () ? new int [0] : getIntParams (optionalNode.get (), defaultValue);
    }


    /**
     * Get all parameter values of a node as integers.
     *
     * @param node The node from which to get the parameter values
     * @param defaultValue The value to return if there is no value present
     * @return The read values of the default value
     */
    private static int [] getIntParams (final Node node, final int defaultValue)
    {
        final List<String> parameters = node.getParameters ();
        final int size = parameters.size ();
        final int [] result = new int [size];
        for (int i = 0; i < size; i++)
        {
            try
            {
                result[i] = Integer.parseInt (parameters.get (i));
            }
            catch (final NumberFormatException ex)
            {
                result[i] = defaultValue;
            }
        }
        return result;
    }


    /**
     * Get the parameter value at the given position of a node as an integer.
     *
     * @param node The node from which to get the parameter value
     * @param position The index of the parameter
     * @param defaultValue The value to return if there is no value present
     * @return The read value of the default value
     */
    private static int getIntParam (final Node node, final int position, final int defaultValue)
    {
        final List<String> parameters = node.getParameters ();
        if (position >= parameters.size ())
            return defaultValue;
        try
        {
            return Integer.parseInt (parameters.get (position));
        }
        catch (final NumberFormatException ex)
        {
            return defaultValue;
        }
    }


    /**
     * Get the first parameter value of a node as a double.
     *
     * @param optionalNode The node from which to get the parameter value
     * @param defaultValue The value to return if there is no value present
     * @return The read value of the default value
     */
    private static double getDoubleParam (final Optional<Node> optionalNode, final double defaultValue)
    {
        return optionalNode.isEmpty () ? defaultValue : getDoubleParam (optionalNode.get (), 0, defaultValue);
    }


    /**
     * Get all parameter values of a node as doubles.
     *
     * @param optionalNode The node from which to get the parameter values
     * @param defaultValue The value to return if there is no value present
     * @return The read values of the default value
     */
    private static double [] getDoubleParams (final Optional<Node> optionalNode, final double defaultValue)
    {
        if (optionalNode.isEmpty ())
            return new double [0];

        final List<String> parameters = optionalNode.get ().getParameters ();
        final int size = parameters.size ();
        final double [] result = new double [size];
        for (int i = 0; i < size; i++)
        {
            try
            {
                result[i] = Double.parseDouble (parameters.get (i));
            }
            catch (final NumberFormatException ex)
            {
                result[i] = defaultValue;
            }
        }
        return result;
    }


    /**
     * Get the parameter value at the given position of a node as a double.
     *
     * @param node The node from which to get the parameter value
     * @param position The index of the parameter
     * @param defaultValue The value to return if there is no value present
     * @return The read value of the default value
     */
    private static double getDoubleParam (final Node node, final int position, final double defaultValue)
    {
        final List<String> parameters = node.getParameters ();
        if (position >= parameters.size ())
            return defaultValue;
        try
        {
            return Double.parseDouble (parameters.get (position));
        }
        catch (final NumberFormatException ex)
        {
            return defaultValue;
        }
    }


    /**
     * Format the ARGB color as a hex string.
     *
     * @param color The color to format
     * @return The formatted color
     */
    private static String toHexColor (final int color)
    {
        // Remove alpha
        final int c = 0xFFFFFF & color;
        return String.format ("#%02x%02x%02x", Integer.valueOf (c & 0xFF), Integer.valueOf (c >> 8 & 0xFF), Integer.valueOf (c >> 16 & 0xFF));
    }


    /**
     * Create a real parameter instance.
     *
     * @param unit The unit of the parameter
     * @param min The minimum value of the parameter
     * @param max The maximum value of the parameter
     * @param value The value of the parameter
     * @return The parameter
     */
    private static RealParameter createRealParameter (final Unit unit, final double min, final double max, final double value)
    {
        final RealParameter param = new RealParameter ();
        param.unit = unit;
        param.min = Double.valueOf (min);
        param.max = Double.valueOf (max);
        param.value = Double.valueOf (value);
        return param;
    }


    private static Points getEnvelopes (final Map<ExpressionType, Map<Integer, Map<Integer, Points>>> midiEnvelopes, final ExpressionType expType, final int channel, final int keyOrCC)
    {
        final Map<Integer, Map<Integer, Points>> envelopesMap = midiEnvelopes.computeIfAbsent (expType, exp -> new HashMap<> ());
        final Integer channelKey = Integer.valueOf (channel);
        final Map<Integer, Points> envelopes = envelopesMap.computeIfAbsent (channelKey, chn -> new HashMap<> ());
        return envelopes.computeIfAbsent (Integer.valueOf (keyOrCC), kcc -> createPoints (channelKey, expType, kcc));
    }


    private static Points createPoints (final Integer channel, final ExpressionType type, final Integer keyOrCC)
    {
        final Points points = new Points ();
        points.unit = Unit.percent;
        points.interpolation = Interpolation.linear;
        points.target.channel = channel;
        points.target.expression = type;
        if (type == ExpressionType.channelController)
            points.target.controller = keyOrCC;
        else if (type == ExpressionType.polyPressure)
            points.target.key = keyOrCC;
        return points;
    }


    private static void addPoint (final Points points, final ReaperMidiEvent midiEvent, final int ticksPerQuarterNote, final MidiBytes midiBytes)
    {
        final IntegerPoint point = new IntegerPoint ();
        point.time = Double.valueOf (midiEvent.getPosition () / (double) ticksPerQuarterNote);

        final int value;
        switch (midiBytes)
        {
            case ONE:
                value = midiEvent.getData1 ();
                break;
            default:
            case TWO:
                value = midiEvent.getData2 ();
                break;
            case BOTH:
                value = midiEvent.getData1 () + midiEvent.getData2 () * 128;
        }

        point.value = Integer.valueOf (value);
        points.points.add (point);
    }


    private static double valueToDB (final double x, final double maxLevelDB)
    {
        return dBToLinear (reaperValueToDB (x), maxLevelDB);
    }


    private static double reaperValueToDB (final double x)
    {
        if (x < 0.0000000298023223876953125)
            return -150;
        return Math.max (-150.0, Math.log (x) * 8.6858896380650365530225783783321);
    }


    private static double dBToLinear (final double dBVal, final double maxLevelDB)
    {
        return Math.pow (10, (dBVal - maxLevelDB) / 20.0);
    }


    private static long getDuration (final AudioFileFormat audioFileFormat)
    {
        final String DURATION = "duration";
        Long duration = (Long) audioFileFormat.properties ().get (DURATION);
        // TODO this must be "/ 1000 / 1000" to get seconds!
        if (duration != null)
            return duration.longValue () * 1000L * 1000L;

        // and because duration is optional, use a fallback
        // for uncompressed formats like AIFF and WAVE
        final AudioFormat format = audioFileFormat.getFormat ();
        if (format.getEncoding () == Encoding.PCM_SIGNED
                // make sure we actually have a frame length
                && audioFileFormat.getFrameLength () != AudioSystem.NOT_SPECIFIED
                // make sure we actually have a frame rate
                && format.getFrameRate () != AudioSystem.NOT_SPECIFIED
                // check if this is WAVE or AIFF, other uncompressed formats may work as well
                && (audioFileFormat.getType () == Type.WAVE || audioFileFormat.getType () == Type.AIFF))
        {
            return (long) (audioFileFormat.getFrameLength () / format.getFrameRate ());
        }

        return 0;
    }


    /**
     * Converts time to beats, vice versa or not at all depending on the source and destination time
     * base.
     *
     * @param time The value to convert
     * @param isEnvelope True if the data is read from an envelope, which might have a different
     *            time base in Reaper
     * @return The value matching the destination time base
     */
    private double handleTime (final double time, final boolean isEnvelope)
    {
        if (isEnvelope)
        {
            if (this.sourceIsEnvelopeBeats == this.destinationIsBeats)
                return time;
            return this.sourceIsEnvelopeBeats ? this.toTime (time) : this.toBeats (time);
        }

        if (this.sourceIsBeats == this.destinationIsBeats)
            return time;
        return this.sourceIsBeats ? this.toTime (time) : this.toBeats (time);
    }


    /**
     * Converts beats (MIDI timing is always in beats) to time if necessary depending on the
     * destination time base.
     *
     * @param time The value to convert
     * @return The value matching the destination time base
     */
    private double handleMIDITime (final double time)
    {
        return this.destinationIsBeats ? time : this.toTime (time);
    }
}
