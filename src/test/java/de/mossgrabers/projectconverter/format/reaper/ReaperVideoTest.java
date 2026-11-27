package de.mossgrabers.projectconverter.format.reaper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.timeline.Audio;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.TimeUnit;
import com.bitwig.dawproject.timeline.Video;
import com.bitwig.dawproject.timeline.Warp;
import com.bitwig.dawproject.timeline.Warps;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IMediaFiles;
import de.mossgrabers.projectconverter.format.dawproject.DawProjectMediaFiles;
import de.mossgrabers.projectconverter.format.reaper.model.Chunk;
import de.mossgrabers.projectconverter.format.reaper.model.ReaperProject;

class ReaperVideoTest
{
    private final TestNotifier notifier = new TestNotifier ();


    @Test
    void copiesEmbeddedMp4AndWritesVideoSource (@TempDir final Path dir) throws Exception
    {
        final byte [] data = "embedded mp4 data".getBytes (StandardCharsets.UTF_8);
        final Path archive = dir.resolve ("source.dawproject");
        createArchive (archive, "samples/My Movie.MP4", data);

        final Audio audio = new Audio ();
        audio.file.path = "samples/My Movie.MP4";
        try (final DawProjectMediaFiles media = new DawProjectMediaFiles (archive.toFile ()))
        {
            final Result result = convertAndSave (dir, media, audio);
            assertEquals ("VIDEO", result.source.getParameters ().get (0));
            assertEquals ("My Movie.MP4", result.source.getChildNode ("FILE").orElseThrow ().getParameters ().get (0));
            assertArrayEquals (data, Files.readAllBytes (result.output.resolve ("My Movie.MP4")));
        }
        assertTrue (this.notifier.errors.isEmpty (), this.notifier.errors.toString ());
    }


    @Test
    void copiesAbsoluteExternalVideoAndPreservesWarpRate (@TempDir final Path dir) throws Exception
    {
        final byte [] data = "external video data".getBytes (StandardCharsets.UTF_8);
        final Path videoFile = dir.resolve ("external movie.mov");
        Files.write (videoFile, data);
        final Path archive = dir.resolve ("source.dawproject");
        createArchive (archive, "placeholder", new byte [0]);

        final Video video = new Video ();
        video.file.path = videoFile.toString ();
        video.file.external = Boolean.TRUE;
        final Warps warps = new Warps ();
        warps.content = video;
        warps.timeUnit = TimeUnit.BEATS;
        warps.contentTimeUnit = TimeUnit.SECONDS;
        final Warp first = new Warp ();
        final Warp second = new Warp ();
        second.time = 8;
        second.contentTime = 2;
        warps.events.add (first);
        warps.events.add (second);

        final Lanes lanes = new Lanes ();
        lanes.lanes.add (warps);
        try (final DawProjectMediaFiles media = new DawProjectMediaFiles (archive.toFile ()))
        {
            final Result result = convertAndSave (dir, media, lanes);
            assertEquals ("VIDEO", result.source.getParameters ().get (0));
            assertEquals ("external movie.mov", result.source.getChildNode ("FILE").orElseThrow ().getParameters ().get (0));
            assertEquals ("0.500000", result.item.getChildNode ("PLAYRATE").orElseThrow ().getParameters ().get (0));
            assertArrayEquals (data, Files.readAllBytes (result.output.resolve ("external movie.mov")));
        }
        assertTrue (this.notifier.errors.isEmpty (), this.notifier.errors.toString ());
    }


    @Test
    void convertsDirectVideoClip () throws Exception
    {
        final Video video = new Video ();
        video.file.path = "samples/movie.mov";
        final Chunk track = convertClip (new ReaperCreator (this.notifier), newParameters (), video);
        final Chunk item = (Chunk) track.getChildNode ("ITEM").orElseThrow ();
        final Chunk source = (Chunk) item.getChildNode ("SOURCE").orElseThrow ();
        assertEquals ("VIDEO", source.getParameters ().get (0));
        assertEquals ("movie.mov", source.getChildNode ("FILE").orElseThrow ().getParameters ().get (0));
        assertTrue (this.notifier.errors.isEmpty ());
    }


    @Test
    void reportsVideoWithNoFilePath () throws Exception
    {
        final Video video = new Video ();
        final Chunk track = convertClip (new ReaperCreator (this.notifier), newParameters (), video);
        final Chunk item = (Chunk) track.getChildNode ("ITEM").orElseThrow ();
        assertTrue (item.getChildNode ("SOURCE").isEmpty ());
        assertEquals (List.of ("IDS_NOTIFY_AUDIO_FILE_NOT_FOUND"), this.notifier.errors);
    }


    @Test
    void leavesWaveAndFlacSourceTypesUnchanged () throws Exception
    {
        for (final String [] example: List.of (new String [] { "sample.wav", "WAVE" }, new String [] { "sample.FLAC", "FLAC" }))
        {
            final Audio audio = new Audio ();
            audio.file.path = example[0];
            final Chunk track = convertClip (new ReaperCreator (this.notifier), newParameters (), audio);
            final Chunk item = (Chunk) track.getChildNode ("ITEM").orElseThrow ();
            final Chunk source = (Chunk) item.getChildNode ("SOURCE").orElseThrow ();
            assertEquals (example[1], source.getParameters ().get (0));
        }
    }


    private Result convertAndSave (final Path dir, final DawProjectMediaFiles media, final com.bitwig.dawproject.timeline.Timeline timeline) throws Exception
    {
        final ReaperCreator creator = new ReaperCreator (this.notifier);
        final Object parameters = newParameters ();
        final Chunk track = convertClip (creator, parameters, timeline);
        final Chunk root = new Chunk ();
        root.setName ("REAPER_PROJECT");
        root.addChildNode (track);
        final Path output = dir.resolve ("output");
        final DawProjectContainer project = new DawProjectContainer ("test", media);
        final Method save = ReaperCreator.class.getDeclaredMethod ("saveProject", Chunk.class, DawProjectContainer.class, IMediaFiles.class, parameters.getClass (), File.class);
        save.setAccessible (true);
        save.invoke (creator, root, project, media, parameters, output.toFile ());

        final Chunk parsed = ReaperProject.parse (Files.readAllLines (output.resolve ("test.rpp")));
        final Chunk parsedTrack = (Chunk) parsed.getChildNode ("TRACK").orElseThrow ();
        final Chunk item = (Chunk) parsedTrack.getChildNode ("ITEM").orElseThrow ();
        return new Result (output, item, (Chunk) item.getChildNode ("SOURCE").orElseThrow ());
    }


    private static Chunk convertClip (final ReaperCreator creator, final Object parameters, final com.bitwig.dawproject.timeline.Timeline timeline) throws Exception
    {
        final Clip clip = new Clip ();
        clip.time = 2;
        clip.duration = Double.valueOf (4);
        clip.contentTimeUnit = TimeUnit.SECONDS;
        clip.content = timeline;
        final Chunk track = new Chunk ();
        track.setName ("TRACK");
        final Class<?> parentType = Class.forName (ReaperCreator.class.getName () + "$ParentClip");
        final Constructor<?> constructor = parentType.getDeclaredConstructor ();
        constructor.setAccessible (true);
        final Object parent = constructor.newInstance ();
        final Method convert = ReaperCreator.class.getDeclaredMethod ("convertClip", Chunk.class, Track.class, Clip.class, parentType, parameters.getClass (), boolean.class);
        convert.setAccessible (true);
        convert.invoke (creator, track, new Track (), clip, parent, parameters, Boolean.FALSE);
        return track;
    }


    private static Object newParameters () throws Exception
    {
        final Class<?> type = Class.forName (ReaperCreator.class.getName () + "$Parameters");
        final Constructor<?> constructor = type.getDeclaredConstructor ();
        constructor.setAccessible (true);
        final Object parameters = constructor.newInstance ();
        final Field tempo = type.getDeclaredField ("tempo");
        tempo.setAccessible (true);
        tempo.set (parameters, Double.valueOf (120));
        return parameters;
    }


    private static void createArchive (final Path archive, final String name, final byte [] data) throws Exception
    {
        try (final ZipOutputStream zip = new ZipOutputStream (Files.newOutputStream (archive)))
        {
            zip.putNextEntry (new ZipEntry (name));
            zip.write (data);
            zip.closeEntry ();
        }
    }


    private record Result (Path output, Chunk item, Chunk source)
    {
    }


    private static class TestNotifier implements INotifier
    {
        final List<String> errors = new ArrayList<> ();

        @Override public void log (final String id, final String... values) {}
        @Override public void logError (final String id, final String... values) { this.errors.add (id); }
        @Override public void logError (final String id, final Throwable error) { this.errors.add (id); }
        @Override public void logError (final Throwable error) { this.errors.add (error.toString ()); }
        @Override public void updateButtonStates (final boolean canClose) {}
        @Override public boolean isCancelled () { return false; }
    }
}
