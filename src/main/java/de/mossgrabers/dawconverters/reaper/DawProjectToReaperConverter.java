// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021
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
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Marker;
import com.bitwig.dawproject.timeline.Markers;
import com.bitwig.dawproject.timeline.Timebase;
import com.bitwig.dawproject.timeline.Timeline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


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


    private final File     inputFile;
    private final Metadata metadata;
    private final Project  project;
    private final Chunk    rootChunk = new Chunk ();
    private boolean        isBeats;
    private Lanes          arrangementLanes;


    /**
     * Constructor.
     *
     * @param inputFile The dawproject file
     * @throws IOException
     */
    public DawProjectToReaperConverter (final File inputFile) throws IOException
    {
        this.metadata = DawProject.loadMetadata (inputFile);
        this.project = DawProject.loadProject (inputFile);

        this.inputFile = inputFile;

        setNode (this.rootChunk, PROJECT_ROOT, "0.1", "6.33/win64");

        this.convertMetadata ();
        this.convertArrangement ();
        this.convertTransport ();
        this.convertMarkers ();

        final List<TrackOrFolder> tracks = this.project.tracks;
        if (tracks == null)
            return;

        // Find the master track and handle it separately
        for (final TrackOrFolder trackOrFolder: tracks)
        {
            if (trackOrFolder instanceof final Track track && track.mixerRole == MixerRole.master)
            {
                tracks.remove (track);
                this.convertMaster (track);
                break;
            }
        }

        this.convertTracks (tracks);
    }


    /**
     * Stores the filled Reaper structure into the given file.
     *
     * @param outputFile The file to store the structure to
     * @throws IOException Could not write the file
     */
    public void saveProject (final File outputFile) throws IOException
    {
        try (final FileWriter writer = new FileWriter (outputFile, StandardCharsets.UTF_8))
        {
            writer.append (ReaperProject.format (this.rootChunk));
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
     */
    private void convertArrangement ()
    {
        final Arrangement arrangement = this.project.arrangement;
        if (arrangement == null || arrangement.content == null)
            return;

        this.isBeats = arrangement.content.timebase == Timebase.beats;
        addNode (this.rootChunk, PROJECT_TIMELOCKMODE, this.isBeats ? "1" : "0");

        if (arrangement.content instanceof final Lanes lanes)
            this.arrangementLanes = lanes;
    }


    /**
     * Assigns the Transport data to different Reaper settings.
     */
    private void convertTransport ()
    {
        final Transport transport = this.project.transport;
        if (transport == null)
            return;

        final Double tempo = transport.tempo == null ? Double.valueOf (120) : transport.tempo.value;
        final Integer numerator = transport.timeSignature == null ? Integer.valueOf (4) : transport.timeSignature.numerator;
        final Integer denominator = transport.timeSignature == null ? Integer.valueOf (4) : transport.timeSignature.denominator;
        addNode (this.rootChunk, PROJECT_TEMPO, tempo.toString (), numerator.toString (), denominator.toString ());
    }


    /**
     * Assigns the Markers data to different Reaper settings.
     */
    private void convertMarkers ()
    {
        if (this.arrangementLanes == null || this.arrangementLanes.lanes == null)
            return;

        for (final Timeline timeline: this.arrangementLanes.lanes)
        {
            if (timeline instanceof final Markers markers)
            {
                if (markers.markers == null)
                    continue;
                for (int i = 0; i < markers.markers.size (); i++)
                {
                    final Marker marker = markers.markers.get (i);
                    final double position = this.isBeats ? this.toTime (marker.time) : marker.time;
                    final int color = marker.color == null ? 0 : fromHexColor (marker.color);
                    addNode (this.rootChunk, PROJECT_MARKER, Integer.toString (i), Double.toString (position), marker.name, "0", Integer.toString (color));
                }
            }
        }
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

        // Convert all FX devices
        this.convertDevices (masterTrack.devices, this.rootChunk, MASTER_CHUNK_FXCHAIN);
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
        }
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
        createTrackStructure (tracks, flatTracks, true);

        final Map<Track, Chunk> chunkMapping = new HashMap<> ();
        final Map<Track, Integer> trackMapping = new HashMap<> ();

        for (int i = 0; i < flatTracks.size (); i++)
        {
            final TrackInfo trackInfo = flatTracks.get (i);
            final Chunk trackChunk = addChunk (this.rootChunk, CHUNK_TRACK);

            final Track track = trackInfo.track;
            createTrack (trackChunk, trackInfo.folder == null ? trackInfo.track : trackInfo.folder, trackInfo.type, trackInfo.direction);

            if (track == null)
                continue;

            chunkMapping.put (track, trackChunk);
            trackMapping.put (track, Integer.valueOf (i));

            // Number of channels
            if (track.audioChannels != null)
                addNode (trackChunk, TRACK_NUMBER_OF_CHANNELS, track.audioChannels.toString ());

            // Volume & Panorama
            if (track.volume != null)
            {
                if (track.volume.unit != Unit.linear || (track.pan != null && track.pan.unit != Unit.linear))
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

        // Set sends
        for (final TrackInfo trackInfo: flatTracks)
        {
            final Track track = trackInfo.track;
            if (track == null || track.sends == null)
                continue;

            for (final Send send: track.sends)
            {
                if (send.destination == null || send.value == null)
                    continue;
                if (send.unit != Unit.linear)
                    throw new IOException (ONLY_LINEAR);
                final Chunk auxChunk = chunkMapping.get (send.destination);
                final Integer index = trackMapping.get (track);
                if (auxChunk != null && index != null)
                {
                    final String mode = send.type == null || send.type == SendType.post ? "0" : "1";
                    addNode (auxChunk, TRACK_AUX_RECEIVE, index.toString (), mode, send.value.toString (), "0");
                }
            }
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

            if (trackOrFolder instanceof FolderTrack folder)
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
                        if (child instanceof Track childTrack && childTrack.mixerRole == MixerRole.master)
                        {
                            trackInfo.track = childTrack;
                            children.remove (childTrack);
                            break;
                        }
                    }

                    trackInfo.type = children.isEmpty () ? 2 : 1;
                    if (trackInfo.type == 1)
                        trackInfo.direction = 1;

                    createTrackStructure (children, flatTracks, false);
                }
            }
            else if (trackOrFolder instanceof Track track)
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


    private static void handleVstDevice (final Chunk vstChunk, final Device device, final InputStream in) throws IOException
    {
        if (device instanceof final Vst2Plugin)
            handleVst2Device (vstChunk, in);
        else if (device instanceof final Vst3Plugin)
            handleVst3Device (vstChunk, in);
    }


    private static void handleVst2Device (final Chunk vstChunk, final InputStream in) throws IOException
    {
        final VstChunkHandler vstChunkHandler = new VstChunkHandler ();
        vstChunkHandler.readVST2Preset (in);
        vstChunkHandler.create (vstChunk);
    }


    private static void handleVst3Device (final Chunk vstChunk, final InputStream in) throws IOException
    {
        final VstChunkHandler vstChunkHandler = new VstChunkHandler ();
        vstChunkHandler.readVST3Preset (in);
        vstChunkHandler.create (vstChunk);
    }


    private static Chunk addChunk (final Chunk parentChunk, final String childName, final String... parameters)
    {
        return (Chunk) addNode (parentChunk, new Chunk (), childName, parameters);
    }


    private static Node addNode (final Chunk parentChunk, final String childName, final String... parameters)
    {
        return addNode (parentChunk, new Node (), childName, parameters);
    }


    private static Node addNode (final Chunk parentChunk, final Node child, final String childName, final String... parameters)
    {
        parentChunk.addChildNode (child);
        setNode (child, childName, parameters);
        return child;
    }


    private static void setNode (final Node node, final String nodeName, final String... parameters)
    {
        node.setName (nodeName);
        node.getParameters ().addAll (Arrays.asList (parameters));
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
            sb.append ((char) ((number >> (8 * i)) & 0xFF));
        return sb.toString ();
    }
}
