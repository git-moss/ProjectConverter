package de.mossgrabers.dawconverters.reaper;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.MixerRole;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.RealParameter;
import com.bitwig.dawproject.Referencable;
import com.bitwig.dawproject.TimeSignatureParameter;
import com.bitwig.dawproject.TimelineRole;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.Transport;
import com.bitwig.dawproject.Unit;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Timebase;

import java.util.List;
import java.util.Optional;


public class ReaperConverter
{
    private static final String CHUNK_TRACK     = "TRACK";
    private static final String TRACK_NAME      = "NAME";
    private static final String TRACK_COLOR     = "PEAKCOL";
    private static final String TRACK_STRUCTURE = "ISBUS";


    public static Project createProject (final Chunk rootChunk)
    {
        Referencable.resetID ();

        final Project project = new Project ();

        project.application.name = "Test";
        project.application.version = "1.0";

        // TODO
        project.transport = new Transport ();
        final var tempoParam = new RealParameter ();
        tempoParam.value = 120.0;
        project.transport.tempo = tempoParam;
        project.transport.timeSignature = new TimeSignatureParameter ();
        project.transport.timeSignature.numerator = 4;
        project.transport.timeSignature.denominator = 4;

        final Track masterTrack = new Track ();
        project.tracks.add (masterTrack);
        masterTrack.name = "Master";

        final var p3 = new RealParameter ();
        p3.value = 1.0;
        p3.unit = Unit.linear;
        masterTrack.volume = p3;

        final var p2 = new RealParameter ();
        p2.value = 0.0;
        p2.unit = Unit.linear;
        masterTrack.pan = p2;
        masterTrack.mixerRole = MixerRole.master;

        project.arrangement = new Arrangement ();
        final var arrangementLanes = new Lanes ();
        arrangementLanes.timebase = Timebase.beats;
        project.arrangement.content = arrangementLanes;

        for (Node node: rootChunk.getChildNodes ())
        {
            if (node instanceof Chunk subChunk)
            {
                if (CHUNK_TRACK.equals (subChunk.getName ()))
                {
                    final var track = new Track ();
                    project.tracks.add (track);

                    // Set track name
                    final Optional<Node> nameNode = subChunk.getParameter (TRACK_NAME);
                    track.name = nameNode.isPresent () ? nameNode.get ().getParameters ().get (0) : "Track";

                    // Set track color
                    final int color = getIntParam (subChunk.getParameter (TRACK_COLOR), -1);
                    if (color >= 0)
                        track.color = toHexColor (color);
                    
                    // Comment
                    // TODO track.comment

                    // TODO
                    track.timelineRole = new TimelineRole []
                    {
                        TimelineRole.notes,
                        TimelineRole.audio
                    };
                    
                    final Optional<Node> nameNode = subChunk.getParameter (TRACK_STRUCTURE);
                    
                     1 1
                    
                    
                }
            }
        }

        return project;
    }


    private static int getIntParam (final Optional<Node> node, final int defaultValue)
    {
        if (node.isEmpty ())
            return defaultValue;
        final List<String> parameters = node.get ().getParameters ();
        if (parameters.isEmpty ())
            return defaultValue;
        try
        {
            return Integer.parseInt (parameters.get (0));
        }
        catch (final NumberFormatException ex)
        {
            return defaultValue;
        }
    }


    private static String toHexColor (final int color)
    {
        // Remove alpha
        final int c = 0xFFFFFF & color;
        return String.format ("#%02X%02X%02X", Integer.valueOf (c & 0xFF), Integer.valueOf ((c >> 8) & 0xFF), Integer.valueOf ((c >> 16) & 0xFF));
    }
}
