// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.bitwig.dawproject.Channel;
import com.bitwig.dawproject.ContentType;
import com.bitwig.dawproject.Lane;
import com.bitwig.dawproject.MetaData;
import com.bitwig.dawproject.MixerRole;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.RealParameter;
import com.bitwig.dawproject.Scene;
import com.bitwig.dawproject.Send;
import com.bitwig.dawproject.SendType;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.Transport;
import com.bitwig.dawproject.Unit;
import com.bitwig.dawproject.device.BuiltinDevice;
import com.bitwig.dawproject.device.ClapPlugin;
import com.bitwig.dawproject.device.Device;
import com.bitwig.dawproject.device.DeviceRole;
import com.bitwig.dawproject.device.Plugin;
import com.bitwig.dawproject.device.Vst2Plugin;
import com.bitwig.dawproject.device.Vst3Plugin;
import com.bitwig.dawproject.timeline.Audio;
import com.bitwig.dawproject.timeline.BoolPoint;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.ClipSlot;
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
import com.bitwig.dawproject.timeline.Timeline;
import com.bitwig.dawproject.timeline.Warp;
import com.bitwig.dawproject.timeline.Warps;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IDestinationFormat;
import de.mossgrabers.projectconverter.core.IMediaFiles;
import de.mossgrabers.projectconverter.core.TimeUtils;
import de.mossgrabers.projectconverter.format.Conversions;
import de.mossgrabers.projectconverter.format.reaper.model.Chunk;
import de.mossgrabers.projectconverter.format.reaper.model.ClapChunkHandler;
import de.mossgrabers.projectconverter.format.reaper.model.Node;
import de.mossgrabers.projectconverter.format.reaper.model.ReaperMidiEvent;
import de.mossgrabers.projectconverter.format.reaper.model.ReaperProject;
import de.mossgrabers.projectconverter.format.reaper.model.VstChunkHandler;
import de.mossgrabers.tools.ui.BasicConfig;
import de.mossgrabers.tools.ui.panel.BoxPanel;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;


/**
 * Converts a Reaper project file (the already loaded chunks to be more specific) into a dawproject
 * structure. Needs to be state-less.
 *
 * @author Jürgen Moßgraber
 */
public class ReaperDestinationFormat extends AbstractCoreTask implements IDestinationFormat
{
    private static final int                   TICKS_PER_QUARTER_NOTE = 960;

    private static final String                CLIPS_SOURCE           = "CLIPS_SOURCE";
    private static final Map<Class<?>, String> PLUGIN_TYPES           = new HashMap<> ();
    static
    {
        PLUGIN_TYPES.put (Vst2Plugin.class, ReaperTags.PLUGIN_VST_2);
        PLUGIN_TYPES.put (Vst3Plugin.class, ReaperTags.PLUGIN_VST_3);
        PLUGIN_TYPES.put (ClapPlugin.class, ReaperTags.PLUGIN_CLAP);
    }


    /** Helper structure to create a flat track list. */
    private static class TrackInfo
    {
        Track folder    = null;
        Track track     = null;
        int   type      = 0;
        int   direction = 0;
    }


    private ToggleGroup arrangementOrScenesGroup;


    /**
     * Constructor.
     *
     * @param notifier The notifier
     */
    public ReaperDestinationFormat (final INotifier notifier)
    {
        super ("Reaper", notifier);
    }


    /** {@inheritDoc} */
    @Override
    public javafx.scene.Node getEditPane ()
    {
        final BoxPanel panel = new BoxPanel (Orientation.VERTICAL);

        panel.createSeparator ("@IDS_ARRANGEMENT_OR_SCENES");

        this.arrangementOrScenesGroup = new ToggleGroup ();
        final RadioButton order1 = panel.createRadioButton ("@IDS_SOURCE_ARRANGEMENT");
        order1.setToggleGroup (this.arrangementOrScenesGroup);
        final RadioButton order2 = panel.createRadioButton ("@IDS_SOURCE_SCENES");
        order2.setToggleGroup (this.arrangementOrScenesGroup);

        return panel.getPane ();
    }


    /** {@inheritDoc} */
    @Override
    public void loadSettings (final BasicConfig config)
    {
        final int sourceIndex = config.getInteger (CLIPS_SOURCE, 0);
        final ObservableList<Toggle> toggles = this.arrangementOrScenesGroup.getToggles ();
        this.arrangementOrScenesGroup.selectToggle (toggles.get (sourceIndex < toggles.size () ? sourceIndex : 0));
    }


    /** {@inheritDoc} */
    @Override
    public void saveSettings (final BasicConfig config)
    {
        final ObservableList<Toggle> toggles = this.arrangementOrScenesGroup.getToggles ();
        for (int i = 0; i < toggles.size (); i++)
        {
            if (toggles.get (i).isSelected ())
            {
                config.setInteger (CLIPS_SOURCE, i);
                break;
            }
        }
    }


    /** {@inheritDoc} */
    @Override
    public boolean needsOverwrite (final String projectName, final File outputPath)
    {
        return getOutputPath (projectName, outputPath).exists ();
    }


    private static File getOutputPath (final String projectName, final File outputPath)
    {
        return new File (outputPath, projectName);
    }


    /** {@inheritDoc} */
    @Override
    public void write (final DawProjectContainer dawProject, final File outputPath) throws IOException
    {
        final Parameters parameters = new Parameters ();

        final Chunk rootChunk = new Chunk ();
        final Project project = dawProject.getProject ();

        setNode (rootChunk, ReaperTags.PROJECT_ROOT, "0.1", "6.33/win64");

        convertMetadata (dawProject.getMetadata (), rootChunk);
        final boolean sourceIsBeats = TimeUtils.getArrangementTimeUnit (project.arrangement);
        convertTransport (project, rootChunk, parameters);

        final List<Lane> lanes = project.structure;
        if (lanes == null)
            return;

        // Find the master track and handle it separately - this is not stable since it requires the
        // name to be Master
        final Track masterTrack = this.findMastertrack (dawProject, rootChunk, lanes);
        if (masterTrack == null)
            this.notifier.logError ("IDS_NOTIFY_NO_MASTERTRACK_FOUND");

        this.convertTracks (dawProject.getMediaFiles (), lanes, rootChunk, parameters);

        parameters.destinationIsBeats = true;

        final String value = parameters.destinationIsBeats ? "1" : "0";
        addNode (rootChunk, ReaperTags.PROJECT_TIME_LOCKMODE, value);
        addNode (rootChunk, ReaperTags.PROJECT_TIME_ENV_LOCKMODE, value);

        // Time values are always in seconds, indicators above seem to be only for visual
        // information
        parameters.destinationIsBeats = false;

        if (this.arrangementOrScenesGroup.getToggles ().get (0).isSelected ())
            this.convertArrangementLanes (rootChunk, project, masterTrack, parameters, sourceIsBeats);
        else
            this.convertScenes (rootChunk, project, masterTrack, parameters, sourceIsBeats);

        this.saveProject (rootChunk, dawProject, parameters, getOutputPath (dawProject.getName (), outputPath));
    }


    private Track findMastertrack (final DawProjectContainer dawProject, final Chunk rootChunk, final List<Lane> lanes) throws IOException
    {
        Track mastertrack = null;

        for (final Lane lane: lanes)
        {
            if (lane instanceof final Track track && "Master".equals (track.name) && track.channel != null && track.channel.role == MixerRole.master)
            {
                lanes.remove (track);
                mastertrack = track;
                break;
            }
            else if (lane instanceof final Channel channel && channel.role == MixerRole.master)
            {
                lanes.remove (channel);
                mastertrack = wrapChannelIntoTrack (channel);
                break;
            }
        }

        if (mastertrack != null)
            this.convertMaster (dawProject.getMediaFiles (), mastertrack, rootChunk);

        // Wrap all other top level channels into a track
        for (int i = 0; i < lanes.size (); i++)
        {
            final Lane lane = lanes.get (i);
            if (lane instanceof final Channel channel)
                lanes.set (i, wrapChannelIntoTrack (channel));
        }

        return mastertrack;
    }


    private static Track wrapChannelIntoTrack (final Channel channel)
    {
        final Track track = new Track ();
        track.channel = channel;
        track.name = channel.name;
        track.contentType = new ContentType []
        {
            ContentType.audio
        };
        return track;
    }


    /**
     * Stores the filled Reaper structure into the given file.
     *
     * @param rootChunk The root chunk of the Reaper project to store
     * @param dawProject The name of the project file to store to
     * @param parameters The parameters
     * @param destinationPath The path to store the project and audio files to
     * @throws IOException Could not write the file
     */
    private void saveProject (final Chunk rootChunk, final DawProjectContainer dawProject, final Parameters parameters, final File destinationPath) throws IOException
    {
        if (!destinationPath.exists () && !destinationPath.mkdir ())
        {
            this.notifier.logError ("IDS_NOTIFY_COULD_NOT_CREATE_OUTPUT_DIR");
            return;
        }

        final String projectName = dawProject.getName () + ".rpp";
        final File outputFile = new File (destinationPath, projectName);

        // Store the project file
        final String formattedChunk = ReaperProject.format (rootChunk);
        try (final FileWriter writer = new FileWriter (outputFile, StandardCharsets.UTF_8))
        {
            writer.append (formattedChunk);
        }

        // Store all referenced wave files
        final IMediaFiles mediaFiles = dawProject.getMediaFiles ();
        for (final String audioFile: parameters.audioFiles)
        {
            if (this.notifier.isCancelled ())
                return;

            final String name = new File (audioFile).getName ();
            final File sampleOutputFile = new File (destinationPath, name);
            try (final InputStream in = mediaFiles.stream (audioFile))
            {
                final Path path = sampleOutputFile.toPath ();
                this.notifier.log ("IDS_NOTIFY_WRITING_AUDIO_FILE", path.toString ());
                Files.copy (in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (final FileNotFoundException ex)
            {
                this.notifier.logError ("IDS_NOTIFY_AUDIO_FILE_NOT_FOUND", audioFile);
            }
            catch (final IOException ex)
            {
                this.notifier.logError ("IDS_NOTIFY_COULD_NOT_CREATE_AUDIO_CHUNK", audioFile);
            }
        }
    }


    /**
     * Assigns the metadata to different Reaper settings.
     *
     * @param metadata The metadata to read from
     * @param rootChunk The root chunk to add the information
     */
    private static void convertMetadata (final MetaData metadata, final Chunk rootChunk)
    {
        // Only one Authors field in Reaper
        final StringBuilder sb = new StringBuilder ();
        if (metadata.artist != null && !metadata.artist.isBlank ())
            sb.append (metadata.artist);
        if (metadata.producer != null && !metadata.producer.isBlank ())
        {
            if (!sb.isEmpty ())
                sb.append (", ");
            sb.append (metadata.producer);
        }
        if (metadata.composer != null && !metadata.composer.isBlank ())
        {
            if (!sb.isEmpty ())
                sb.append (", ");
            sb.append (metadata.composer);
        }
        if (metadata.songwriter != null && !metadata.songwriter.isBlank ())
        {
            if (!sb.isEmpty ())
                sb.append (", ");
            sb.append (metadata.songwriter);
        }

        final String author = sb.toString ();
        if (!author.isBlank ())
            addNode (rootChunk, ReaperTags.PROJECT_AUTHOR, author);

        // Comment goes into the Notes
        if (metadata.comment != null)
            createNotesChunk (rootChunk, metadata.comment, ReaperTags.PROJECT_NOTES);

        // Fill render metadata
        final Chunk renderChunk = addChunk (rootChunk, ReaperTags.PROJECT_RENDER_METADATA);
        if (metadata.comment != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:COMM", metadata.comment.replace ("\r", "").replace ("\n", " "));
        if (metadata.composer != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TCOM", metadata.composer);
        if (metadata.songwriter != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TCOM", metadata.songwriter);
        if (metadata.genre != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TCON", metadata.genre);
        if (metadata.copyright != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TCOP", metadata.copyright);
        if (metadata.producer != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TIPL", metadata.producer);
        if (metadata.title != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TIT2", metadata.title);
        if (metadata.artist != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TPE1", metadata.artist);
        if (metadata.originalArtist != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TPE2", metadata.originalArtist);
        if (metadata.year != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TYER", metadata.year);
        if (metadata.album != null)
            addNode (renderChunk, ReaperTags.METADATA_TAG, "ID3:TALB", metadata.album);
    }


    private static void createNotesChunk (final Chunk rootChunk, final String comment, final String tag)
    {
        final Chunk notesChunk = addChunk (rootChunk, tag, "0");
        final String [] commentLines = comment.split ("\\R");
        for (final String line: commentLines)
            addNode (notesChunk, "|" + line);
    }


    /**
     * Assigns the Transport data to different Reaper settings.
     *
     * @param project The project to read from
     * @param rootChunk The root chunk to add the information
     * @param parameters The parameters
     */
    private static void convertTransport (final Project project, final Chunk rootChunk, final Parameters parameters)
    {
        final Transport transport = project.transport;
        if (transport == null)
            return;

        parameters.tempo = transport.tempo == null ? Double.valueOf (120) : transport.tempo.value;
        parameters.numerator = transport.timeSignature == null || transport.timeSignature.numerator == null ? 4 : transport.timeSignature.numerator.intValue ();
        parameters.denominator = transport.timeSignature == null || transport.timeSignature.denominator == null ? 4 : transport.timeSignature.denominator.intValue ();
        addNode (rootChunk, ReaperTags.PROJECT_TEMPO, parameters.tempo.toString (), Integer.toString (parameters.numerator), Integer.toString (parameters.denominator));
    }


    /**
     * Assigns the Master track data to different Reaper settings.
     *
     * @param mediaFiles Access to additional media files
     * @param masterTrack The master track
     * @param rootChunk The root chunk to add the information
     * @throws IOException Units must be linear
     */
    private void convertMaster (final IMediaFiles mediaFiles, final Track masterTrack, final Chunk rootChunk) throws IOException
    {
        if (masterTrack.channel == null)
            return;

        final Channel channel = masterTrack.channel;

        addNode (rootChunk, ReaperTags.MASTER_NUMBER_OF_CHANNELS, channel.audioChannels == null ? "2" : channel.audioChannels.toString ());

        if (channel.volume != null && channel.pan != null)
            addNode (rootChunk, ReaperTags.MASTER_VOLUME_PAN, this.convertVolume (channel.volume), this.convertPanorama (channel.pan));

        int state = channel.solo != null && channel.solo.booleanValue () ? 2 : 0;
        if (channel.mute != null && channel.mute.value.booleanValue ())
            state |= 1;
        addNode (rootChunk, ReaperTags.MASTER_MUTE_SOLO, Integer.toString (state));

        if (masterTrack.color != null)
            addNode (rootChunk, ReaperTags.MASTER_COLOR, Integer.toString (this.fromHexColor (masterTrack.color)));

        this.convertDevices (mediaFiles, channel.devices, masterTrack.loaded == null || masterTrack.loaded.booleanValue (), rootChunk, ReaperTags.MASTER_CHUNK_FXCHAIN);
    }


    /**
     * Assigns the data of all Tracks to different Reaper settings.
     *
     * @param mediaFiles Access to additional media files
     * @param lanes The lanes which contain the tracks to convert
     * @param rootChunk The root chunk to add the information
     * @param parameters The parameters
     * @throws IOException Units must be linear
     */
    private void convertTracks (final IMediaFiles mediaFiles, final List<Lane> lanes, final Chunk rootChunk, final Parameters parameters) throws IOException
    {
        final List<TrackInfo> flatTracks = new ArrayList<> ();
        this.createTrackStructure (getTracks (lanes), flatTracks, true, parameters);

        for (int i = 0; i < flatTracks.size (); i++)
            this.convertTrack (mediaFiles, flatTracks, i, rootChunk, parameters);

        // Set sends and create automation
        for (final TrackInfo trackInfo: flatTracks)
        {
            final Track track = trackInfo.track;
            if (track != null)
                this.convertSends (track, parameters);
        }
    }


    /**
     * Convert all sends of the track.
     *
     * @param track The track
     * @param parameters The parameters
     * @throws IOException Units must be linear
     */
    private void convertSends (final Track track, final Parameters parameters) throws IOException
    {
        if (track.channel == null || track.channel.sends == null)
            return;
        for (final Send send: track.channel.sends)
        {
            if (send.destination == null || send.volume == null)
                continue;
            final Track destinationTrack = parameters.channelMapping.get (send.destination);
            if (destinationTrack != null)
            {
                final Chunk auxChunk = parameters.chunkMapping.get (destinationTrack);
                final Integer index = parameters.trackMapping.get (track);
                if (auxChunk != null && index != null)
                {
                    final String mode = send.type == null || send.type == SendType.post ? "0" : "1";
                    addNode (auxChunk, ReaperTags.TRACK_AUX_RECEIVE, index.toString (), mode, this.getValue (send.volume, Unit.linear), "0");

                    // TODO add panorama
                }
            }
        }
    }


    /**
     * Assigns the data of all Devices of a track to different Reaper settings.
     *
     * @param mediaFiles Access to additional media files
     * @param devices The devices to convert
     * @param isTrackActive Is the track active?
     * @param parentChunk The chunk where to add the data
     * @param fxChainName The name of the FX chain chunk
     * @throws IOException Could not create the VST chunks
     */
    private void convertDevices (final IMediaFiles mediaFiles, final List<Device> devices, final boolean isTrackActive, final Chunk parentChunk, final String fxChainName) throws IOException
    {
        if (devices == null || devices.isEmpty ())
            return;

        final Chunk fxChunk = addChunk (parentChunk, fxChainName);

        for (final Device device: devices)
        {
            if (device.state != null)
                this.convertDevice (device, fxChunk, mediaFiles, isTrackActive);
        }
    }


    /**
     * Assigns the data of one Device of a track to different Reaper settings.
     *
     * @param device The device to convert
     * @param fxChunk The FX chunk where to add the device information
     * @param mediaFiles Access to additional media files
     * @param isTrackActive Is the track active?
     * @throws IOException Could not create the VST chunks
     */
    private void convertDevice (final Device device, final Chunk fxChunk, final IMediaFiles mediaFiles, final boolean isTrackActive) throws IOException
    {
        final boolean bypass = device.enabled != null && device.enabled.value != null && !device.enabled.value.booleanValue ();
        final boolean offline = !isTrackActive || device.loaded != null && !device.loaded.booleanValue ();
        addNode (fxChunk, ReaperTags.FXCHAIN_BYPASS, bypass ? "1" : "0", offline ? "1" : "0");

        if (device instanceof Vst2Plugin || device instanceof Vst3Plugin)
        {
            this.convertVstDevice (device, fxChunk, mediaFiles);
        }
        else if (device instanceof final ClapPlugin clapPlugin)
        {
            this.convertClapDevice (clapPlugin, fxChunk, mediaFiles);
        }
        else if (device instanceof BuiltinDevice)
        {
            // TODO Support built-in devices
        }
        else
        {
            // Note: AU plugins can be supported here but only necessary if there is another
            // project source format besides Reaper which supports AU (Bitwig does not).
            this.notifier.logError ("IDS_NOTIFY_PLUGIN_TYPE_NOT_SUPPORTED", device.getClass ().getName ());
        }

        // TODO convert VST parameter envelopes -> needs parameter ID fix from Bitwig export
    }


    /**
     * Convert a VST device to Reaper.
     *
     * @param device The device to convert
     * @param fxChunk The FX chunk where to add the device information
     * @param mediaFiles Access to additional media files
     * @throws IOException Could not create the VST chunks
     */
    private void convertVstDevice (final Device device, final Chunk fxChunk, final IMediaFiles mediaFiles) throws IOException
    {
        // Create the Reaper device ID for the VST chunk
        final StringBuilder id = new StringBuilder ();
        if (device instanceof Vst2Plugin)
        {
            final StringBuilder fakeVst3ID = new StringBuilder ("VST");
            // VST2 ID transformed to ASCII text
            final int vstID = Integer.parseInt (device.deviceID);
            fakeVst3ID.append (intToText (vstID));
            // First 9 lower case characters of the device name
            String fakeID = fakeVst3ID.append (device.deviceName.toLowerCase (Locale.US)).toString ();
            fakeID = fakeID.substring (0, Math.min (16, fakeID.length ()));

            id.append (device.deviceID).append ("<");
            for (int i = 0; i < 16; i++)
                id.append (i < fakeID.length () ? Integer.toHexString (fakeID.charAt (i)) : "00");
            id.append (">");
        }
        else if (device instanceof Vst3Plugin)
        {
            // Quick and dirty endian change
            final String [] uuidParts = new String [5];
            uuidParts[0] = device.deviceID.substring (0, 8);
            uuidParts[1] = device.deviceID.substring (8, 12);
            uuidParts[2] = device.deviceID.substring (12, 16);
            uuidParts[3] = device.deviceID.substring (16, 20);
            uuidParts[4] = device.deviceID.substring (20, 32);
            final UUID uuid = UUID.fromString (flipBytes (uuidParts[0]) + "-" + flipBytes (uuidParts[1]) + "-" + flipBytes (uuidParts[2]) + "-" + uuidParts[3] + "-" + uuidParts[4]);
            id.append (calculateFNV1AHash (uuid)).append ("{").append (device.deviceID).append ("}");
        }

        final Chunk vstChunk = addChunk (fxChunk, ReaperTags.CHUNK_VST, createDeviceName (device), "\"\"", "0", "\"\"", id.toString ());
        try (final InputStream in = mediaFiles.stream (device.state.path))
        {
            new VstChunkHandler (device instanceof Vst2Plugin, null).fileToChunk (in, vstChunk);
        }
        catch (final FileNotFoundException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_DEVICE_STATE_FILE_NOT_FOUND", device.state.path);
        }
        catch (final IOException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_COULD_NOT_CREATE_VST_CHUNK", device.state.path);
        }
    }


    private static int calculateFNV1AHash (final UUID uuid)
    {
        final ByteBuffer buffer = ByteBuffer.wrap (new byte [16]);
        buffer.putLong (uuid.getMostSignificantBits ());
        buffer.putLong (uuid.getLeastSignificantBits ());

        final int FNV_PRIME = 0x01000193;
        final int FNV_OFFSET_BASIS = 0x811c9dc5;

        int checksum = FNV_OFFSET_BASIS;
        for (int i = 0; i < buffer.capacity (); i++)
        {
            checksum ^= buffer.get (i) & 0xFF;
            checksum *= FNV_PRIME;
        }

        return checksum & 0x7fffffff;
    }


    private static String flipBytes (final String bytesAsText)
    {
        final StringBuilder flipped = new StringBuilder (bytesAsText.length ());
        for (int i = bytesAsText.length () - 2; i >= 0; i -= 2)
            flipped.append (bytesAsText.substring (i, i + 2));
        return flipped.toString ();
    }


    /**
     * Convert a CLAP device to Reaper.
     *
     * @param clapPlugin The CLAP plugin to convert
     * @param fxChunk The FX chunk where to add the device information
     * @param mediaFiles Access to additional media files
     * @throws IOException Could not create the VST chunks
     */
    private void convertClapDevice (final ClapPlugin clapPlugin, final Chunk fxChunk, final IMediaFiles mediaFiles) throws IOException
    {
        final Chunk clapChunk = addChunk (fxChunk, ReaperTags.CHUNK_CLAP, createDeviceName (clapPlugin), clapPlugin.deviceID, "\"\"");
        try (final InputStream in = mediaFiles.stream (clapPlugin.state.path))
        {
            new ClapChunkHandler ().fileToChunk (in, clapChunk);
        }
        catch (final FileNotFoundException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_DEVICE_STATE_FILE_NOT_FOUND", clapPlugin.state.path);
        }
        catch (final IOException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_COULD_NOT_CREATE_CLAP_CHUNK", clapPlugin.state.path);
        }
    }


    /**
     * Convert a single track.
     *
     * @param mediaFiles All media files, needed for the device states
     * @param flatTracks All flattened
     * @param trackIndex The index of the track to convert
     * @param rootChunk The root chunk to add the information
     * @param parameters The parameters
     * @throws IOException Error converting the track
     */
    private void convertTrack (final IMediaFiles mediaFiles, final List<TrackInfo> flatTracks, final int trackIndex, final Chunk rootChunk, final Parameters parameters) throws IOException
    {
        final TrackInfo trackInfo = flatTracks.get (trackIndex);
        final Chunk trackChunk = addChunk (rootChunk, ReaperTags.CHUNK_TRACK);

        final Track track = trackInfo.track;
        this.createTrack (trackChunk, trackInfo.folder == null ? trackInfo.track : trackInfo.folder, trackInfo.type, trackInfo.direction);

        if (track == null || track.channel == null)
            return;

        parameters.chunkMapping.put (track, trackChunk);
        parameters.trackMapping.put (track, Integer.valueOf (trackIndex));

        final Channel channel = track.channel;

        // Number of channels
        if (channel.audioChannels != null)
            addNode (trackChunk, ReaperTags.TRACK_NUMBER_OF_CHANNELS, channel.audioChannels.toString ());

        // Volume & Panorama
        if (channel.volume != null && channel.pan != null)
            addNode (trackChunk, ReaperTags.TRACK_VOLUME_PAN, this.convertVolume (channel.volume), this.convertPanorama (channel.pan));

        // Mute & Solo
        int state = channel.solo != null && channel.solo.booleanValue () ? 2 : 0;
        if (channel.mute != null && channel.mute.value.booleanValue ())
            state |= 1;
        addNode (trackChunk, ReaperTags.TRACK_MUTE_SOLO, Integer.toString (state));

        // Convert all FX devices
        this.convertDevices (mediaFiles, channel.devices, track.loaded == null || track.loaded.booleanValue (), trackChunk, ReaperTags.CHUNK_FXCHAIN);
    }


    /**
     * Recursively convert grouped clips into top level media items, since Reaper does not support
     * to wrap clips into a parent clip.
     *
     * @param trackChunk The Reaper track chunk
     * @param track The track which contains the items
     * @param clips The clips to convert
     * @param parentClip Some aggregated info about the parent clip(s)
     * @param sourceIsBeats If true the source time base is in beats otherwise seconds
     * @param parameters The parameters
     */
    private void convertItems (final Chunk trackChunk, final Track track, final Clips clips, final ParentClip parentClip, final boolean sourceIsBeats, final Parameters parameters)
    {
        if (clips.clips == null)
            return;

        final boolean isBeats = TimeUtils.updateIsBeats (clips, sourceIsBeats);
        for (final Clip clip: clips.clips)
            this.convertItem (trackChunk, track, clip, parentClip, sourceIsBeats, parameters, isBeats);
    }


    private void convertItem (final Chunk trackChunk, final Track track, final Clip clip, final ParentClip parentClip, final boolean sourceIsBeats, final Parameters parameters, final boolean isBeats)
    {
        double duration = TimeUtils.getDuration (clip);

        // Cannot group clips in clips in Reaper, therefore only create the most inner clips
        if (clip.content instanceof final Clips groupedClips)
        {
            final ParentClip innerParentClip = new ParentClip ();
            innerParentClip.comment = clip.comment;
            innerParentClip.loopStart = clip.loopStart == null ? 0 : clip.loopStart.doubleValue ();
            innerParentClip.loopEnd = clip.loopEnd == null ? -1 : clip.loopEnd.doubleValue ();
            innerParentClip.position = parentClip.position + clip.time;
            innerParentClip.offset = clip.playStart == null ? 0 : clip.playStart.doubleValue ();
            innerParentClip.duration = Math.min (innerParentClip.position + duration, +parentClip.position + parentClip.duration) - innerParentClip.position;
            this.convertItems (trackChunk, track, groupedClips, innerParentClip, isBeats, parameters);
            return;
        }

        // Ignore clips outside of the view of the parent clip
        final double clipTimeEnd = clip.time + duration;
        if (parentClip.duration != -1 && (clipTimeEnd <= parentClip.offset || clip.time >= parentClip.duration))
            return;

        // Check if clip start is left to the parents start, if true limit it
        double start = clip.time;
        double offset = 0;
        if (start < parentClip.offset)
        {
            final double diff = parentClip.offset - start;
            duration -= diff;
            offset = diff;
            start = 0;
        }
        // Limit to maximum duration depending on the surrounding clip
        if (parentClip.duration != -1 && duration > parentClip.duration)
            duration = parentClip.duration;
        start += parentClip.position;

        offset += clip.playStart == null ? 0 : clip.playStart.doubleValue ();
        final Chunk itemChunk = createClipChunk (trackChunk, clip, start, duration, offset, parameters, isBeats);

        if (parentClip.comment != null && !parentClip.comment.isBlank ())
            createNotesChunk (itemChunk, parentClip.comment, ReaperTags.PROJECT_NOTES);

        if (clip.content instanceof final Notes notes)
            convertMIDI (itemChunk, parameters, clip, notes, duration, sourceIsBeats);
        else if (clip.content instanceof final Audio audio)
            this.convertLoopedAudio (trackChunk, clip, audio, null, parentClip, sourceIsBeats, parameters, isBeats, duration, start, itemChunk);
        else if (clip.content instanceof final Warps warps)
            this.convertLoopedAudio (trackChunk, clip, null, warps, parentClip, sourceIsBeats, parameters, isBeats, duration, start, itemChunk);
        else if (clip.content instanceof final Lanes lanes)
        {
            // Cannot create endless nested time lines, handle directly here
            for (final Timeline trackTimeline: lanes.lanes)
            {
                if (trackTimeline instanceof final Notes notes)
                    convertMIDI (itemChunk, parameters, clip, notes, duration, sourceIsBeats);
                else if (trackTimeline instanceof final Audio audio)
                    this.convertLoopedAudio (itemChunk, clip, audio, null, parentClip, sourceIsBeats, parameters, isBeats, duration, start, itemChunk);
                else if (trackTimeline instanceof final Warps warps)
                    this.convertLoopedAudio (itemChunk, clip, null, warps, parentClip, sourceIsBeats, parameters, isBeats, duration, start, itemChunk);
                else
                    this.notifier.logError ("IDS_NOTIFY_UNSUPPORTED_CLIP_TYPE", trackTimeline.getClass ().getName ());
            }
        }
        else
            this.notifier.logError ("IDS_NOTIFY_UNSUPPORTED_CLIP_TYPE", clip.content.getClass ().getName ());
    }


    private void convertLoopedAudio (final Chunk trackChunk, final Clip clip, final Audio audio, final Warps warps, final ParentClip parentClip, final boolean sourceIsBeats, final Parameters parameters, final boolean isBeats, final double clipDuration, final double clipStart, final Chunk firstItemChunk)
    {
        double start = clipStart;
        double duration = clipDuration;
        Chunk itemChunk = firstItemChunk;

        // Convert the looped region into individual clips
        while (true)
        {
            if (audio != null)
                convertAudio (itemChunk, parameters, audio, 1);
            else
                this.convertWarps (itemChunk, parameters, warps, sourceIsBeats);

            if (parentClip.loopEnd < 0)
                return;

            start += duration;
            final double end = parentClip.position + parentClip.duration;
            if (start >= end)
                break;
            duration = parentClip.loopEnd - parentClip.loopStart;
            if (start + duration > end)
                duration = end - start;

            itemChunk = createClipChunk (trackChunk, clip, start, duration, parentClip.loopStart, parameters, isBeats);
        }
    }


    private static Chunk createClipChunk (final Chunk trackChunk, final Clip clip, final double start, final double duration, final double offset, final Parameters parameters, final boolean isBeats)
    {
        final Chunk itemChunk = addChunk (trackChunk, ReaperTags.CHUNK_ITEM);
        if (clip.name != null)
            addNode (itemChunk, ReaperTags.ITEM_NAME, clip.name);
        addNode (itemChunk, ReaperTags.ITEM_POSITION, Double.toString (handleTime (start, isBeats, parameters)));
        addNode (itemChunk, ReaperTags.ITEM_LENGTH, Double.toString (handleTime (duration, isBeats, parameters)));
        addNode (itemChunk, ReaperTags.ITEM_SAMPLE_OFFSET, Double.toString (handleTime (offset, isBeats, parameters)));

        // FADEIN 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
        addNode (itemChunk, ReaperTags.ITEM_FADEIN, "1", handleTime (clip.fadeInTime, isBeats, parameters).toString (), "0");

        // FADEOUT 1 0 0 1 0 0 0 - 2nd parameter is fade-in time in seconds
        addNode (itemChunk, ReaperTags.ITEM_FADEOUT, "1", handleTime (clip.fadeOutTime, isBeats, parameters).toString (), "0");
        return itemChunk;
    }


    /**
     * Converts a warped audio clip. Can only handle simple Warps with one warp marker at the
     * beginning and one at the end of the audio clip.
     *
     * @param itemChunk The item chunk
     * @param parameters The parameters to add to the node
     * @param warps The warps information with an audio file and several warp events
     * @param sourceIsBeats True if the time is in beats otherwise in seconds
     */
    private void convertWarps (final Chunk itemChunk, final Parameters parameters, final Warps warps, final boolean sourceIsBeats)
    {
        final boolean contentTimeIsBeats = TimeUtils.updateWarpsTimeIsBeats (warps, sourceIsBeats);
        final double playrate = this.calcPlayrate (warps.events, sourceIsBeats, contentTimeIsBeats, parameters);
        if (warps.content instanceof final Audio audio)
            convertAudio (itemChunk, parameters, audio, playrate);
    }


    /**
     * Calculate the play rate of the media item in Reaper from the a list of warp events. Since
     * there is only one play rate the logic takes only the first 2 warp events into account.
     *
     * @param events The warp events
     * @param sourceIsBeats True if the time is in beats otherwise in seconds
     * @param contentTimeIsBeats True if the time of the warped content is in beats otherwise in
     *            seconds
     * @param parameters The parameters to add to the node
     * @return The play rate (1 = normal playback, < 1 slower, > 1 faster)
     */
    private double calcPlayrate (final List<Warp> events, final boolean sourceIsBeats, final boolean contentTimeIsBeats, final Parameters parameters)
    {
        // Can only map a simple warp to one play rate
        if (events.size () != 2)
        {
            this.notifier.logError ("IDS_NOTIFY_CAN_ONLY_MAP_SIMPLE_WARP");
            return 1;
        }

        // First warp must be at the beginning
        final Warp warp = events.get (0);
        if (warp.time != 0 || warp.contentTime != 0)
        {
            this.notifier.logError ("IDS_NOTIFY_CAN_ONLY_MAP_SIMPLE_WARP");
            return 1;
        }

        // Calculate play rate from the 2nd, if there are more warps the result will also not be
        // correct
        final Warp warp2 = events.get (1);
        final double duration = sourceIsBeats ? Conversions.toTempoTime (warp2.time, parameters.tempo) : warp2.time;
        final double contentTime = contentTimeIsBeats ? Conversions.toTempoTime (warp2.contentTime, parameters.tempo) : warp2.contentTime;
        return contentTime / duration;
    }


    /**
     * Convert an audio clip.
     *
     * @param itemChunk The item chunk
     * @param parameters The parameters to add to the node
     * @param audio The audio file
     * @param playRate The play rate of the media item
     */
    private static void convertAudio (final Chunk itemChunk, final Parameters parameters, final Audio audio, final double playRate)
    {
        if (audio.file == null)
            return;

        final File sourceFile = new File (audio.file.path);
        parameters.audioFiles.add (audio.file.path);

        // TODO support pitch
        // TODO support audio.algorithm

        // field 1, float, play rate
        // field 2, integer (boolean), preserve pitch while changing rate
        // field 3, float, pitch adjust, in semitones.cents
        // field 4, integer, pitch shifting/time stretch mode and preset: -1 is project default
        addNode (itemChunk, ReaperTags.ITEM_PLAYRATE, String.format ("%.6f", Double.valueOf (playRate)), "1", "0.000", "-1");

        final Chunk sourceChunk = addChunk (itemChunk, ReaperTags.CHUNK_ITEM_SOURCE, "WAVE");
        addNode (sourceChunk, ReaperTags.SOURCE_FILE, sourceFile.getName ());
    }


    /**
     * Convert a MIDI clip.
     *
     * @param itemChunk The chunk of the media item to fill
     * @param parameters The parameters to add to the node
     * @param clip The clip to convert
     * @param notes The notes of the clip
     * @param clipDuration The duration of the clip
     * @param sourceIsBeats True if the time is in beats otherwise in seconds
     */
    private static void convertMIDI (final Chunk itemChunk, final Parameters parameters, final Clip clip, final Notes notes, final double clipDuration, final boolean sourceIsBeats)
    {
        final Chunk sourceChunk = addChunk (itemChunk, ReaperTags.CHUNK_ITEM_SOURCE, "MIDI");

        // Reaper can only loop the whole clip therefore notes are 'printed' below
        addNode (itemChunk, ReaperTags.ITEM_LOOP, "0");

        addNode (sourceChunk, ReaperTags.SOURCE_HASDATA, "1", "960", "QN");

        final List<ReaperMidiEvent> events = new ArrayList<> ();

        final double loopStart = clip.loopStart == null ? 0 : clip.loopStart.doubleValue ();
        final double loopEnd;
        if (clip.loopEnd == null)
            loopEnd = sourceIsBeats ? clipDuration : Conversions.toTempoBeats (clipDuration, parameters.tempo);
        else
            loopEnd = sourceIsBeats ? clip.loopEnd.doubleValue () : Conversions.toTempoBeats (clip.loopEnd.doubleValue (), parameters.tempo);
        final double loopDuration = loopEnd - loopStart;

        // Create all note on and off events for notes before the start of the loop, collect notes
        // in the loop
        final List<Note> notesInLoop = new ArrayList<> ();
        for (final Note note: notes.notes)
        {
            // Time is always in beats for MIDI events!
            final double noteStart = note.time.doubleValue ();
            if (noteStart < loopStart)
                createNoteEvent (events, note, noteStart, note.duration.doubleValue (), parameters.tempo, sourceIsBeats);
            else if (noteStart < loopEnd)
                notesInLoop.add (note);
        }

        // Fill in notes inside of the loop until the end of the clip
        double offset = 0;
        while (offset < clipDuration)
        {
            final double offsetLoopEnd = offset + loopDuration;
            for (final Note note: notesInLoop)
            {
                final double noteStart = offset + note.time.doubleValue ();
                if (noteStart < clipDuration)
                {
                    double noteDuration = note.duration.doubleValue ();
                    // Clip notes at the end of the loop
                    if (noteStart + noteDuration > offsetLoopEnd)
                        noteDuration = offsetLoopEnd - noteStart;
                    // Clip notes at the end of the clip
                    if (noteStart + noteDuration > clipDuration)
                        noteDuration = clipDuration - noteStart;
                    createNoteEvent (events, note, noteStart, noteDuration, parameters.tempo, sourceIsBeats);
                }
            }
            offset = offsetLoopEnd;
        }

        // Sort the events by their time position
        Collections.sort (events, (o1, o2) -> (int) (o1.getPosition () - o2.getPosition ()));

        // Finally, calculate the offsets between the events from their position
        long lastPosition = 0;
        for (final ReaperMidiEvent event: events)
        {
            final long currentPosition = event.getPosition ();
            event.setOffset (currentPosition - lastPosition);
            lastPosition = currentPosition;
            sourceChunk.addChildNode (event.toNode ());
        }

        // Add padding from the last note till the end of the clip with: "E 480 b0 7b 00"
        final long padding = (long) ((clipDuration - lastPosition) * TICKS_PER_QUARTER_NOTE);
        final ReaperMidiEvent event = new ReaperMidiEvent (padding, 0, 0xB0, 0x7B, 0);
        sourceChunk.addChildNode (event.toNode ());

        // TODO add all MIDI envelope parameters
    }


    /**
     * Convert all lanes. Assigns the Markers data to different Reaper settings.
     *
     * @param rootChunk The root chunk to add the information
     * @param project The project to read from
     * @param masterTrack The master track
     * @param parameters The parameters
     * @param sourceIsBeats If true the source time base is in beats otherwise seconds
     */
    private void convertArrangementLanes (final Chunk rootChunk, final Project project, final Track masterTrack, final Parameters parameters, final boolean sourceIsBeats)
    {
        if (project.arrangement == null || project.arrangement.lanes == null)
            return;

        for (final Timeline timeline: project.arrangement.lanes.lanes)
        {
            if (timeline instanceof final Markers markers && markers.markers != null)
                this.convertMarkers (rootChunk, parameters, markers, sourceIsBeats);
            else if (timeline instanceof final Lanes lanes && lanes.track != null)
                this.convertLanes (rootChunk, parameters, null, lanes, project, masterTrack, sourceIsBeats);
            else
                this.notifier.logError ("IDS_NOTIFY_UNSUPPORTED_TYPE_IN_LANE", timeline.getClass ().getName ());
        }

        createTempoSignatureEnvelope (sourceIsBeats, rootChunk, parameters);
    }


    /**
     * Converts all scenes.
     *
     * @param rootChunk The root chunk to add the information
     * @param project The project to read from
     * @param masterTrack The master track
     * @param parameters The parameters
     * @param sourceIsBeats If true the source time base is in beats otherwise seconds
     */
    private void convertScenes (final Chunk rootChunk, final Project project, final Track masterTrack, final Parameters parameters, final boolean sourceIsBeats)
    {
        if (project.scenes == null)
        {
            this.notifier.log ("IDS_NOTIFY_NO_SCENES");
            return;
        }

        // Position all scenes after each other in the arranger
        double sceneOffset = 0;
        int sceneNum = 1;
        for (final Scene scene: project.scenes)
        {
            if (scene.content instanceof final Lanes lanes)
            {
                final List<ClipSlot> clipSlots = getClipSlots (lanes.lanes, masterTrack, parameters);
                final double maxDuration = TimeUtils.getMaxDuration (clipSlots);
                for (final ClipSlot clipSlot: clipSlots)
                {
                    // Null checks of track and clip were already done in getClipSlots
                    final Track track = clipSlot.track;
                    final Clip clip = clipSlot.clip;
                    final Chunk trackChunk = parameters.chunkMapping.get (track);
                    final boolean isBeats = TimeUtils.updateIsBeats (clipSlot, sourceIsBeats);

                    // 'Roll-out' the clip to the length of the longest clip in the scene
                    final ParentClip parentClip = new ParentClip ();
                    parentClip.position += sceneOffset;
                    parentClip.duration = maxDuration;
                    final double duration = TimeUtils.getDuration (clip);
                    for (double pos = 0; pos < maxDuration; pos += duration)
                    {
                        this.convertItem (trackChunk, track, clip, parentClip, sourceIsBeats, parameters, isBeats);
                        clip.time += duration;
                    }
                }

                if (maxDuration > 0)
                {
                    // Create a range marker for the scene with the name of the scene
                    final boolean isBeats = TimeUtils.updateIsBeats (scene.content, sourceIsBeats);
                    final int color = 0;
                    double pos = handleTime (sceneOffset, isBeats, parameters);
                    addNode (rootChunk, ReaperTags.PROJECT_MARKER, Integer.toString (sceneNum), Double.toString (pos), scene.name, "1", Integer.toString (color));
                    sceneOffset += maxDuration;
                    pos = handleTime (sceneOffset, isBeats, parameters);
                    addNode (rootChunk, ReaperTags.PROJECT_MARKER, Integer.toString (sceneNum), Double.toString (pos), "", "1");
                }
            }

            sceneNum++;
        }
    }


    private static List<ClipSlot> getClipSlots (final List<Timeline> timelines, final Track masterTrack, final Parameters parameters)
    {
        final List<ClipSlot> clipSlots = new ArrayList<> ();
        for (final Timeline timeline: timelines)
        {
            if (timeline instanceof final ClipSlot clipSlot && clipSlot.clip != null && clipSlot.track != null && clipSlot.track != masterTrack)
            {
                // Note: this returns null if the track is a folder track which cannot contain clips
                // in Reaper
                final Chunk trackChunk = parameters.chunkMapping.get (clipSlot.track);
                if (trackChunk != null)
                    clipSlots.add (clipSlot);
            }
        }
        return clipSlots;
    }


    private void convertLanes (final Chunk rootOrItemChunk, final Parameters parameters, final Track ownerTrack, final Lanes lanes, final Project project, final Track masterTrack, final boolean sourceIsBeats)
    {
        final Track track = lanes.track == null ? ownerTrack : lanes.track;
        final Chunk trackChunk = track == masterTrack ? rootOrItemChunk : parameters.chunkMapping.get (track);
        // Note: it is null for folder tracks which cannot contain clips in Reaper
        if (trackChunk == null)
            return;

        for (final Timeline trackTimeline: lanes.lanes)
        {
            final boolean isBeats = TimeUtils.updateIsBeats (trackTimeline, sourceIsBeats);

            // TODO these envelopes might be MIDI envelopes and need to be integrated
            // into the MIDI clips. To complicate it, the envelopes are after the clips
            // section, so we need to parse that first...
            // clips/clip/notes
            // points/target
            if (trackTimeline instanceof final Points trackEnvelope)
                handleEnvelopeParameter (project, masterTrack, track, trackChunk, trackEnvelope, isBeats, parameters);
            else if (trackTimeline instanceof final Clips clips)
                this.convertItems (trackChunk, track, clips, new ParentClip (), isBeats, parameters);
            else if (trackTimeline instanceof final Warps warps)
                this.convertWarps (rootOrItemChunk, parameters, warps, sourceIsBeats);
            else
                this.notifier.logError ("IDS_NOTIFY_UNSUPPORTED_TYPE_IN_LANE", trackTimeline.getClass ().getName ());
        }
    }


    private void convertMarkers (final Chunk rootChunk, final Parameters parameters, final Markers markers, final boolean sourceIsBeats)
    {
        final boolean isBeats = TimeUtils.updateIsBeats (markers, sourceIsBeats);
        for (int i = 0; i < markers.markers.size (); i++)
        {
            final Marker marker = markers.markers.get (i);
            final double position = handleTime (marker.time, isBeats, parameters);
            final int color = marker.color == null ? 0 : this.fromHexColor (marker.color);

            // marker.comment - Marker comment not in Reaper

            addNode (rootChunk, ReaperTags.PROJECT_MARKER, Integer.toString (i), Double.toString (position), marker.name, "0", Integer.toString (color));
        }
    }


    /**
     * Combines the tempo and signature automation into the Reaper tempo envelope.
     *
     * @param sourceIsBeats If true the source time base is in beats otherwise seconds
     * @param rootChunk The root chunk to add the information
     * @param parameters The parameters to add to the node
     */
    private static void createTempoSignatureEnvelope (final boolean sourceIsBeats, final Chunk rootChunk, final Parameters parameters)
    {
        // Combine tempo and signature automation envelopes
        final Map<Double, List<Point>> combined = new TreeMap<> ();
        if (parameters.tempoEnvelope != null)
        {
            final boolean isBeats = TimeUtils.updateIsBeats (parameters.tempoEnvelope, sourceIsBeats);
            for (final Point point: parameters.tempoEnvelope.points)
                combined.put (handleTime (point.time, isBeats, parameters), new ArrayList<> (Collections.singleton (point)));
        }
        if (parameters.signatureEnvelope != null)
        {
            final boolean isBeats = TimeUtils.updateIsBeats (parameters.signatureEnvelope, sourceIsBeats);
            for (final Point point: parameters.signatureEnvelope.points)
                combined.computeIfAbsent (handleTime (point.time, isBeats, parameters), key -> new ArrayList<> (Collections.singleton (new RealPoint ()))).add (point);
        }
        if (combined.isEmpty ())
            return;

        final Chunk tempoChunk = addChunk (rootChunk, ReaperTags.PROJECT_TEMPO_ENVELOPE);

        Double tempoValue = parameters.tempo;
        int numeratorValue = parameters.numerator;
        int denominatorValue = parameters.denominator;

        for (final Entry<Double, List<Point>> e: combined.entrySet ())
        {
            final Double time = e.getKey ();
            final Double position = handleTime (time, sourceIsBeats, parameters);
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
            addNode (tempoChunk, ReaperTags.ENVELOPE_POINT, position.toString (), tempoValue.toString (), "0", signatureValue);
        }
    }


    private static void handleEnvelopeParameter (final Project project, final Track masterTrack, final Track track, final Chunk trackChunk, final Points envelope, final boolean sourceIsBeats, final Parameters parameters)
    {
        if (track.channel == null)
            return;
        final Channel channel = track.channel;

        // Track Volume Parameter
        if (channel.volume == envelope.target.parameter)
        {
            final String envelopeName = track == masterTrack ? ReaperTags.MASTER_VOLUME_ENVELOPE : ReaperTags.TRACK_VOLUME_ENVELOPE;
            createEnvelope (trackChunk, envelopeName, envelope, true, sourceIsBeats, parameters);
            return;
        }

        // Track Panorama Parameter
        if (channel.pan == envelope.target.parameter)
        {
            final String envelopeName = track == masterTrack ? ReaperTags.MASTER_PANORAMA_ENVELOPE : ReaperTags.TRACK_PANORAMA_ENVELOPE;
            createEnvelope (trackChunk, envelopeName, envelope, true, sourceIsBeats, parameters);
            return;
        }

        // Track Mute Parameter
        if (channel.mute == envelope.target.parameter)
        {
            createEnvelope (trackChunk, ReaperTags.TRACK_MUTE_ENVELOPE, envelope, false, sourceIsBeats, parameters);
            return;
        }

        // Track Sends Parameter
        if (channel.sends != null)
        {
            for (final Send send: channel.sends)
            {
                if (send.volume == envelope.target.parameter)
                {
                    final Track destinationTrack = parameters.channelMapping.get (send.destination);
                    if (destinationTrack != null)
                    {
                        final Chunk auxChunk = parameters.chunkMapping.get (destinationTrack);
                        if (auxChunk != null)
                            createEnvelope (auxChunk, ReaperTags.TRACK_AUX_ENVELOPE, envelope, true, sourceIsBeats, parameters);
                    }
                    return;
                }
                // TODO add panorama envelope
            }
        }

        // Transport Tempo and Signature Parameter needs to be integrated if both are found
        if (project == null)
            return;
        final Transport transport = project.transport;
        if (transport == null)
            return;
        if (transport.tempo == envelope.target.parameter)
            parameters.tempoEnvelope = envelope;
        else if (transport.timeSignature == envelope.target.parameter)
            parameters.signatureEnvelope = envelope;
    }


    private static void createEnvelope (final Chunk trackChunk, final String envelopeName, final Points trackEnvelope, final boolean interpolate, final boolean sourceIsBeats, final Parameters parameters)
    {
        final Chunk envelopeChunk = addChunk (trackChunk, envelopeName);

        final boolean isBeats = TimeUtils.updateIsBeats (trackEnvelope, sourceIsBeats);

        for (final Point point: trackEnvelope.points)
        {
            if (point.time == null)
                continue;

            final Double position = handleTime (point.time, isBeats, parameters);

            String value = null;
            // Note: The unit of the value must already been matched at the parameter itself
            if (point instanceof final RealPoint realPoint && realPoint.value != null)
                value = realPoint.value.toString ();
            else if (point instanceof final IntegerPoint integerPoint && integerPoint.value != null)
                value = integerPoint.value.toString ();
            else if (point instanceof final BoolPoint boolPoint && boolPoint.value != null)
                value = boolPoint.value.booleanValue () ? "1" : "0";

            if (value != null)
                addNode (envelopeChunk, ReaperTags.ENVELOPE_POINT, position.toString (), value, interpolate ? "0" : "1");
        }
    }


    /**
     * Set the basic track information, like structure, name and color.
     *
     * @param trackChunk The track chunk to add the information
     * @param track The DAWproject object
     * @param type The folder type
     * @param direction The level direction
     */
    private void createTrack (final Chunk trackChunk, final Track track, final int type, final int direction)
    {
        addNode (trackChunk, ReaperTags.TRACK_NAME, track.name);
        if (track.loaded != null && !track.loaded.booleanValue ())
            addNode (trackChunk, ReaperTags.TRACK_LOCK, "1");
        addNode (trackChunk, ReaperTags.TRACK_STRUCTURE, Integer.toString (type), Integer.toString (direction));
        if (track.color != null)
            addNode (trackChunk, ReaperTags.TRACK_COLOR, Integer.toString (this.fromHexColor (track.color)));
    }


    /**
     * Convert the track hierarchy into a flat list in which two parameters indicate the track state
     * (1 = start track, 2 = end of track) and a direction the number of levels to move into our out
     * of folders (1 = move into a folder, -X = X number of levels to move up).
     *
     * @param tracks The current list of sub-folders and -tracks.
     * @param flatTracks The list with all flat tracks so far
     * @param isTop True if this is the top level
     * @param parameters The parameters
     */
    private void createTrackStructure (final List<Track> tracks, final List<TrackInfo> flatTracks, final boolean isTop, final Parameters parameters)
    {
        for (final Track track: tracks)
        {
            final TrackInfo trackInfo = new TrackInfo ();
            flatTracks.add (trackInfo);

            if (track.channel != null)
                parameters.channelMapping.put (track.channel, track);

            // If it is not a folder/group, it is a 'plain' track
            if (!hasContent (ContentType.tracks, track.contentType))
            {
                trackInfo.track = track;
                continue;
            }

            // Handle all child tracks of the group track
            trackInfo.folder = track;
            trackInfo.type = 2;

            final List<Track> childTracks = track.tracks;
            if (childTracks == null)
                continue;

            // Find the track among the child tracks which acts as the mix master for
            // the folder, this will be combined with the parent folder
            final List<Track> children = new ArrayList<> (childTracks);
            for (final Track child: childTracks)
            {
                if (child.channel != null && child.channel.role == MixerRole.master)
                {
                    trackInfo.track = child;
                    children.remove (child);
                    break;
                }
            }

            if (!children.isEmpty ())
            {
                trackInfo.type = 1;
                trackInfo.direction = 1;
            }

            this.createTrackStructure (children, flatTracks, false, parameters);
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
     * Creates the Reaper device name for the VST chunk.
     *
     * @param device The device for which to create a name
     * @return The name
     */
    private static String createDeviceName (final Device device)
    {
        final StringBuilder name = new StringBuilder ();

        if (device instanceof final Plugin plugin)
        {
            name.append (PLUGIN_TYPES.get (plugin.getClass ()));
            if (plugin.deviceRole == DeviceRole.instrument)
                name.append ("i");
        }

        name.append (": ").append (device.deviceName);
        if (device.deviceVendor != null)
            name.append (" (").append (device.deviceVendor).append (")");
        return name.toString ();
    }


    private static void createNoteEvent (final List<ReaperMidiEvent> events, final Note note, final double noteStart, final double noteDuration, final Double tempo, final boolean sourceIsBeats)
    {
        final long startPosition = (long) ((sourceIsBeats ? noteStart : Conversions.toTempoBeats (noteStart, tempo)) * TICKS_PER_QUARTER_NOTE);
        final long endPosition = startPosition + (long) ((sourceIsBeats ? noteDuration : Conversions.toTempoBeats (noteDuration, tempo)) * TICKS_PER_QUARTER_NOTE);
        final int velocity = note.velocity == null ? 0 : (int) (note.velocity.doubleValue () * 127.0);
        final int releaseVelocity = note.releaseVelocity == null ? 0 : (int) (note.releaseVelocity.doubleValue () * 127.0);
        events.add (new ReaperMidiEvent (startPosition, note.channel, 0x90, note.key, velocity));
        events.add (new ReaperMidiEvent (endPosition, note.channel, 0x80, note.key, releaseVelocity));
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
    private int fromHexColor (final String hexColor)
    {
        String fixedHexColor = hexColor;

        try
        {
            if (hexColor.startsWith ("#"))
                fixedHexColor = hexColor.substring (1);
            if (!hexColor.startsWith ("0x"))
                fixedHexColor = "0x" + fixedHexColor;
            if (fixedHexColor.length () > 8)
                fixedHexColor = fixedHexColor.substring (0, 8);

            final int c = Integer.decode (fixedHexColor).intValue ();
            final int r = c & 0xFF;
            final int g = c >> 8 & 0xFF;
            final int b = c >> 16 & 0xFF;
            return (r << 16) + (g << 8) + b + 0x01000000;
        }
        catch (final NumberFormatException | NullPointerException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_INVALID_COLOR", hexColor == null ? "null" : hexColor);
            return 0xCCCCCC;
        }
    }


    private static String intToText (final int number)
    {
        final StringBuilder sb = new StringBuilder (4);
        for (int i = 3; i >= 0; i--)
            sb.append ((char) (number >> 8 * i & 0xFF));
        return sb.toString ();
    }


    private static Double handleTime (final Double time, final boolean sourceIsBeats, final Parameters parameters)
    {
        return Double.valueOf (time == null ? 0 : handleTime (time.doubleValue (), sourceIsBeats, parameters));
    }


    private static double handleTime (final double time, final boolean sourceIsBeats, final Parameters parameters)
    {
        if (sourceIsBeats == parameters.destinationIsBeats)
            return time;
        return sourceIsBeats ? Conversions.toTempoTime (time, parameters.tempo) : Conversions.toTempoBeats (time, parameters.tempo);
    }


    private static boolean hasContent (final ContentType type, final ContentType [] types)
    {
        for (final ContentType t: types)
        {
            if (t == type)
                return true;
        }
        return false;
    }


    private static List<Track> getTracks (final List<Lane> lanes)
    {
        final List<Track> tracks = new ArrayList<> ();
        for (final Lane lane: lanes)
        {
            if (lane instanceof final Track track)
                tracks.add (track);
        }
        return tracks;
    }


    private String convertVolume (final RealParameter volume)
    {
        if (volume == null)
            return "0";
        final double value = this.getDoubleValue (volume, Unit.linear);
        return Double.toString (Conversions.dBToValue (value, 0));
    }


    private String convertPanorama (final RealParameter pan)
    {
        if (pan == null)
            return "0";
        return Double.toString (this.getDoubleValue (pan, Unit.normalized) * 2.0 - 1);
    }


    private String getValue (final RealParameter parameter, final Unit supportedUnit)
    {
        if (parameter.unit != supportedUnit)
            this.notifier.logError ("IDS_NOTIFY_UNIT_NOT_SUPPORTED", parameter.name, parameter.unit.toString ());
        return parameter.value.toString ();
    }


    private double getDoubleValue (final RealParameter parameter, final Unit supportedUnit)
    {
        if (parameter.unit != supportedUnit)
            this.notifier.logError ("IDS_NOTIFY_UNIT_NOT_SUPPORTED", parameter.name, parameter.unit.toString ());
        return parameter.value == null ? 0 : parameter.value.doubleValue ();
    }


    private static class Parameters
    {
        private Points                    tempoEnvelope;
        private Points                    signatureEnvelope;
        private Double                    tempo;
        private int                       numerator;
        private int                       denominator;
        private final Set<String>         audioFiles     = new HashSet<> ();
        private boolean                   destinationIsBeats;

        private final Map<Track, Chunk>   chunkMapping   = new HashMap<> ();
        private final Map<Channel, Track> channelMapping = new HashMap<> ();
        private final Map<Track, Integer> trackMapping   = new HashMap<> ();
    }


    private static class ParentClip
    {
        String comment;
        // The start of the loop
        double loopEnd;
        // The end of the loop
        double loopStart;
        // The time at which the parent clip starts
        double position = 0;
        // The duration of the parent clip
        double duration = -1;
        // An offset to the play start in the clip
        double offset   = 0;
    }
}
