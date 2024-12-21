// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFileFormat.Type;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.BoolParameter;
import com.bitwig.dawproject.Channel;
import com.bitwig.dawproject.ContentType;
import com.bitwig.dawproject.ExpressionType;
import com.bitwig.dawproject.FileReference;
import com.bitwig.dawproject.Interpolation;
import com.bitwig.dawproject.MetaData;
import com.bitwig.dawproject.MixerRole;
import com.bitwig.dawproject.Parameter;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.RealParameter;
import com.bitwig.dawproject.Referenceable;
import com.bitwig.dawproject.Send;
import com.bitwig.dawproject.SendType;
import com.bitwig.dawproject.TimeSignatureParameter;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.Transport;
import com.bitwig.dawproject.Unit;
import com.bitwig.dawproject.Utility;
import com.bitwig.dawproject.device.ClapPlugin;
import com.bitwig.dawproject.device.Device;
import com.bitwig.dawproject.device.DeviceRole;
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
import com.bitwig.dawproject.timeline.TimeUnit;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IMediaFiles;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.projectconverter.core.TempoChange;
import de.mossgrabers.projectconverter.core.TempoConverter;
import de.mossgrabers.projectconverter.core.TimeUtils;
import de.mossgrabers.projectconverter.format.Conversions;
import de.mossgrabers.projectconverter.format.reaper.model.Chunk;
import de.mossgrabers.projectconverter.format.reaper.model.ClapChunkHandler;
import de.mossgrabers.projectconverter.format.reaper.model.DeviceChunkHandler;
import de.mossgrabers.projectconverter.format.reaper.model.Node;
import de.mossgrabers.projectconverter.format.reaper.model.ReaperMidiEvent;
import de.mossgrabers.projectconverter.format.reaper.model.ReaperProject;
import de.mossgrabers.projectconverter.format.reaper.model.VstChunkHandler;
import de.mossgrabers.tools.FileUtils;
import de.mossgrabers.tools.ui.BasicConfig;
import de.mossgrabers.tools.ui.Functions;
import de.mossgrabers.tools.ui.panel.BoxPanel;
import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;
import javafx.stage.FileChooser.ExtensionFilter;


/**
 * Converts a Reaper project file (the already loaded chunks to be more specific) into a dawproject
 * structure. Needs to be state-less.
 *
 * @author Jürgen Moßgraber
 */
public class ReaperDetector extends AbstractCoreTask implements ISourceFormat
{
    private static final Pattern         PATTERN_DEVICE_DESCRIPTION  = Pattern.compile ("(VST|VSTi|VST3|VST3i|CLAP|CLAPi)?:\\s(.*)(\\s\\((.*)\\))?");
    private static final Pattern         PATTERN_VST2_ID             = Pattern.compile ("(.*)<.*");
    private static final Pattern         PATTERN_VST3_ID             = Pattern.compile (".*\\{(.*)\\}");
    private static final ExtensionFilter EXTENSION_FILTER            = new ExtensionFilter ("Reaper Project", "*.rpp", "*.rpp-bak");
    private static final String          DO_NOT_COMPRESS_AUDIO_FILES = "DO_NOT_COMPRESS_AUDIO_FILES";


    private enum MidiBytes
    {
        ONE,
        TWO,
        BOTH
    }


    private CheckBox doNotCompressAudioFiles;


    /**
     * Constructor.
     *
     * @param notifier The notifier
     */
    public ReaperDetector (final INotifier notifier)
    {
        super ("Reaper", notifier);
    }


    /** {@inheritDoc} */
    @Override
    public ExtensionFilter getExtensionFilter ()
    {
        return EXTENSION_FILTER;
    }


    /** {@inheritDoc} */
    @Override
    public javafx.scene.Node getEditPane ()
    {
        final BoxPanel panel = new BoxPanel (Orientation.VERTICAL);
        this.doNotCompressAudioFiles = panel.createCheckBox ("@IDS_DO_NOT_COMPRESS_AUDIO_FILES");
        return panel.getPane ();
    }


    /** {@inheritDoc} */
    @Override
    public void loadSettings (final BasicConfig config)
    {
        this.doNotCompressAudioFiles.setSelected (config.getBoolean (DO_NOT_COMPRESS_AUDIO_FILES));
    }


    /** {@inheritDoc} */
    @Override
    public void saveSettings (final BasicConfig config)
    {
        config.setBoolean (DO_NOT_COMPRESS_AUDIO_FILES, this.doNotCompressAudioFiles.isSelected ());
    }


    /** {@inheritDoc} */
    @Override
    public DawProjectContainer read (final File sourceFile) throws IOException, ParseException
    {
        final BeatsAndTime beatsAndTime = new BeatsAndTime ();

        final List<String> lines = Files.readAllLines (sourceFile.toPath (), StandardCharsets.UTF_8);
        final Chunk rootChunk = ReaperProject.parse (lines);

        Referenceable.setAutoID (true);

        try (final ReaperMediaFiles reaperMediaFiles = new ReaperMediaFiles (); final DawProjectContainer dawProject = new DawProjectContainer (FileUtils.getNameWithoutType (sourceFile), reaperMediaFiles))
        {
            final Project project = dawProject.getProject ();

            project.application.name = "Cockos Reaper (converted with " + Functions.getMessage ("TITLE") + ")";
            final List<String> parameters = rootChunk.getParameters ();
            project.application.version = parameters.size () > 1 ? parameters.get (1) : "Unknown";

            convertMetadata (dawProject.getMetadata (), rootChunk);

            convertArrangement (project, rootChunk, beatsAndTime);
            beatsAndTime.destinationIsBeats = false;
            TimeUtils.setTimeUnit (project.arrangement.lanes, beatsAndTime.destinationIsBeats);

            final FolderStructure structure = new FolderStructure ();
            structure.folderTracks = new ArrayList<> ();

            convertTransport (project, rootChunk);

            final Map<String, File> mediaFilesMap = new HashMap<> ();
            beatsAndTime.tempoEnvelope = this.convertMaster (dawProject, mediaFilesMap, rootChunk, structure, beatsAndTime);

            this.convertTracks (dawProject, mediaFilesMap, rootChunk, sourceFile.getParentFile (), structure, beatsAndTime);
            convertMarkers (dawProject, rootChunk, beatsAndTime);

            try (final IMediaFiles mediaFiles = dawProject.getMediaFiles ())
            {
                for (final Map.Entry<String, File> entry: mediaFilesMap.entrySet ())
                    mediaFiles.add (entry.getKey (), entry.getValue ());
            }

            project.structure.addAll (structure.folderTracks);

            return dawProject;
        }
    }


    /**
     * Fills the metadata description file.
     *
     * @param metadata The metadata to fill
     * @param rootChunk The project root chunk
     */
    private static void convertMetadata (final MetaData metadata, final Chunk rootChunk)
    {
        // Get the author and comment settings from the project (File -> Project settings...)
        final Optional<Node> authorParameter = rootChunk.getChildNode (ReaperTags.PROJECT_AUTHOR);
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
                    metadata.artist = author;
                    metadata.producer = author;
                    metadata.composer = author;
                    metadata.songwriter = author;
                }
            }
        }
        final Optional<Node> notesParameter = rootChunk.getChildNode (ReaperTags.PROJECT_NOTES);
        if (notesParameter.isPresent () && notesParameter.get () instanceof final Chunk notesChunk)
            metadata.comment = readNotes (notesChunk);

        // Use metadata from the render metadata setting (File -> Project Render Metadata)
        final Optional<Node> renderMetadataParameter = rootChunk.getChildNode (ReaperTags.PROJECT_RENDER_METADATA);
        if (renderMetadataParameter.isEmpty ())
            return;

        final Node node = renderMetadataParameter.get ();
        if (node instanceof final Chunk renderMetadataChunk)
        {
            for (final Node tagNode: renderMetadataChunk.getChildNodes ())
                handleMetadataTag (metadata, tagNode);
        }
    }


    private static String readNotes (final Chunk notesChunk)
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
        return comment.toString ();
    }


    /**
     * Check if some useful metadata can be extracted from a render chunk.
     *
     * @param metadata The metadata to fill
     * @param tagNode A sub node of a render chunk
     */
    private static void handleMetadataTag (final MetaData metadata, final Node tagNode)
    {
        if (!"TAG".equals (tagNode.getName ()))
            return;

        final List<String> parameters = tagNode.getParameters ();
        if (parameters.size () < 2)
            return;

        String value = parameters.get (1);
        if (value.length () > 1 && value.startsWith ("\"") && value.endsWith ("\""))
            value = value.substring (1, value.length () - 2);

        // metadata.arranger - no matching tag in Reaper
        // metadata.website - no matching tag in Reaper

        switch (parameters.get (0))
        {
            case "ID3:COMM":
                metadata.comment = value;
                break;
            case "ID3:TCOM":
                metadata.composer = value;
                metadata.songwriter = value;
                break;
            case "ID3:TCON":
                metadata.genre = value;
                break;
            case "ID3:TCOP":
                metadata.copyright = value;
                break;
            case "ID3:TIPL":
                metadata.producer = value;
                break;
            case "ID3:TIT2":
                metadata.title = value;
                break;
            case "ID3:TPE1":
                metadata.artist = value;
                break;
            case "ID3:TPE2":
                metadata.originalArtist = value;
                break;
            case "ID3:TYER":
                metadata.year = value;
                break;
            case "ID3:TALB":
                metadata.album = value;
                break;
            default:
                // No more supported
                break;
        }
    }


    /**
     * Fill the arrangement structure.
     *
     * @param project The project to fill
     * @param rootChunk The root chunk
     * @param beatsAndTime The beats and/or time conversion information
     */
    private static void convertArrangement (final Project project, final Chunk rootChunk, final BeatsAndTime beatsAndTime)
    {
        final Arrangement arrangement = new Arrangement ();
        project.arrangement = arrangement;
        arrangement.lanes = new Lanes ();

        final Optional<Node> timelockModeNode = rootChunk.getChildNode (ReaperTags.PROJECT_TIME_LOCKMODE);
        beatsAndTime.sourceIsBeats = getIntParam (timelockModeNode, 1) == 1;

        final Optional<Node> timelockEnvelopeModeNode = rootChunk.getChildNode (ReaperTags.PROJECT_TIME_ENV_LOCKMODE);
        beatsAndTime.sourceIsEnvelopeBeats = getIntParam (timelockEnvelopeModeNode, 1) == 1;

        // Time values are always in seconds, indicators above seem to be only for visual
        // information
        beatsAndTime.sourceIsBeats = false;
        beatsAndTime.sourceIsEnvelopeBeats = false;
    }


    /**
     * Create all markers.
     *
     * @param dawProject The DAWproject container
     * @param rootChunk The root chunk
     * @param beatsAndTime The beats and/or time conversion information
     */
    private static void convertMarkers (final DawProjectContainer dawProject, final Chunk rootChunk, final BeatsAndTime beatsAndTime)
    {
        final Markers cueMarkers = new Markers ();

        for (final Node node: rootChunk.getChildNodes ())
        {
            // Is it a simple marker?
            if (!ReaperTags.PROJECT_MARKER.equals (node.getName ()) || getIntParam (node, 3, 0) > 0)
                continue;

            // If the marker has no name use the index
            String name = getParam (node, 2, "");
            if (name.isBlank () || "\"\"".equals (name))
                name = getParam (node, 0, "0");

            final Marker marker = new Marker ();
            marker.time = handleTime (beatsAndTime, getDoubleParam (node, 1, 0), false);
            marker.name = name;
            final int c = getIntParam (node, 4, 0);
            if (c > 0)
                marker.color = toHexColor (c);
            cueMarkers.markers.add (marker);
        }

        if (!cueMarkers.markers.isEmpty ())
            dawProject.getProject ().arrangement.markers = cueMarkers;
    }


    /**
     * Fill the transport structure.
     *
     * @param project The project
     * @param rootChunk The root chunk
     */
    private static void convertTransport (final Project project, final Chunk rootChunk)
    {
        project.transport = new Transport ();
        final TimeSignatureParameter timeSignatureParameter = new TimeSignatureParameter ();
        project.transport.timeSignature = timeSignatureParameter;

        final Optional<Node> parameter = rootChunk.getChildNode (ReaperTags.PROJECT_TEMPO);
        if (parameter.isPresent ())
        {
            final double [] transParams = getDoubleParams (parameter, -1);
            if (transParams.length > 0)
                project.transport.tempo = Utility.createRealParameter (Unit.BPM, 1.0, 960.0, transParams[0]);

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
     * @param dawProject The DAWproject container
     * @param mediaFilesMap Map to collect media files
     * @param rootChunk The root chunk
     * @param folderStructure The folder structure
     * @param beatsAndTime The beats and/or time conversion information
     * @throws ParseException Could not parse the master
     */
    private final List<TempoChange> convertMaster (final DawProjectContainer dawProject, final Map<String, File> mediaFilesMap, final Chunk rootChunk, final FolderStructure folderStructure, final BeatsAndTime beatsAndTime) throws ParseException
    {
        final Project project = dawProject.getProject ();

        final Track masterTrack = new Track ();
        folderStructure.folderTracks.add (masterTrack);

        masterTrack.name = "Master";
        masterTrack.contentType = new ContentType []
        {
            ContentType.AUDIO
        };

        masterTrack.channel = new Channel ();
        final Channel channel = masterTrack.channel;
        channel.role = MixerRole.MASTER;

        final int numberOfChannels = getIntParam (rootChunk.getChildNode (ReaperTags.MASTER_NUMBER_OF_CHANNELS), -1);
        channel.audioChannels = Integer.valueOf (numberOfChannels > 0 ? numberOfChannels : 2);

        // Volume & Panorama
        final double [] volPan = getDoubleParams (rootChunk.getChildNode (ReaperTags.MASTER_VOLUME_PAN), -1);
        if (volPan.length >= 1)
        {
            channel.volume = Utility.createRealParameter (Unit.LINEAR, 0.0, 1.0, Math.min (1, Conversions.valueToDb (volPan[0], 0)));
            channel.pan = Utility.createRealParameter (Unit.NORMALIZED, 0.0, 1.0, (volPan[1] + 1.0) / 2.0);
        }

        // Mute & Solo
        final int muteSolo = getIntParam (rootChunk.getChildNode (ReaperTags.MASTER_MUTE_SOLO), -1);
        if (muteSolo > 0)
        {
            channel.mute = createBoolParameter ((muteSolo & 1) > 0);
            channel.solo = Boolean.valueOf ((muteSolo & 2) > 0);
        }

        // Set track color
        final int color = getIntParam (rootChunk.getChildNode (ReaperTags.MASTER_COLOR), -1);
        if (color >= 0)
            masterTrack.color = toHexColor (color);

        // Convert all FX devices
        channel.devices = this.convertDevices (mediaFilesMap, masterTrack, rootChunk, ReaperTags.MASTER_CHUNK_FXCHAIN, folderStructure);

        createTrackLanes (project, masterTrack, folderStructure);
        final Points tempoEnvelope = convertTempoAutomation (dawProject, rootChunk);

        convertAutomation (masterTrack, rootChunk, ReaperTags.MASTER_VOLUME_ENVELOPE, channel.volume, true, folderStructure, beatsAndTime);
        convertAutomation (masterTrack, rootChunk, ReaperTags.MASTER_PANORAMA_ENVELOPE, channel.pan, true, folderStructure, beatsAndTime);

        return toTempoChangeList (tempoEnvelope, project.transport.tempo.value.doubleValue ());
    }


    private static List<TempoChange> toTempoChangeList (final Points envelope, final double globalTempo)
    {
        final List<TempoChange> tempoChanges = new ArrayList<> ();
        for (final Point point: envelope.points)
        {
            if (point instanceof RealPoint realPoint && realPoint.time != null && realPoint.value != null)
                tempoChanges.add (new TempoChange (realPoint.time.doubleValue (), realPoint.value.doubleValue (), realPoint.interpolation == Interpolation.LINEAR));
        }

        if (tempoChanges.isEmpty () || tempoChanges.get (0).getTime () > 0)
            tempoChanges.add (0, new TempoChange (0, globalTempo, false));

        return tempoChanges;
    }


    /**
     * Fill the track structure.
     *
     * @param dawProject The DAWproject container
     * @param mediaFilesMap Map to collect media files
     * @param rootChunk The root chunk
     * @param sourcePath The path of the source project file
     * @param folderStructure The folder structure
     * @param beatsAndTime The beats and/or time conversion information
     * @throws ParseException Could not parse the tracks
     */
    private void convertTracks (final DawProjectContainer dawProject, final Map<String, File> mediaFilesMap, final Chunk rootChunk, final File sourcePath, final FolderStructure folderStructure, final BeatsAndTime beatsAndTime) throws ParseException
    {
        final List<Track> tracks = new ArrayList<> ();
        for (final Node node: rootChunk.getChildNodes ())
        {
            if (node instanceof final Chunk subChunk && ReaperTags.CHUNK_TRACK.equals (subChunk.getName ()))
                tracks.add (this.convertTrack (dawProject, mediaFilesMap, subChunk, sourcePath, folderStructure, beatsAndTime));
        }

        // In the second run assign the collected sends
        for (int i = 0; i < tracks.size (); i++)
        {
            final Track track = tracks.get (i);
            track.channel.sends = folderStructure.sendMapping.get (Integer.valueOf (i));

            if (track.channel.sends != null)
            {
                for (final Send send: track.channel.sends)
                    // TODO add send panorama automation
                    convertAutomation (track, folderStructure.sendChunkMapping.get (send), ReaperTags.TRACK_AUX_ENVELOPE, send.volume, true, folderStructure, beatsAndTime);
            }
        }
    }


    /**
     * Fill the track structure.
     *
     * @param dawProject The DAWproject container
     * @param mediaFilesMap Map to collect media files
     * @param trackChunk The track chunk
     * @param sourcePath The path of the source project file
     * @param folderStructure The folder structure
     * @param beatsAndTime The beats and/or time conversion information
     * @return The created track
     * @throws ParseException Could not parse the track info
     */
    private Track convertTrack (final DawProjectContainer dawProject, final Map<String, File> mediaFilesMap, final Chunk trackChunk, final File sourcePath, final FolderStructure folderStructure, final BeatsAndTime beatsAndTime) throws ParseException
    {
        final Track track = new Track ();
        final Channel channel = new Channel ();
        track.channel = channel;

        // Set track name
        final Optional<Node> nameNode = trackChunk.getChildNode (ReaperTags.TRACK_NAME);
        track.name = nameNode.isPresent () ? nameNode.get ().getParameters ().get (0) : "Track";

        // Set track color
        final int color = getIntParam (trackChunk.getChildNode (ReaperTags.TRACK_COLOR), -1);
        if (color >= 0)
            track.color = toHexColor (color);

        // track.comment - track comment not in Reaper

        // track.loaded - no loaded state in Reaper

        final int numberOfChannels = getIntParam (trackChunk.getChildNode (ReaperTags.TRACK_NUMBER_OF_CHANNELS), -1);
        track.channel.audioChannels = Integer.valueOf (numberOfChannels > 0 ? numberOfChannels : 2);

        // Create and store Sends for assignment in second phase
        final List<Node> auxReceive = trackChunk.getChildNodes (ReaperTags.TRACK_AUX_RECEIVE);
        if (!auxReceive.isEmpty ())
        {
            for (final Node sendNode: auxReceive)
            {
                final int trackIndex = getIntParam (sendNode, 0, 0);
                final int mode = getIntParam (sendNode, 1, 0);
                final double sendVolume = getDoubleParam (sendNode, 2, 1);
                final double sendPanorama = getDoubleParam (sendNode, 3, 0);
                final boolean isEnabled = getIntParam (sendNode, 4, 0) == 0;

                final Send send = new Send ();
                send.name = "Send";
                send.enable = createBoolParameter (isEnabled);
                send.volume = Utility.createRealParameter (Unit.LINEAR, 0.0, 1.0, Conversions.valueToDb (sendVolume, 12));
                send.pan = Utility.createRealParameter (Unit.NORMALIZED, -1, 1, sendPanorama);
                send.type = mode == 0 ? SendType.POST : SendType.PRE;
                send.destination = track.channel;
                folderStructure.sendMapping.computeIfAbsent (Integer.valueOf (trackIndex), key -> new ArrayList<> ()).add (send);
                folderStructure.sendChunkMapping.put (send, trackChunk);
            }

            track.channel.role = MixerRole.EFFECT_TRACK;
            track.contentType = new ContentType []
            {
                ContentType.AUDIO
            };
        }

        // track.destination -> too much options in Reaper to support this with a single destination

        // Volume & Panorama
        final double [] volPan = getDoubleParams (trackChunk.getChildNode (ReaperTags.TRACK_VOLUME_PAN), -1);
        if (volPan.length >= 1)
        {
            channel.volume = Utility.createRealParameter (Unit.LINEAR, 0.0, 1.0, Conversions.valueToDb (volPan[0], 0));
            channel.pan = Utility.createRealParameter (Unit.NORMALIZED, 0.0, 1.0, (volPan[1] + 1.0) / 2.0);
        }

        // Mute & Solo
        final int [] muteSolo = getIntParams (trackChunk.getChildNode (ReaperTags.TRACK_MUTE_SOLO), -1);
        if (muteSolo.length > 0)
            channel.mute = createBoolParameter (muteSolo[0] > 0);
        if (muteSolo.length > 1)
            channel.solo = Boolean.valueOf (muteSolo[1] > 0);

        // Folder handling
        final Optional<Node> structureNode = trackChunk.getChildNode (ReaperTags.TRACK_STRUCTURE);
        if (structureNode.isPresent ())
            convertFolderStructure (folderStructure, track, channel, getIntParams (structureNode, -1));

        final Lanes trackLanes = createTrackLanes (dawProject.getProject (), track, folderStructure);

        // Convert all FX devices
        channel.devices = this.convertDevices (mediaFilesMap, track, trackChunk, ReaperTags.CHUNK_FXCHAIN, folderStructure);

        final Set<ContentType> trackTypes = this.convertClips (dawProject, mediaFilesMap, trackLanes, trackChunk, sourcePath, beatsAndTime);

        if (auxReceive.isEmpty ())
        {
            // Reaper tracks are always hybrid but try to set only the necessary type
            if (trackTypes.isEmpty ())
            {
                track.contentType = new ContentType []
                {
                    ContentType.NOTES,
                    ContentType.AUDIO
                };
            }
            else
                track.contentType = trackTypes.toArray (new ContentType [trackTypes.size ()]);

            // TODO check for group status and set mixer role:
            // track.mixerRole = MixerRole.subMix;
        }

        convertAutomation (track, trackChunk, folderStructure, beatsAndTime);

        return track;
    }


    private static void convertFolderStructure (final FolderStructure folderStructure, final Track track, final Channel channel, final int [] structure)
    {
        if (structure.length != 2)
            return;

        switch (structure[0])
        {
            // A top level track or track in a folder
            default:
            case 0:
                folderStructure.folderTracks.add (track);
                break;

            // Folder track
            case 1:
                // Folder tracks are stored as a folder and a master track, which is inside of
                // the folder
                channel.role = MixerRole.MASTER;

                final Track folderTrack = new Track ();
                folderTrack.contentType = new ContentType []
                {
                    ContentType.TRACKS
                };
                folderTrack.name = track.name;
                track.name = track.name + " Master";
                folderTrack.comment = track.comment;
                folderTrack.color = track.color;
                folderStructure.folderTracks.add (folderTrack);
                folderStructure.folderStack.add (folderStructure.folderTracks);
                folderStructure.folderTracks = folderTrack.tracks;
                folderStructure.folderTracks.add (track);
                break;

            // Last track in the folder
            case 2:
                folderStructure.folderTracks.add (track);
                if (!folderStructure.folderStack.isEmpty ())
                {
                    for (int i = 0; i < Math.abs (structure[1]); i++)
                        folderStructure.folderTracks = folderStructure.folderStack.removeLast ();
                }
                break;
        }
    }


    /**
     * Fill the envelope structure.
     *
     * @param track The track to add the media item clips
     * @param trackChunk The track chunk
     * @param folderStructure The folder structure
     * @param beatsAndTime The beats and/or time conversion information
     */
    private static void convertAutomation (final Track track, final Chunk trackChunk, final FolderStructure folderStructure, final BeatsAndTime beatsAndTime)
    {
        convertAutomation (track, trackChunk, ReaperTags.TRACK_VOLUME_ENVELOPE, track.channel.volume, true, folderStructure, beatsAndTime);
        convertAutomation (track, trackChunk, ReaperTags.TRACK_PANORAMA_ENVELOPE, track.channel.pan, true, folderStructure, beatsAndTime);
        convertAutomation (track, trackChunk, ReaperTags.TRACK_MUTE_ENVELOPE, track.channel.mute, false, folderStructure, beatsAndTime);
    }


    private static void convertAutomation (final Track track, final Chunk trackChunk, final String envelopeName, final Parameter parameter, final boolean interpolate, final FolderStructure folderStructure, final BeatsAndTime beatsAndTime)
    {
        final Optional<Node> envelopeNode = trackChunk.getChildNode (envelopeName);
        if (envelopeNode.isPresent () && envelopeNode.get () instanceof final Chunk envelopeChunk)
        {
            final Lanes lanes = folderStructure.trackLanesMap.get (track);
            if (lanes == null)
                return;
            final Points envelope = new Points ();
            lanes.lanes.add (envelope);

            if (interpolate)
                envelope.unit = Unit.LINEAR;

            envelope.target.parameter = parameter;

            for (final Node pointNode: envelopeChunk.getChildNodes (ReaperTags.ENVELOPE_POINT))
            {
                final Point point;
                if (interpolate)
                {
                    final RealPoint realPoint = new RealPoint ();
                    // TODO Are there different interpolations available in Reaper?
                    realPoint.interpolation = Interpolation.LINEAR;
                    point = realPoint;
                }
                else
                    point = new BoolPoint ();
                final double timeValue = getDoubleParam (pointNode, 0, 0);
                point.time = Double.valueOf (handleTime (beatsAndTime, timeValue, true));

                if (interpolate)
                    ((RealPoint) point).value = Double.valueOf (getDoubleParam (pointNode, 1, 0));
                else
                    ((BoolPoint) point).value = Boolean.valueOf (getDoubleParam (pointNode, 1, 0) > 0);
                envelope.points.add (point);
            }
        }
    }


    private static Points convertTempoAutomation (final DawProjectContainer dawProject, final Chunk rootChunk)
    {
        final Optional<Node> envelopeNode = rootChunk.getChildNode (ReaperTags.PROJECT_TEMPO_ENVELOPE);
        if (!envelopeNode.isPresent () || !(envelopeNode.get () instanceof final Chunk envelopeChunk))
            return null;

        final Points tempoEnvelope = new Points ();

        // Time envelope must be kept in seconds!
        tempoEnvelope.timeUnit = TimeUnit.SECONDS;

        final Project project = dawProject.getProject ();
        project.arrangement.tempoAutomation = tempoEnvelope;

        tempoEnvelope.unit = Unit.BPM;
        tempoEnvelope.target.parameter = project.transport.tempo;

        final Points signatureEnvelope = new Points ();
        signatureEnvelope.target.parameter = project.transport.timeSignature;

        Interpolation previousInterpolation = Interpolation.HOLD;
        for (final Node pointNode: envelopeChunk.getChildNodes (ReaperTags.ENVELOPE_POINT))
        {
            final RealPoint point = new RealPoint ();
            final double timeValue = getDoubleParam (pointNode, 0, 0);
            // Time envelope must be kept in seconds!
            point.time = Double.valueOf (timeValue);
            point.value = Double.valueOf (getDoubleParam (pointNode, 1, 0));
            point.interpolation = previousInterpolation;
            previousInterpolation = getIntParam (pointNode, 2, 1) == 0 ? Interpolation.LINEAR : Interpolation.HOLD;
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

        // Make sure there is a signature marker at position 0
        if (!signatureEnvelope.points.isEmpty () && signatureEnvelope.points.get (0).time.doubleValue () > 0)
        {
            final TimeSignaturePoint timeSigPoint = new TimeSignaturePoint ();
            timeSigPoint.time = Double.valueOf (0);
            timeSigPoint.numerator = project.transport.timeSignature.numerator;
            timeSigPoint.denominator = project.transport.timeSignature.denominator;
            signatureEnvelope.points.add (0, timeSigPoint);
            project.arrangement.timeSignatureAutomation = signatureEnvelope;
        }

        return tempoEnvelope;
    }


    /**
     * Fill the devices structure.
     *
     * @param mediaFilesMap Map to collect media files
     * @param track The track
     * @param trackChunk The track chunk
     * @param chunkName The name of the FX list chunk
     * @return The list with the parsed devices
     * @param folderStructure The folder structure
     * @throws ParseException Could not parse the track info
     */
    private List<Device> convertDevices (final Map<String, File> mediaFilesMap, final Track track, final Chunk trackChunk, final String chunkName, final FolderStructure folderStructure) throws ParseException
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

                if (ReaperTags.FXCHAIN_BYPASS.equals (nodeName))
                {
                    final int [] params = getIntParams (node, 0);
                    bypass = params.length > 0 && params[0] > 0;
                    offline = params.length > 1 && params[1] > 0;
                }
                else if ((ReaperTags.CHUNK_CLAP.equals (nodeName) || ReaperTags.CHUNK_VST.equals (nodeName)) && node instanceof final Chunk chunk)
                {
                    device = this.convertDevice (mediaFilesMap, chunk, bypass, offline);
                    if (device != null)
                        devices.add (device);
                }
                else if (ReaperTags.FXCHAIN_PARAMETER_ENVELOPE.equals (nodeName) && node instanceof final Chunk paramEnvChunk)
                {
                    createAutomationParameters (track, device, paramEnvChunk, folderStructure);
                }
            }
        }

        return devices;
    }


    private static void createAutomationParameters (final Track track, final Device device, final Chunk paramEnvChunk, final FolderStructure folderStructure)
    {
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

        final RealParameter param = Utility.createRealParameter (Unit.LINEAR, getDoubleParam (paramEnvChunk, 1, 0), getDoubleParam (paramEnvChunk, 2, 0), getDoubleParam (paramEnvChunk, 3, 0));
        param.parameterID = Integer.valueOf (paramID);
        device.automatedParameters.add (param);

        final Lanes lanes = folderStructure.trackLanesMap.get (track);

        final Points envelope = new Points ();
        lanes.lanes.add (envelope);
        envelope.unit = Unit.LINEAR;
        envelope.target.parameter = param;

        for (final Node pointNode: paramEnvChunk.getChildNodes (ReaperTags.ENVELOPE_POINT))
        {
            final RealPoint point = new RealPoint ();
            // TODO Is there interpolation info in Reaper?
            point.interpolation = Interpolation.LINEAR;
            point.time = Double.valueOf (getDoubleParam (pointNode, 0, 0));
            point.value = Double.valueOf (getDoubleParam (pointNode, 1, 0));
            envelope.points.add (point);
        }
    }


    /**
     * Analyze one FX device chunk.
     *
     * @param mediaFilesMap Map to collect media files
     * @param chunk The device chunk
     * @param offline True if the FX device is offline
     * @param bypass True if the FX device is bypassed
     * @return The created device
     * @throws ParseException Error during parsing
     */
    private Device convertDevice (final Map<String, File> mediaFilesMap, final Chunk chunk, final boolean bypass, final boolean offline) throws ParseException
    {
        final List<String> parameters = chunk.getParameters ();
        if (parameters.size () < 3)
            return null;

        final String deviceDesc = parameters.get (0);
        final Matcher descMatcher = PATTERN_DEVICE_DESCRIPTION.matcher (deviceDesc);
        if (!descMatcher.matches ())
        {
            this.notifier.logError ("IDS_NOTIFY_COULD_NOT_MATCH_DEVICE", deviceDesc);
            return null;
        }

        final Device device;
        final String fileEnding;
        boolean isVST2 = false;
        final String pluginType = descMatcher.group (1);
        if (pluginType == null)
        {
            this.notifier.logError ("IDS_NOTIFY_NO_PLUGIN_TYPE");
            return null;
        }

        final String deviceID;
        switch (pluginType)
        {
            case ReaperTags.PLUGIN_VST_2, ReaperTags.PLUGIN_VST_2_INSTRUMENT:
                device = new Vst2Plugin ();
                fileEnding = ".fxp";
                isVST2 = true;

                final Matcher idMatcher2 = PATTERN_VST2_ID.matcher (parameters.get (4));
                if (!idMatcher2.matches ())
                    return null;
                deviceID = idMatcher2.group (1);
                break;

            case ReaperTags.PLUGIN_VST_3, ReaperTags.PLUGIN_VST_3_INSTRUMENT:
                device = new Vst3Plugin ();
                fileEnding = ".vstpreset";

                final Matcher idMatcher3 = PATTERN_VST3_ID.matcher (parameters.get (4));
                if (!idMatcher3.matches ())
                    return null;
                deviceID = idMatcher3.group (1);
                break;

            case ReaperTags.PLUGIN_CLAP, ReaperTags.PLUGIN_CLAP_INSTRUMENT:
                device = new ClapPlugin ();
                fileEnding = ".clap-preset";
                deviceID = parameters.get (1);
                break;

            default:
                // AU and build-in devices not supported
                this.notifier.logError ("IDS_NOTIFY_PLUGINTYPE_NOT_SUPPORTED", pluginType);
                return null;
        }

        device.name = descMatcher.group (2);
        // device.pluginVersion -> information not available

        if (ReaperTags.isInstrumentPlugin (pluginType))
            device.deviceRole = DeviceRole.INSTRUMENT;
        else
        {
            // Other type info not available, therefore always assume an audio FX
            device.deviceRole = DeviceRole.AUDIO_FX;
        }

        device.deviceName = device.name;
        device.deviceVendor = descMatcher.group (3);
        device.deviceID = deviceID;
        device.enabled = createBoolParameter (!bypass);
        device.enabled.name = "On/Off";
        device.loaded = Boolean.valueOf (!offline);

        device.state = new FileReference ();
        final String filename = UUID.randomUUID ().toString () + fileEnding;
        device.state.external = Boolean.FALSE;
        device.state.path = "plugins/" + filename;

        try
        {
            final File tempFile = File.createTempFile ("dawproject-", "-converter");
            tempFile.deleteOnExit ();

            try (final FileOutputStream out = new FileOutputStream (tempFile))
            {
                final DeviceChunkHandler handler = device instanceof ClapPlugin ? new ClapChunkHandler () : new VstChunkHandler (isVST2, device.deviceID);
                handler.chunkToFile (chunk, out);
                mediaFilesMap.put (device.state.path, tempFile);
            }
        }
        catch (final IOException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_COULD_NOT_CONVERT_PLUGIN_STATE", device.deviceName);
        }

        return device;
    }


    /**
     * Add the media item clips to the track structure.
     *
     * @param dawProject The DAWproject container
     * @param mediaFilesMap Map to collect media files
     * @param trackLanes The lanes of the track to add the media item clips
     * @param trackChunk The track chunk
     * @param sourcePath The path of the source project file
     * @param beatsAndTime The beats and/or time conversion information
     * @return The type of converted clips
     * @throws ParseException Could not parse the track info
     */
    private Set<ContentType> convertClips (final DawProjectContainer dawProject, final Map<String, File> mediaFilesMap, final Lanes trackLanes, final Chunk trackChunk, final File sourcePath, final BeatsAndTime beatsAndTime) throws ParseException
    {
        final Set<ContentType> contentTypes = new HashSet<> ();

        final Clips clips = new Clips ();
        trackLanes.lanes.add (clips);
        TimeUtils.setTimeUnit (clips, true);

        for (final Node node: trackChunk.getChildNodes ())
        {
            final String chunkName = node.getName ();
            if (!ReaperTags.CHUNK_ITEM.equals (chunkName))
                continue;

            if (node instanceof final Chunk itemChunk)
            {
                final Clip clip = this.convertClip (dawProject, mediaFilesMap, itemChunk, sourcePath, beatsAndTime, contentTypes);
                if (clip != null)
                    clips.clips.add (clip);
            }
        }

        return contentTypes;
    }


    private static Lanes createTrackLanes (final Project project, final Track track, final FolderStructure folderStructure)
    {
        final Lanes trackLanes = new Lanes ();
        trackLanes.track = track;
        project.arrangement.lanes.lanes.add (trackLanes);
        folderStructure.trackLanesMap.put (track, trackLanes);
        return trackLanes;
    }


    /**
     * Parse one item clip.
     *
     * @param dawProject The DAWproject container
     * @param mediaFilesMap Map to collect media files
     * @param itemChunk The item chunk to parse
     * @param sourcePath The path of the source project file
     * @param beatsAndTime The beats and/or time conversion information
     * @param contentTypes The content types of the clips
     * @return The clip
     * @throws ParseException Could not parse a clip
     */
    private Clip convertClip (final DawProjectContainer dawProject, final Map<String, File> mediaFilesMap, final Chunk itemChunk, final File sourcePath, final BeatsAndTime beatsAndTime, final Set<ContentType> contentTypes) throws ParseException
    {
        final Clip clip = new Clip ();

        final boolean destinationIsBeatsBackup = beatsAndTime.destinationIsBeats;
        beatsAndTime.destinationIsBeats = true;

        try
        {
            double clipPosition = getDoubleParam (itemChunk.getChildNode (ReaperTags.ITEM_POSITION), 0);
            double clipDuration = getDoubleParam (itemChunk.getChildNode (ReaperTags.ITEM_LENGTH), 1);

            final boolean clipMute = getDoubleParam (itemChunk.getChildNode (ReaperTags.ITEM_MUTE), 0) > 0;
            if (clipMute)
                clip.enable = Boolean.FALSE;

            clip.name = getParam (itemChunk.getChildNode (ReaperTags.ITEM_NAME), null);
            clip.time = handleTime (beatsAndTime, clipPosition, false);
            clip.contentTimeUnit = beatsAndTime.destinationIsBeats ? TimeUnit.BEATS : TimeUnit.SECONDS;
            clip.duration = Double.valueOf (handleTime (beatsAndTime, clipDuration, false));

            final Optional<Node> notesParameter = itemChunk.getChildNode (ReaperTags.ITEM_NOTES);
            if (notesParameter.isPresent () && notesParameter.get () instanceof final Chunk notesChunk)
                clip.comment = readNotes (notesChunk);

            // FADEIN 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
            final int [] fadeInParams = getIntParams (itemChunk.getChildNode (ReaperTags.ITEM_FADEIN), 0);
            if (fadeInParams.length > 1 && fadeInParams[1] > 0)
                clip.fadeInTime = Double.valueOf (handleTime (beatsAndTime, fadeInParams[1], false));

            // FADEOUT 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
            final int [] fadeOutParams = getIntParams (itemChunk.getChildNode (ReaperTags.ITEM_FADEOUT), 0);
            if (fadeOutParams.length > 1 && fadeOutParams[1] > 0)
                clip.fadeOutTime = Double.valueOf (handleTime (beatsAndTime, fadeOutParams[1], false));

            // TODO support the PLAYRATE of ITEM (at least on Audio)

            final Optional<Node> source = itemChunk.getChildNode (ReaperTags.CHUNK_ITEM_SOURCE);
            if (source.isPresent ())
            {
                final Node sourceNode = source.get ();
                if (sourceNode instanceof final Chunk sourceChunk)
                {
                    final List<String> parameters = sourceChunk.getParameters ();
                    if (parameters.isEmpty ())
                        return null;

                    final Clip internalClip = new Clip ();
                    final double offset = handleTime (beatsAndTime, getDoubleParam (itemChunk.getChildNode (ReaperTags.ITEM_SAMPLE_OFFSET), 0), false);
                    internalClip.time = 0;
                    internalClip.playStart = Double.valueOf (offset);
                    internalClip.contentTimeUnit = clip.contentTimeUnit;
                    internalClip.duration = clip.duration;

                    final String clipType = parameters.get (0);
                    double loopLength = 0;
                    switch (clipType)
                    {
                        case "MIDI":
                            final Lanes lanes = new Lanes ();
                            internalClip.content = lanes;
                            loopLength = convertMIDI (dawProject, sourceChunk, lanes, beatsAndTime);
                            contentTypes.add (ContentType.NOTES);
                            break;

                        case "WAVE", "FLAC":
                            final Audio audio = this.convertAudio (mediaFilesMap, sourceChunk, sourcePath);
                            if (audio == null)
                                return null;
                            internalClip.content = audio;
                            loopLength = handleTime (beatsAndTime, audio.duration, false);
                            contentTypes.add (ContentType.AUDIO);
                            break;

                        default:
                            // Not supported
                            this.notifier.log ("IDS_NOTIFY_CLIPTYPE_NOT_SUPPORTED", clipType);
                            return null;
                    }

                    final boolean isLooped = getIntParam (itemChunk.getChildNode (ReaperTags.ITEM_LOOP), 0) > 0;
                    if (isLooped)
                    {
                        clip.loopStart = Double.valueOf (0);
                        clip.loopEnd = Double.valueOf (loopLength);
                        internalClip.duration = clip.loopEnd;
                    }

                    final Clips clips = new Clips ();
                    clip.content = clips;
                    clips.clips.add (internalClip);
                    return clip;
                }
            }

            return null;
        }
        finally
        {
            beatsAndTime.destinationIsBeats = destinationIsBeatsBackup;
        }
    }


    /**
     * Fill a MIDI clip.
     *
     * @param dawProject The DAWproject container
     * @param sourceChunk The source chunk which contains the clip data
     * @param lanes The lanes where to add the MIDI events
     * @param beatsAndTime The beats and/or time conversion information
     * @return The end of the MIDI events
     * @throws ParseException Could not parse the notes
     */
    private double convertMIDI (final DawProjectContainer dawProject, final Chunk sourceChunk, final Lanes lanes, final BeatsAndTime beatsAndTime) throws ParseException
    {
        final Notes notes = new Notes ();
        lanes.lanes.add (notes);

        final Map<ExpressionType, Map<Integer, Map<Integer, Points>>> envelopes = new EnumMap<> (ExpressionType.class);

        final int ticksPerQuarterNote = readTicksPerQuarterNote (sourceChunk);
        if (ticksPerQuarterNote == -1)
            return 0;

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
                    {
                        this.notifier.logError ("IDS_NOTIFY_ERR_NO_END_NOTE", Integer.toString (midiEvent.getData1 ()), Integer.toString (midiEvent.getData2 ()));
                        continue;
                    }
                    noteStarts.remove (noteStart);

                    final Note note = new Note ();
                    note.channel = channel;

                    final double position = noteStart.getPosition () / (double) ticksPerQuarterNote;
                    final double length = (midiEvent.getPosition () - noteStart.getPosition ()) / (double) ticksPerQuarterNote;
                    note.time = Double.valueOf (handleMIDITime (beatsAndTime, position));
                    note.duration = Double.valueOf (handleMIDITime (beatsAndTime, length));
                    note.key = noteStart.getData1 ();
                    note.velocity = Double.valueOf (noteStart.getData2 () / 127.0);
                    note.releaseVelocity = Double.valueOf (midiEvent.getData2 () / 127.0);

                    notes.notes.add (note);
                    break;

                // Polyphonic Aftertouch
                case 0xA0:
                    final Points paPoints = getEnvelopes (envelopes, ExpressionType.POLY_PRESSURE, channel, midiEvent.getData1 ());
                    addPoint (paPoints, midiEvent, ticksPerQuarterNote, MidiBytes.TWO);
                    break;

                // CC
                case 0xB0:
                    final Points ccPoints = getEnvelopes (envelopes, ExpressionType.CHANNEL_CONTROLLER, channel, midiEvent.getData1 ());
                    addPoint (ccPoints, midiEvent, ticksPerQuarterNote, MidiBytes.TWO);
                    break;

                // Program Change
                case 0xC0:
                    final Points pcPoints = getEnvelopes (envelopes, ExpressionType.PROGRAM_CHANGE, channel, 0);
                    addPoint (pcPoints, midiEvent, ticksPerQuarterNote, MidiBytes.ONE);
                    break;

                // Channel Aftertouch
                case 0xD0:
                    final Points atPoints = getEnvelopes (envelopes, ExpressionType.CHANNEL_PRESSURE, channel, 0);
                    addPoint (atPoints, midiEvent, ticksPerQuarterNote, MidiBytes.ONE);
                    break;

                // Pitch Bend
                case 0xE0:
                    final Points pbPoints = getEnvelopes (envelopes, ExpressionType.PITCH_BEND, channel, 0);
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
                    lanes.lanes.add (points);

        return handleMIDITime (beatsAndTime, currentPosition / (double) ticksPerQuarterNote);
    }


    /**
     * Fill an audio clip.
     *
     * @param mediaFilesMap Map to collect media files
     * @param sourceChunk The audio source chunk
     * @param sourcePath The path of the source project file
     * @return The created Audio clip object
     * @throws ParseException Could not retrieve audio file format
     */
    private Audio convertAudio (final Map<String, File> mediaFilesMap, final Chunk sourceChunk, final File sourcePath) throws ParseException
    {
        final Optional<Node> waveFileOptional = sourceChunk.getChildNode (ReaperTags.SOURCE_FILE);
        if (waveFileOptional.isEmpty ())
            return null;

        final Node waveFileNode = waveFileOptional.get ();
        final List<String> waveFileNodeParameters = waveFileNode.getParameters ();
        if (waveFileNodeParameters.isEmpty ())
            return null;

        final String filePathName = waveFileNodeParameters.get (0).replaceAll ("^\"|\"$", "").replace ('\\', '/');
        final File filePath = new File (filePathName);
        final boolean isAbsolute = filePath.isAbsolute ();
        final boolean noCompression = this.doNotCompressAudioFiles.isSelected ();
        final String filename = isAbsolute ? filePath.getName () : filePathName;

        final Audio audio = new Audio ();
        audio.file = new FileReference ();
        audio.file.path = noCompression ? filePathName : "samples/" + filename;
        if (noCompression)
            audio.file.external = Boolean.valueOf (noCompression);
        audio.algorithm = "raw";

        final File sourceFile = isAbsolute ? filePath : new File (sourcePath, filePathName);
        if (!sourceFile.exists ())
        {
            // Do not crash since the rest of the project file might still be useful
            this.notifier.logError ("IDS_NOTIFY_AUDIO_FILE_NOT_FOUND", sourceFile.getAbsolutePath ());
        }
        else
        {
            this.setAudioAttributes (filename, audio, sourceFile);
            if (!noCompression)
                mediaFilesMap.put (audio.file.path, sourceFile);
        }

        return audio;
    }


    private void setAudioAttributes (final String filename, final Audio audio, final File sourceFile) throws ParseException
    {
        try
        {
            final AudioFileFormat audioFileFormat = AudioSystem.getAudioFileFormat (sourceFile);
            final AudioFormat format = audioFileFormat.getFormat ();
            audio.channels = format.getChannels ();
            audio.sampleRate = (int) format.getSampleRate ();
            audio.duration = getDuration (audioFileFormat);
            if (audio.duration == -1)
                this.notifier.logError ("IDS_NOTIFY_NO_AUDIO_FILE_LENGTH", filename);
        }
        catch (final UnsupportedAudioFileException | IOException ex)
        {
            throw new ParseException (Functions.getMessage ("IDS_NOTIFY_UNKNOWN_AUDIO_FORMAT", sourceFile.getAbsolutePath ()), 0);
        }
    }


    /**
     * Get the resolution of the note times (ticks per quarter note)
     *
     * @param sourceChunk The source chunk from which to read the information
     * @return The resolution or -1 if it could not be read
     */
    private static int readTicksPerQuarterNote (final Chunk sourceChunk)
    {
        final Optional<Node> hasData = sourceChunk.getChildNode (ReaperTags.SOURCE_HASDATA);
        if (hasData.isEmpty ())
            return -1;

        final Node hasDataNode = hasData.get ();
        final List<String> hasDataParameters = hasDataNode.getParameters ();
        if (hasDataParameters.size () != 3 || !"1".equals (hasDataParameters.get (0)))
            return -1;

        final int ticksPerQuarterNote;
        try
        {
            ticksPerQuarterNote = Integer.parseInt (hasDataParameters.get (1));
        }
        catch (final NumberFormatException ex)
        {
            return -1;
        }
        return ticksPerQuarterNote;
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
        points.unit = Unit.PERCENT;
        points.target.channel = channel;
        points.target.expression = type;
        if (type == ExpressionType.CHANNEL_CONTROLLER)
            points.target.controller = keyOrCC;
        else if (type == ExpressionType.POLY_PRESSURE)
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


    /**
     * Get the duration of the audio file from the format object.
     *
     * @param audioFileFormat The audio format
     * @return The duration in seconds
     */
    private static long getDuration (final AudioFileFormat audioFileFormat)
    {
        // Playback duration of the file in microseconds
        final Long duration = (Long) audioFileFormat.properties ().get ("duration");
        if (duration != null)
            return duration.longValue () * 1000L * 1000L;

        // ... and because the duration property is mandatory, use a fallback
        // for uncompressed formats like AIFF and WAVE
        final AudioFormat format = audioFileFormat.getFormat ();
        // Make sure we have a frame length and a frame rate
        if (audioFileFormat.getFrameLength () != AudioSystem.NOT_SPECIFIED && format.getFrameRate () != AudioSystem.NOT_SPECIFIED)
        {
            // Check if this is WAVE or AIFF, other uncompressed formats may work as well
            final Type type = audioFileFormat.getType ();
            if (type == Type.WAVE || type == Type.AIFF || type.getExtension () == "flac")
                return (long) (audioFileFormat.getFrameLength () / format.getFrameRate ());
        }

        return -1;
    }


    /**
     * Converts time to beats, vice versa or not at all depending on the source and destination time
     * base.
     *
     * @param beatsAndTime The beats and/or time conversion information
     * @param time The value to convert
     * @param isEnvelope True if the data is read from an envelope, which might have a different
     *            time base in Reaper
     * @return The value matching the destination time base
     */
    private static double handleTime (final BeatsAndTime beatsAndTime, final double time, final boolean isEnvelope)
    {
        final boolean sourceIsBeats = isEnvelope ? beatsAndTime.sourceIsEnvelopeBeats : beatsAndTime.sourceIsBeats;
        if (sourceIsBeats == beatsAndTime.destinationIsBeats)
            return time;
        return sourceIsBeats ? TempoConverter.beatsToSeconds (time, beatsAndTime.tempoEnvelope) : TempoConverter.secondsToBeats (time, beatsAndTime.tempoEnvelope);
    }


    /**
     * Converts beats (MIDI timing is always in beats) to time if necessary depending on the
     * destination time base.
     *
     * @param beatsAndTime The beats and/or time conversion information
     * @param time The value to convert
     * @return The value matching the destination time base
     */
    private static double handleMIDITime (final BeatsAndTime beatsAndTime, final double time)
    {
        return beatsAndTime.destinationIsBeats ? time : TempoConverter.beatsToSeconds (time, beatsAndTime.tempoEnvelope);
    }


    /**
     * Helper class for creating the folder structure.
     */
    private static class FolderStructure
    {
        final Deque<List<Track>>       folderStack      = new LinkedList<> ();
        List<Track>                    folderTracks;

        final Map<Integer, List<Send>> sendMapping      = new HashMap<> ();
        final Map<Track, Lanes>        trackLanesMap    = new HashMap<> ();
        final Map<Send, Chunk>         sendChunkMapping = new HashMap<> ();
    }


    private static BoolParameter createBoolParameter (final boolean value)
    {
        final BoolParameter parameter = new BoolParameter ();
        parameter.value = Boolean.valueOf (value);
        return parameter;
    }
}
