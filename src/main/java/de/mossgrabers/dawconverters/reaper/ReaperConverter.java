package de.mossgrabers.dawconverters.reaper;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.BoolParameter;
import com.bitwig.dawproject.DawProject;
import com.bitwig.dawproject.FileReference;
import com.bitwig.dawproject.FolderTrack;
import com.bitwig.dawproject.Metadata;
import com.bitwig.dawproject.MixerRole;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.RealParameter;
import com.bitwig.dawproject.Referencable;
import com.bitwig.dawproject.TimeSignatureParameter;
import com.bitwig.dawproject.TimelineRole;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.TrackOrFolder;
import com.bitwig.dawproject.Transport;
import com.bitwig.dawproject.Unit;
import com.bitwig.dawproject.device.Device;
import com.bitwig.dawproject.device.Vst2Plugin;
import com.bitwig.dawproject.device.Vst3Plugin;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.Clips;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Note;
import com.bitwig.dawproject.timeline.Notes;
import com.bitwig.dawproject.timeline.Timebase;
import com.bitwig.dawproject.timeline.Timeline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ReaperConverter
{
    private static final String              PROJECT_TEMPO             = "TEMPO";
    private static final String              PROJECT_RENDER_METADATA   = "RENDER_METADATA";
    private static final String              PROJECT_AUTHOR            = "AUTHOR";
    private static final String              PROJECT_NOTES             = "NOTES";
    private static final String              PROJECT_TIMELOCKMODE      = "TIMELOCKMODE";

    private static final String              MASTER_COLOR              = "MASTERPEAKCOL";
    private static final String              MASTER_NUMBER_OF_CHANNELS = "MASTER_NCH";
    private static final String              MASTER_MUTE_SOLO          = "MASTERMUTESOLO";
    private static final String              MASTER_VOLUME_PAN         = "MASTER_VOLUME";

    private static final String              CHUNK_TRACK               = "TRACK";
    private static final String              TRACK_NAME                = "NAME";
    private static final String              TRACK_COLOR               = "PEAKCOL";
    private static final String              TRACK_STRUCTURE           = "ISBUS";
    private static final String              TRACK_NUMBER_OF_CHANNELS  = "NCHAN";
    private static final String              TRACK_MUTE_SOLO           = "MUTESOLO";
    private static final String              TRACK_VOLUME_PAN          = "VOLPAN";

    private static final String              CHUNK_ITEM                = "ITEM";
    private static final String              ITEM_NAME                 = "NAME";
    private static final String              ITEM_POSITION             = "POSITION";
    private static final String              ITEM_LENGTH               = "LENGTH";

    private static final String              CHUNK_FXCHAIN             = "FXCHAIN";
    private static final String              FXCHAIN_BYPASS            = "BYPASS";
    private static final String              CHUNK_VST                 = "VST";

    private static final String              PLUGIN_VST_2              = "VST";
    private static final String              PLUGIN_VST_2_INSTRUMENT   = "VSTi";
    private static final String              PLUGIN_VST_3              = "VST3";
    private static final String              PLUGIN_VST_3_INSTRUMENT   = "VST3i";

    private static final Pattern             PATTERN_VST_DESCRIPTION   = Pattern.compile ("(VST|VSTi|VST3|VST3i)?:\\s(.*)\\s\\((.*)\\)");
    private static final Pattern             PATTERN_VST2_ID           = Pattern.compile ("(.*)<.*");
    private static final Pattern             PATTERN_VST3_ID           = Pattern.compile (".*\\{(.*)\\}");

    private final Decoder                    decoder                   = Base64.getDecoder ();
    private final Deque<List<TrackOrFolder>> folderStack               = new LinkedList<> ();
    private List<TrackOrFolder>              folderTracks;
    private final Map<File, String>          embeddedFiles             = new HashMap<> ();

    private final Metadata                   metadata                  = new Metadata ();
    private final Project                    project                   = new Project ();


    public ReaperConverter (final Chunk rootChunk) throws ParseException
    {
        Referencable.resetID ();

        this.folderTracks = this.project.tracks;

        this.project.application.name = "Cockos Reaper";
        final List<String> parameters = rootChunk.getParameters ();
        this.project.application.version = parameters.size () > 1 ? parameters.get (1) : "Unknown";

        this.convertMetadata (rootChunk);

        this.convertArrangement (rootChunk);
        this.convertTransport (rootChunk);
        this.convertMaster (rootChunk);

        for (final Node node: rootChunk.getChildNodes ())
        {
            if (node instanceof final Chunk subChunk)
            {
                if (CHUNK_TRACK.equals (subChunk.getName ()))
                    this.convertTrack (subChunk);
                continue;
            }
        }
    }


    public void saveDawProject (final File outputFile) throws IOException
    {
        DawProject.save (this.project, this.metadata, this.embeddedFiles, outputFile);

        // TODO only for testing - remove
        DawProject.saveXML (this.project, new File (outputFile.getParent (), "test-dump.xml"));
    }


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
        if (notesParameter.isPresent () && notesParameter.get ()instanceof Chunk notesChunk)
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
        if (renderMetadataParameter.isPresent () && renderMetadataParameter.get ()instanceof Chunk renderMetadataChunk)
        {
            for (final Node tagNode: renderMetadataChunk.getChildNodes ())
            {
                if (!"TAG".equals (tagNode.getName ()))
                    continue;

                final List<String> parameters = tagNode.getParameters ();
                if (parameters.size () < 2)
                    continue;

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
                }
            }
        }
    }


    private void convertArrangement (final Chunk rootChunk)
    {
        final Arrangement arrangement = new Arrangement ();
        this.project.arrangement = arrangement;

        final Lanes arrangementLanes = new Lanes ();

        final Optional<Node> timelockModeNode = rootChunk.getChildNode (PROJECT_TIMELOCKMODE);
        arrangementLanes.timebase = getIntParam (timelockModeNode, 1) == 0 ? Timebase.seconds : Timebase.beats;

        arrangement.content = arrangementLanes;
    }


    private void convertMaster (final Chunk rootChunk)
    {
        final Track masterTrack = new Track ();
        this.project.tracks.add (masterTrack);
        masterTrack.name = "Master";

        final int numberOfChannels = getIntParam (rootChunk.getChildNode (MASTER_NUMBER_OF_CHANNELS), -1);
        masterTrack.audioChannels = Integer.valueOf (numberOfChannels > 0 ? numberOfChannels : 2);

        final double [] volPan = getDoubleParams (rootChunk.getChildNode (MASTER_VOLUME_PAN), -1);
        if (volPan.length >= 1)
        {
            masterTrack.volume = createRealParameter (Unit.linear, 0.0, 4.0, volPan[0]);
            masterTrack.pan = createRealParameter (Unit.linear, -1.0, 1.0, volPan[1]);
        }

        final int muteSolo = getIntParam (rootChunk.getChildNode (MASTER_MUTE_SOLO), -1);
        if (muteSolo > 0)
        {
            masterTrack.mute = new BoolParameter ();
            masterTrack.mute.value = Boolean.valueOf ((muteSolo & 1) > 0);
            masterTrack.solo = Boolean.valueOf ((muteSolo & 2) > 0);
        }

        masterTrack.mixerRole = MixerRole.master;

        // Set track color
        final int color = getIntParam (rootChunk.getChildNode (MASTER_COLOR), -1);
        if (color >= 0)
            masterTrack.color = toHexColor (color);
    }


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


    private void convertTrack (final Chunk trackChunk) throws ParseException
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

        // Reaper tracks are always hybrid
        track.timelineRole = new TimelineRole []
        {
            TimelineRole.notes,
            TimelineRole.audio
        };

        final int numberOfChannels = getIntParam (trackChunk.getChildNode (TRACK_NUMBER_OF_CHANNELS), -1);
        track.audioChannels = Integer.valueOf (numberOfChannels > 0 ? numberOfChannels : 2);

        // TODO routing
        // track.destination
        // track.sends / track.mixerRole = MixerRole.return

        final double [] volPan = getDoubleParams (trackChunk.getChildNode (TRACK_VOLUME_PAN), -1);
        if (volPan.length >= 1)
        {
            track.volume = createRealParameter (Unit.linear, 0.0, 4.0, volPan[0]);
            track.pan = createRealParameter (Unit.linear, -1.0, 1.0, volPan[1]);
        }

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
            return;

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
                    // Folder tracks are stored as a folder and master track
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
                    this.folderTracks = this.folderStack.removeLast ();
                    break;
            }
        }

        track.devices = this.convertDevices (trackChunk);

        this.convertItems (track, trackChunk);
    }


    private List<Device> convertDevices (final Chunk trackChunk) throws ParseException
    {
        final List<Device> devices = new ArrayList<> ();

        final Optional<Node> fxChainNode = trackChunk.getChildNode (CHUNK_FXCHAIN);
        if (!(fxChainNode.isPresent () && fxChainNode.get ()instanceof final Chunk fxChainChunk))
            return null;

        boolean bypass = false;

        for (final Node node: fxChainChunk.getChildNodes ())
        {
            final String chunkName = node.getName ();
            if (FXCHAIN_BYPASS.equals (chunkName))
            {
                final int [] params = getIntParams (node, 0);
                bypass = params.length > 0 && params[0] > 0;
                continue;
            }

            if (!(CHUNK_VST.equals (chunkName) && node instanceof final Chunk vstChunk))
                continue;

            final List<String> parameters = vstChunk.getParameters ();
            if (parameters.size () < 5)
                continue;

            final Matcher descMatcher = PATTERN_VST_DESCRIPTION.matcher (parameters.get (0));
            if (!descMatcher.matches ())
                continue;

            final Device device;
            final String fileEnding;
            final Pattern idPattern;
            switch (descMatcher.group (1))
            {
                case PLUGIN_VST_2:
                case PLUGIN_VST_2_INSTRUMENT:
                    device = new Vst2Plugin ();
                    fileEnding = ".fxp";
                    idPattern = PATTERN_VST2_ID;
                    break;

                case PLUGIN_VST_3:
                case PLUGIN_VST_3_INSTRUMENT:
                    device = new Vst3Plugin ();
                    fileEnding = ".vstpreset";
                    idPattern = PATTERN_VST3_ID;
                    break;

                default:
                    // Not supported but should never be reached
                    continue;
            }

            final Matcher idMatcher = idPattern.matcher (parameters.get (4));
            if (!idMatcher.matches ())
                continue;

            device.name = descMatcher.group (2);
            device.deviceName = device.name;
            device.deviceVendor = descMatcher.group (3);
            device.deviceID = idMatcher.group (1);
            device.enabled = new BoolParameter ();
            device.enabled.value = Boolean.valueOf (!bypass);
            device.enabled.name = "On/Off";

            device.state = new FileReference ();
            final String filename = UUID.randomUUID ().toString () + fileEnding;
            device.state.path = "plugins/" + filename;

            try
            {
                final File tempFile = File.createTempFile ("dawproject-", "-converter");
                tempFile.deleteOnExit ();

                try (final FileOutputStream out = new FileOutputStream (tempFile); final ByteArrayOutputStream baos = new ByteArrayOutputStream ())
                {
                    for (final Node childNode: vstChunk.getChildNodes ())
                        baos.write (this.decoder.decode (childNode.getName ().trim ()));

                    final byte [] byteArray = baos.toByteArray ();
                    final InputStream in = new ByteArrayInputStream (byteArray);

                    final Vst2Chunk vst2Chunk = new Vst2Chunk ();
                    vst2Chunk.read (in);
                    vst2Chunk.writePreset (out);
                }

                this.embeddedFiles.put (tempFile, device.state.path);
            }
            catch (final IOException ex)
            {
                throw new ParseException ("Could not store plugin state.", 0);
            }

            devices.add (device);
        }

        return devices.isEmpty () ? null : devices;
    }


    private void convertItems (final Track track, final Chunk trackChunk)
    {
        final Lanes lanes = (Lanes) this.project.arrangement.content;
        lanes.track = track;

        final Lanes trackLanes = new Lanes ();
        final List<Timeline> laneItems = lanes.lanes;
        laneItems.add (trackLanes);

        trackLanes.track = track;

        final Clips clips = new Clips ();

        for (final Node node: trackChunk.getChildNodes ())
        {
            final String chunkName = node.getName ();
            if (!CHUNK_ITEM.equals (chunkName))
                continue;

            if (node instanceof Chunk itemChunk)
            {
                final Clip clip = new Clip ();
                clips.clips.add (clip);

                clip.name = getParam (itemChunk.getChildNode (ITEM_NAME), null);
                clip.time = toBeats (getDoubleParam (itemChunk.getChildNode (ITEM_POSITION), 0));
                clip.duration = toBeats (getDoubleParam (itemChunk.getChildNode (ITEM_LENGTH), 1));

                // TODO
                // clip.fadeInTime FADEIN 1 0 0 1 0 0 0
                // clip.fadeOutTime FADEOUT 1 0 0 1 0 0 0

                final Notes notes = new Notes ();
                clip.content = notes;

                Note note = new Note ();
                // note.channel
                note.key = 64;
                note.velocity = 1.0;
                note.time = 1.0;
                note.duration = 2.0;

                // notes.notes
            }
        }

        if (!clips.clips.isEmpty ())
            trackLanes.lanes.add (clips);
    }


    private double toBeats (double value)
    {
        final double tempo = this.project.transport.tempo.value.doubleValue ();
        final double bps = tempo / 60.0;
        return bps * value;
    }


    private String getParam (final Optional<Node> optionalNode, final String defaultValue)
    {
        if (optionalNode.isEmpty ())
            return defaultValue;
        final List<String> parameters = optionalNode.get ().getParameters ();
        return parameters.isEmpty () ? defaultValue : parameters.get (0);
    }


    private static int getIntParam (final Optional<Node> node, final int defaultValue)
    {
        final int [] result = getIntParams (node, defaultValue);
        return result.length > 0 ? result[0] : defaultValue;
    }


    private static int [] getIntParams (final Optional<Node> optionalNode, final int defaultValue)
    {
        return optionalNode.isEmpty () ? new int [0] : getIntParams (optionalNode.get (), defaultValue);
    }


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


    private static double getDoubleParam (final Optional<Node> node, final double defaultValue)
    {
        final double [] result = getDoubleParams (node, defaultValue);
        return result.length > 0 ? result[0] : defaultValue;
    }


    private static double [] getDoubleParams (final Optional<Node> node, final double defaultValue)
    {
        if (node.isEmpty ())
            return new double [0];

        final List<String> parameters = node.get ().getParameters ();
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


    private static String toHexColor (final int color)
    {
        // Remove alpha
        final int c = 0xFFFFFF & color;
        return String.format ("#%02x%02x%02x", Integer.valueOf (c & 0xFF), Integer.valueOf (c >> 8 & 0xFF), Integer.valueOf (c >> 16 & 0xFF));
    }


    private static RealParameter createRealParameter (final Unit unit, final double min, final double max, final double value)
    {
        final RealParameter param = new RealParameter ();
        param.unit = unit;
        param.min = Double.valueOf (min);
        param.max = Double.valueOf (max);
        param.value = Double.valueOf (value);
        return param;
    }
}
