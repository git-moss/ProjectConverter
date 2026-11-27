package de.mossgrabers.projectconverter.format.reaper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.Channel;
import com.bitwig.dawproject.FileReference;
import com.bitwig.dawproject.Interpolation;
import com.bitwig.dawproject.RealParameter;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.Unit;
import com.bitwig.dawproject.device.Device;
import com.bitwig.dawproject.device.Vst2Plugin;
import com.bitwig.dawproject.device.Vst3Plugin;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Points;
import com.bitwig.dawproject.timeline.RealPoint;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IMediaFiles;
import de.mossgrabers.projectconverter.format.reaper.model.Chunk;
import de.mossgrabers.projectconverter.format.reaper.model.Node;
import de.mossgrabers.projectconverter.format.reaper.model.ReaperProject;
import de.mossgrabers.projectconverter.format.reaper.model.VstChunkHandler;
import de.mossgrabers.projectconverter.vst.Vst3Preset;

class ReaperVstAutomationTest
{
    private final TestNotifier notifier = new TestNotifier ();


    @Test
    void importsMasterAndTrackVstAutomationFromProject (@TempDir final Path directory) throws Exception
    {
        final Chunk root = chunk ("REAPER_PROJECT", "0.1", "7.0");
        root.addChildNode (node ("TEMPO", "120", "4", "4"));
        final Chunk masterFx = chunk ("MASTERFXLIST");
        root.addChildNode (masterFx);
        addVstAndEnvelope (masterFx, 3);
        final Chunk track = chunk ("TRACK");
        root.addChildNode (track);
        track.addChildNode (node ("NAME", "Track"));
        final Chunk trackFx = chunk ("FXCHAIN");
        track.addChildNode (trackFx);
        addVstAndEnvelope (trackFx, 7);
        final Path path = directory.resolve ("automation.rpp");
        Files.writeString (path, ReaperProject.format (root));

        try (final DawProjectContainer project = new ReaperDetector (this.notifier).read (path.toFile ()))
        {
            final List<com.bitwig.dawproject.timeline.Timeline> lanes = project.getProject ().arrangement.lanes.lanes;
            final Lanes masterLanes = (Lanes) lanes.get (0);
            final Lanes trackLanes = (Lanes) lanes.get (1);
            final Points masterPoints = (Points) masterLanes.lanes.get (0);
            final Points trackPoints = (Points) trackLanes.lanes.stream ().filter (Points.class::isInstance).findFirst ().orElseThrow ();
            assertEquals (3, masterPoints.target.parameter.parameterID);
            assertEquals (7, trackPoints.target.parameter.parameterID);
            assertEquals (0.75, ((RealPoint) trackPoints.points.get (1)).value);
        }
        assertTrue (this.notifier.errors.isEmpty (), this.notifier.errors.toString ());
    }


    @Test
    void exportsAndImportsVst3IdentifiersWithoutGuessingIndices () throws Exception
    {
        final Vst3Plugin plugin = new Vst3Plugin ();
        plugin.deviceName = "Test";
        final Chunk chain = chunk ("FXCHAIN");
        final Node pluginNode = chunk ("VST");
        chain.addChildNode (pluginNode);
        final int [] ids = { 0, 3029309, -1611133501, Integer.MIN_VALUE };
        for (final int id: ids)
        {
            final RealParameter parameter = parameter (id);
            plugin.automatedParameters.add (parameter);
            exportEnvelope (plugin, chain, pluginNode, points (parameter), false, new com.bitwig.dawproject.Project ());
        }

        final Chunk root = chunk ("REAPER_PROJECT");
        root.addChildNode (chain);
        final Chunk parsed = ReaperProject.parse (ReaperProject.format (root).lines ().toList ());
        final List<Node> envelopes = ((Chunk) parsed.getChildNode ("FXCHAIN").orElseThrow ()).getChildNodes ("PARMENV");
        assertEquals (ids.length, envelopes.size ());
        final Vst3Plugin imported = new Vst3Plugin ();
        final Lanes lanes = new Lanes ();
        for (final Node envelopeNode: envelopes)
        {
            final Chunk envelope = (Chunk) envelopeNode;
            final String identifier = envelope.getParameters ().get (0);
            assertTrue (identifier.startsWith ("2147483647:"));
            importEnvelope (new Track (), imported, lanes, envelope);
            final Points result = (Points) lanes.lanes.get (lanes.lanes.size () - 1);
            assertEquals (Double.valueOf (2), result.points.get (0).time);
            assertEquals (Interpolation.HOLD, ((RealPoint) result.points.get (1)).interpolation);
        }
        assertEquals (List.of (Integer.MIN_VALUE, -1611133501, 3029309, 0), imported.automatedParameters.stream ().map (p -> p.parameterID).toList ());
        assertTrue (this.notifier.errors.isEmpty (), this.notifier.errors.toString ());
    }


    @Test
    void importsUnsignedVst3Identifiers () throws Exception
    {
        final Vst3Plugin plugin = new Vst3Plugin ();
        final Lanes lanes = new Lanes ();
        importEnvelope (new Track (), plugin, lanes, chunk ("PARMENV", "3:4294967295", "0", "1", "0.5"));
        assertEquals (-1, plugin.automatedParameters.get (0).parameterID);
        importEnvelope (new Track (), plugin, lanes, chunk ("PARMENV", "3:4294967296", "0", "1", "0.5"));
        assertEquals (1, plugin.automatedParameters.size ());
        assertEquals (1, this.notifier.errors.size ());
    }


    @Test
    void importsVst3IdentifierAndPointShapes () throws Exception
    {
        final Vst3Plugin plugin = new Vst3Plugin ();
        final Track track = new Track ();
        final Lanes lanes = new Lanes ();
        final Chunk envelope = chunk ("PARMENV", "27:3029309", "0", "1", "0.5");
        envelope.addChildNode (node ("PT", "1.25", "0.2", "0"));
        envelope.addChildNode (node ("PT", "2.5", "0.8", "1"));

        importEnvelope (track, plugin, lanes, envelope);

        assertEquals (3029309, plugin.automatedParameters.get (0).parameterID);
        final Points points = (Points) lanes.lanes.get (0);
        assertSame (plugin.automatedParameters.get (0), points.target.parameter);
        assertEquals (1.25, points.points.get (0).time);
        assertEquals (Interpolation.LINEAR, ((RealPoint) points.points.get (0)).interpolation);
        assertEquals (Interpolation.HOLD, ((RealPoint) points.points.get (1)).interpolation);
        assertTrue (this.notifier.errors.isEmpty ());
    }


    @Test
    void rejectsUnknownIdentifiersAndUnsupportedShapes () throws Exception
    {
        final Vst3Plugin plugin = new Vst3Plugin ();
        final Track track = new Track ();
        final Lanes lanes = new Lanes ();
        final Chunk missingId = chunk ("PARMENV", "27", "0", "1", "0.5");
        importEnvelope (track, plugin, lanes, missingId);
        final Chunk curved = chunk ("PARMENV", "27:3029309", "0", "1", "0.5");
        curved.addChildNode (node ("PT", "0", "0.5", "5"));
        importEnvelope (track, plugin, lanes, curved);
        final Chunk item = chunk ("PARMENV", "27:3029309", "0", "1", "0.5");
        item.addChildNode (node ("POOLEDENVINST", "1"));
        importEnvelope (track, plugin, lanes, item);
        final Chunk invalid = chunk ("PARMENV", "27:3029309", "0", "1", "0.5");
        invalid.addChildNode (node ("PT", "1", "not-a-number", "0"));
        importEnvelope (track, plugin, lanes, invalid);
        assertTrue (plugin.automatedParameters.isEmpty ());
        assertTrue (lanes.lanes.isEmpty ());
        assertEquals (4, this.notifier.errors.size ());
    }


    @Test
    void exportsVst2AutomationToItsOwnPluginAndRoundTrips () throws Exception
    {
        final Vst2Plugin plugin = new Vst2Plugin ();
        plugin.deviceName = "Test";
        final RealParameter parameter = parameter (7);
        plugin.automatedParameters.add (parameter);
        final Points points = points (parameter);
        final Chunk chain = chunk ("FXCHAIN");
        final Node firstPlugin = chunk ("VST");
        final Node secondPlugin = chunk ("VST");
        chain.addChildNode (firstPlugin);
        chain.addChildNode (node ("BYPASS", "0", "0"));
        chain.addChildNode (secondPlugin);

        exportEnvelope (plugin, chain, firstPlugin, points, true, new com.bitwig.dawproject.Project ());

        final Chunk result = (Chunk) chain.getChildNodes ().get (1);
        assertEquals ("PARMENV", result.getName ());
        assertEquals ("7", result.getParameters ().get (0));
        assertEquals ("1.0", result.getChildNodes ("PT").get (0).getParameters ().get (0));
        assertEquals ("0", result.getChildNodes ("PT").get (0).getParameters ().get (2));
        assertEquals ("1", result.getChildNodes ("PT").get (1).getParameters ().get (2));
        assertSame (secondPlugin, chain.getChildNodes ().get (3));

        final Vst2Plugin imported = new Vst2Plugin ();
        final Lanes lanes = new Lanes ();
        importEnvelope (new Track (), imported, lanes, result);
        final Points roundTrip = (Points) lanes.lanes.get (0);
        assertEquals (7, imported.automatedParameters.get (0).parameterID);
        assertEquals (1.0, roundTrip.points.get (0).time);
        assertEquals (Interpolation.HOLD, ((RealPoint) roundTrip.points.get (1)).interpolation);
    }


    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void exportsNestedMasterAutomationThroughDeviceMapping (final boolean vst3) throws Exception
    {
        final Device plugin = vst3 ? new Vst3Plugin () : new Vst2Plugin ();
        plugin.deviceName = "Test";
        plugin.deviceID = vst3 ? "ABCDEF019182FAEB4D616E7548683265" : "1234";
        plugin.state = new FileReference ();
        plugin.state.path = "test.fxp";
        final RealParameter parameter = parameter (4);
        plugin.automatedParameters.add (parameter);
        final Track master = new Track ();
        master.channel = new Channel ();
        master.channel.devices = List.of (plugin);
        final Lanes lanes = new Lanes ();
        lanes.track = master;
        final Lanes nested = new Lanes ();
        nested.lanes.add (points (parameter));
        lanes.lanes.add (nested);
        final Chunk root = chunk ("REAPER_PROJECT");
        final ReaperCreator creator = new ReaperCreator (this.notifier);
        final Class<?> parametersType = Class.forName (ReaperCreator.class.getName () + "$Parameters");
        final Object parameters = newInstance (parametersType);
        final Field tempo = parametersType.getDeclaredField ("tempo");
        tempo.setAccessible (true);
        tempo.set (parameters, Double.valueOf (120));
        final byte [] preset;
        if (vst3)
        {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream ();
            new Vst3Preset ().write (bytes, plugin.deviceID, List.of (new byte [8]));
            preset = bytes.toByteArray ();
        }
        else
            preset = preset ();
        final IMediaFiles media = new IMediaFiles ()
        {
            @Override public InputStream stream (final String id) { return new ByteArrayInputStream (preset); }
            @Override public void add (final String id, final File file) {}
            @Override public List<String> getAll () { return List.of (); }
            @Override public void close () {}
        };
        final Method convertDevices = ReaperCreator.class.getDeclaredMethod ("convertDevices", IMediaFiles.class, List.class, boolean.class, Chunk.class, String.class, parametersType);
        convertDevices.setAccessible (true);
        convertDevices.invoke (creator, media, List.of (plugin), Boolean.TRUE, root, "MASTERFXLIST", parameters);
        final Method convertLanes = ReaperCreator.class.getDeclaredMethod ("convertLanes", Chunk.class, parametersType, Track.class, Lanes.class, com.bitwig.dawproject.Project.class, Track.class, boolean.class);
        convertLanes.setAccessible (true);
        convertLanes.invoke (creator, root, parameters, null, lanes, new com.bitwig.dawproject.Project (), master, Boolean.TRUE);

        final Chunk fx = (Chunk) root.getChildNode ("MASTERFXLIST").orElseThrow ();
        final Chunk envelope = (Chunk) fx.getChildNode ("PARMENV").orElseThrow ();
        assertEquals (vst3 ? "2147483647:4" : "4", envelope.getParameters ().get (0));
        assertEquals ("1.0", envelope.getChildNodes ("PT").get (0).getParameters ().get (0));
        assertTrue (this.notifier.errors.isEmpty (), this.notifier.errors.toString ());
    }


    @Test
    void refusesUnsafeUnitsAndBeatAutomationWithTempoChanges () throws Exception
    {
        final Chunk chain = chunk ("FXCHAIN");
        final Node pluginNode = chunk ("VST");
        chain.addChildNode (pluginNode);
        final RealParameter parameter = parameter (7);
        final Points points = points (parameter);
        final com.bitwig.dawproject.Project project = new com.bitwig.dawproject.Project ();
        project.arrangement = new Arrangement ();
        project.arrangement.tempoAutomation = new Points ();
        exportEnvelope (new Vst3Plugin (), chain, pluginNode, points, true, project);
        points.unit = Unit.PERCENT;
        exportEnvelope (new Vst3Plugin (), chain, pluginNode, points, false, new com.bitwig.dawproject.Project ());
        points.unit = Unit.NORMALIZED;
        parameter.parameterID = null;
        exportEnvelope (new Vst3Plugin (), chain, pluginNode, points, false, new com.bitwig.dawproject.Project ());
        parameter.parameterID = Integer.valueOf (-1);
        exportEnvelope (new Vst2Plugin (), chain, pluginNode, points, false, new com.bitwig.dawproject.Project ());
        assertEquals (1, chain.getChildNodes ().size ());
        assertEquals (4, this.notifier.errors.size ());
    }


    private void importEnvelope (final Track track, final Device plugin, final Lanes lanes, final Chunk envelope) throws Exception
    {
        final Class<?> structureType = Class.forName (ReaperDetector.class.getName () + "$FolderStructure");
        final Object structure = newInstance (structureType);
        final Field field = structureType.getDeclaredField ("trackLanesMap");
        field.setAccessible (true);
        @SuppressWarnings("unchecked")
        final Map<Track, Lanes> tracks = (Map<Track, Lanes>) field.get (structure);
        tracks.put (track, lanes);
        final Method method = ReaperDetector.class.getDeclaredMethod ("createAutomationParameters", Track.class, Device.class, Chunk.class, structureType);
        method.setAccessible (true);
        method.invoke (new ReaperDetector (this.notifier), track, plugin, envelope, structure);
    }


    private void exportEnvelope (final Device plugin, final Chunk chain, final Node pluginNode, final Points points, final boolean beats, final com.bitwig.dawproject.Project project) throws Exception
    {
        final Class<?> mappingType = Class.forName (ReaperCreator.class.getName () + "$DeviceParameter");
        final Constructor<?> constructor = mappingType.getDeclaredConstructor (Device.class, Chunk.class, Node.class);
        constructor.setAccessible (true);
        final Object mapping = constructor.newInstance (plugin, chain, pluginNode);
        final Class<?> parametersType = Class.forName (ReaperCreator.class.getName () + "$Parameters");
        final Object parameters = newInstance (parametersType);
        final Field tempo = parametersType.getDeclaredField ("tempo");
        tempo.setAccessible (true);
        tempo.set (parameters, Double.valueOf (120));
        final Method method = ReaperCreator.class.getDeclaredMethod ("createDeviceEnvelope", com.bitwig.dawproject.Project.class, mappingType, Points.class, boolean.class, parametersType);
        method.setAccessible (true);
        method.invoke (new ReaperCreator (this.notifier), project, mapping, points, Boolean.valueOf (beats), parameters);
    }


    private static Object newInstance (final Class<?> type) throws Exception
    {
        final Constructor<?> constructor = type.getDeclaredConstructor ();
        constructor.setAccessible (true);
        return constructor.newInstance ();
    }


    private static RealParameter parameter (final int id)
    {
        final RealParameter parameter = new RealParameter ();
        parameter.parameterID = Integer.valueOf (id);
        parameter.unit = Unit.NORMALIZED;
        parameter.min = Double.valueOf (0);
        parameter.max = Double.valueOf (1);
        return parameter;
    }


    private static Points points (final RealParameter parameter)
    {
        final Points points = new Points ();
        points.target.parameter = parameter;
        final RealPoint first = new RealPoint ();
        first.time = Double.valueOf (2);
        first.value = Double.valueOf (0.25);
        first.interpolation = Interpolation.LINEAR;
        final RealPoint second = new RealPoint ();
        second.time = Double.valueOf (4);
        second.value = Double.valueOf (0.75);
        second.interpolation = Interpolation.HOLD;
        points.points.add (first);
        points.points.add (second);
        return points;
    }


    private static Chunk chunk (final String name, final String... parameters)
    {
        final Chunk chunk = new Chunk ();
        chunk.setName (name);
        chunk.addParameters (List.of (parameters));
        return chunk;
    }

    private static void addVstAndEnvelope (final Chunk chain, final int parameterId) throws Exception
    {
        chain.addChildNode (node ("BYPASS", "0", "0"));
        final Chunk vst = chunk ("VST", "VST: Test", "test.dll", "0", "", "1234<0000>");
        new VstChunkHandler (true, null).fileToChunk (new ByteArrayInputStream (preset ()), vst);
        chain.addChildNode (vst);
        final Chunk envelope = chunk ("PARMENV", Integer.toString (parameterId), "0", "1", "0.5");
        envelope.addChildNode (node ("PT", "1", "0.25", "0"));
        envelope.addChildNode (node ("PT", "2", "0.75", "1"));
        chain.addChildNode (envelope);
    }


    private static byte [] preset () throws Exception
    {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream ();
        try (final DataOutputStream preset = new DataOutputStream (bytes))
        {
            preset.writeBytes ("CcnK");
            preset.writeInt (48);
            preset.writeBytes ("FxCk");
            preset.writeInt (1);
            preset.writeInt (1234);
            preset.writeInt (1);
            preset.writeInt (0);
            preset.write (new byte [28]);
            preset.write (new byte [8]);
        }
        return bytes.toByteArray ();
    }


    private static Node node (final String name, final String... parameters)
    {
        final Node node = new Node ();
        node.setName (name);
        node.addParameters (List.of (parameters));
        return node;
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
