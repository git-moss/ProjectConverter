// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.dawproject;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IDestinationFormat;
import de.mossgrabers.projectconverter.core.IMediaFiles;
import de.mossgrabers.projectconverter.core.TimeUtils;
import de.mossgrabers.tools.ui.BasicConfig;
import de.mossgrabers.tools.ui.panel.BoxPanel;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.Scene;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.ClipSlot;
import com.bitwig.dawproject.timeline.Clips;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Marker;
import com.bitwig.dawproject.timeline.Markers;
import com.bitwig.dawproject.timeline.Timeline;

import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


/**
 * The dawproject project destination.
 *
 * @author Jürgen Moßgraber
 */
public class DawProjectDestinationFormat extends AbstractCoreTask implements IDestinationFormat
{
    private static final String PROJECT_FILE        = "project.xml";
    private static final String METADATA_FILE       = "metadata.xml";
    private static final String REPLACE_ARRANGEMENT = "REPLACE_ARRANGEMENT";

    private CheckBox            replaceArrangement;


    /**
     * Constructor.
     *
     * @param notifier The notifier for error messages
     */
    public DawProjectDestinationFormat (final INotifier notifier)
    {
        super ("DawProject", notifier);
    }


    /** {@inheritDoc} */
    @Override
    public javafx.scene.Node getEditPane ()
    {
        final BoxPanel panel = new BoxPanel (Orientation.VERTICAL);
        this.replaceArrangement = panel.createCheckBox ("@IDS_REPLACE_ARRANGEMENT");
        return panel.getPane ();
    }


    /** {@inheritDoc} */
    @Override
    public void loadSettings (final BasicConfig config)
    {
        this.replaceArrangement.setSelected (config.getBoolean (REPLACE_ARRANGEMENT));
    }


    /** {@inheritDoc} */
    @Override
    public void saveSettings (final BasicConfig config)
    {
        config.setBoolean (REPLACE_ARRANGEMENT, this.replaceArrangement.isSelected ());
    }


    /** {@inheritDoc} */
    @Override
    public boolean needsOverwrite (final String projectName, final File outputPath)
    {
        return getFile (projectName, outputPath).exists ();
    }


    /** {@inheritDoc} */
    @Override
    public void write (final DawProjectContainer dawProject, final File outputPath) throws IOException
    {
        if (this.replaceArrangement.isSelected ())
            this.replaceArrangementClips (dawProject.getProject ());

        final File outputFile = getFile (dawProject.getName (), outputPath);
        final IMediaFiles mediaFiles = dawProject.getMediaFiles ();

        final String metadataXML = toXML (dawProject.getMetadata ());
        final String projectXML = toXML (dawProject.getProject ());

        if (this.notifier.isCancelled ())
            return;

        final ZipOutputStream zos = new ZipOutputStream (new FileOutputStream (outputFile));

        addToZip (zos, METADATA_FILE, metadataXML.getBytes (StandardCharsets.UTF_8));
        addToZip (zos, PROJECT_FILE, projectXML.getBytes (StandardCharsets.UTF_8));

        for (final String id: mediaFiles.getAll ())
        {
            if (this.notifier.isCancelled ())
                return;

            this.notifier.log (id.startsWith ("plugins/") ? "IDS_NOTIFY_COMPRESSING_PRESET_FILE" : "IDS_NOTIFY_COMPRESSING_AUDIO_FILE", id);
            addToZip (zos, id, mediaFiles);
        }

        zos.close ();
    }


    /**
     * Create an arrangement from the Clip Launcher Data. This puts all clips from the Scenes in the
     * DAWproject's Arranger. Each scene section has the length of the longest clip in the scene.
     * Additionally, range markers are created for each scene using the name of the scene.
     *
     * @param project The DAWproject to modify
     */
    private void replaceArrangementClips (final Project project)
    {
        if (project.scenes == null)
        {
            this.notifier.log ("IDS_NOTIFY_NO_SCENES");
            return;
        }

        // Create and setup new arrangement
        final boolean sourceIsBeats = TimeUtils.getArrangementTimeUnit (project.arrangement);
        final Arrangement arrangement = new Arrangement ();
        project.arrangement = arrangement;
        final Lanes arrangerLanes = new Lanes ();
        arrangement.lanes = arrangerLanes;
        TimeUtils.setTimeUnit (arrangerLanes, sourceIsBeats);
        final Markers markers = new Markers ();
        arrangement.markers = markers;

        // Collect clips from the scenes and assign them to a track
        final Map<Track, Clips> trackClipsMap = new HashMap<> ();
        int sceneCounter = 1;
        double sceneOffset = 0;
        for (final Scene scene: project.scenes)
        {
            if (scene.content instanceof final Lanes sceneLanes)
            {
                final List<ClipSlot> clipSlots = getClipSlots (sceneLanes.lanes);
                final double maxDuration = TimeUtils.getMaxDuration (clipSlots);
                for (final ClipSlot clipSlot: clipSlots)
                {
                    // Null checks of track and clip were already done in getClipSlots
                    final Track track = clipSlot.track;
                    final Clip clip = clipSlot.clip;

                    // Create a new arranger lanes/clips object for the track if none exists yet
                    final Clips trackClips = trackClipsMap.computeIfAbsent (track, t -> {

                        final Lanes trackLanes = new Lanes ();
                        arrangerLanes.lanes.add (trackLanes);
                        trackLanes.track = t;
                        final Clips clips = new Clips ();
                        trackLanes.lanes.add (clips);
                        return clips;

                    });

                    // Note: no time unit check here, let's hope no DAW uses different time units
                    // for arranger and scenes...
                    clip.time += sceneOffset;
                    clip.duration = Double.valueOf (maxDuration);
                    trackClips.clips.add (clip);
                }

                // Create a marker for the scene
                final Marker marker = new Marker ();
                marker.time = sceneOffset;
                marker.name = scene.name == null ? "Scene" + sceneCounter : scene.name;
                markers.markers.add (marker);

                sceneOffset += maxDuration;
            }

            sceneCounter++;
        }
    }


    /**
     * Get all timeline objects which are clip slots.
     *
     * @param timelineObjects The timeline objects to filter
     * @return The clip slots
     */
    private static List<ClipSlot> getClipSlots (final List<Timeline> timelineObjects)
    {
        final List<ClipSlot> clipSlots = new ArrayList<> ();
        for (final Timeline timeline: timelineObjects)
        {
            if (timeline instanceof final ClipSlot clipSlot && clipSlot.track != null && clipSlot.clip != null)
                clipSlots.add (clipSlot);
        }
        return clipSlots;
    }


    private static File getFile (final String projectName, final File outputPath)
    {
        return new File (outputPath, projectName + ".dawproject");
    }


    private static void addToZip (final ZipOutputStream zos, final String path, final byte [] data) throws IOException
    {
        final ZipEntry entry = new ZipEntry (path);
        zos.putNextEntry (entry);
        zos.write (data);
        zos.closeEntry ();
    }


    private static void addToZip (final ZipOutputStream zos, final String path, final IMediaFiles mediaFiles) throws IOException
    {
        final ZipEntry entry = new ZipEntry (path);
        zos.putNextEntry (entry);
        try (final InputStream fileInputStream = mediaFiles.stream (path))
        {
            final byte [] data = new byte [65536];
            int size = 0;
            while ((size = fileInputStream.read (data)) != -1)
                zos.write (data, 0, size);
            zos.flush ();
        }
        zos.closeEntry ();
    }


    private static JAXBContext createContext (final Class<? extends Object> cls) throws JAXBException
    {
        return JAXBContext.newInstance (cls);
    }


    private static String toXML (final Object object) throws IOException
    {
        try
        {
            final JAXBContext context = createContext (object.getClass ());
            final Marshaller marshaller = context.createMarshaller ();
            marshaller.setProperty (Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

            final var sw = new StringWriter ();
            marshaller.marshal (object, sw);

            return sw.toString ();
        }
        catch (final Exception ex)
        {
            throw new IOException (ex);
        }
    }
}
