// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.dawconverters.reaper.project;

import de.mossgrabers.dawconverters.utils.StreamHelper;
import de.mossgrabers.dawconverters.vst.Vst3Preset;
import de.mossgrabers.dawconverters.vst.Vst3Preset.Vst3ChunkInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.List;


/**
 * The data of a VST chunk in the Reaper project file.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public class VstChunkHandler
{
    private static final int     CHUNK_OPAQUE       = 0xFEED5EEE;
    private static final int     CHUNK_REGULAR      = 0xFEED5EEF;

    private static final int     MAGIC1             = 0xDEADBEEF;
    private static final int     MAGIC2             = 0xDEADF00D;

    // @formatter:off
    private static final byte [] VST2_CHUNK         = { 'C', 'c', 'n', 'K' };
    private static final byte [] VST2_CHUNK_OPAQUE  = { 'F', 'P', 'C', 'h' };
    private static final byte [] VST2_CHUNK_REGULAR = { 'F', 'x', 'C', 'k' };
    private static final byte [] CONNECTIONS        = { 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0 };
    // @formatter:on

    private final Decoder        decoder            = Base64.getDecoder ();
    private final Encoder        encoder            = Base64.getEncoder ();

    /** The VST ID. */
    private int                  vstID;

    /** The data type (at least I guess so). */
    private int                  dataType;

    /** The VST data. */
    private byte []              vstData;

    /** The size of the data. */
    private int                  dataSize;

    /** Null terminated preset name. */
    private String               presetName;
    private String               programName;


    /**
     * Parses the content of a VST chunk.
     *
     * @param vstChunk The VST chunk to parse
     * @throws IOException If an error occurs
     */
    public void parse (final Chunk vstChunk) throws IOException
    {
        final List<Node> childNodes = new ArrayList<> (vstChunk.getChildNodes ());
        final String lastLine = childNodes.remove (childNodes.size () - 1).getName ();
        this.readLastLine (this.decoder.decode (lastLine.trim ()));

        final ByteArrayOutputStream baos = new ByteArrayOutputStream ();
        for (final Node childNode: childNodes)
            baos.write (this.decoder.decode (childNode.getName ().trim ()));
        final InputStream in = new ByteArrayInputStream (baos.toByteArray ());

        this.vstID = StreamHelper.readIntLittleEndian (in);

        /** 0xFEED5EEE or 0xFEED5EEF for the ones with magic numbers set. */
        this.dataType = StreamHelper.readIntLittleEndian (in);
        if (this.dataType != CHUNK_OPAQUE && this.dataType != CHUNK_REGULAR)
            throw new IOException (String.format ("Unsupported data format: %X", Integer.valueOf (this.dataType)));

        /** Number of input channels for routing. */
        final int numInputs = StreamHelper.readIntLittleEndian (in);

        // A bit mask array of 8 bytes each, for the input routings
        // bit 0 = input channel 1 is set; bit 63 = input channel 64 is set; etc.
        // The length of the above array is defined by the number of inputs
        // Total size is 8*NumInputs bytes
        in.skipNBytes (8L * numInputs);

        /** Number of output channels for routing. */
        final int numOutputs = StreamHelper.readIntLittleEndian (in);

        // Again bit mask array 8 bytes each for outputs this time...
        in.skipNBytes (8L * numOutputs);

        // Size of the VST Data and the 2 magic numbers
        this.dataSize = StreamHelper.readIntLittleEndian (in);

        // Seems to indicate the magic numbers (0 = magic / regular, 1 = no magic / opaque)
        StreamHelper.readIntLittleEndian (in);
        // Found: 100000, 100002, 10000C, 10FFFF
        StreamHelper.readIntLittleEndian (in);

        // Magic numbers or already VST data?
        in.mark (2);
        final int magicNumber1 = StreamHelper.readIntLittleEndian (in);
        final int magicNumber2 = StreamHelper.readIntLittleEndian (in);
        if (magicNumber1 == MAGIC1 && magicNumber2 == MAGIC2)
            this.dataSize -= 8;
        else
            in.reset ();

        // Read the VST data
        this.vstData = in.readNBytes (this.dataSize);
    }


    /**
     * Creates a VST chunk.
     *
     * @param vstChunk The VST chunk to create
     * @throws IOException If an error occurs
     */
    public void create (final Chunk vstChunk) throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream ();

        StreamHelper.writeIntLittleEndian (out, this.vstID);

        /** 0xFEED5EEE or 0xFEED5EEF for the ones with magic numbers set. */
        StreamHelper.writeIntLittleEndian (out, this.dataType);

        // Only can support stereo - other info not available
        final int numInputs = 2;
        final int numOutputs = 2;

        /** Number of input channels for routing. */
        StreamHelper.writeIntLittleEndian (out, numInputs);
        out.write (CONNECTIONS);

        /** Number of output channels for routing. */
        StreamHelper.writeIntLittleEndian (out, numOutputs);
        out.write (CONNECTIONS);

        // Size of the VST Data and the 2 magic numbers
        StreamHelper.writeIntLittleEndian (out, this.dataSize);

        // Seems to indicate the magic numbers (0 = magic, 1 = no magic)
        final boolean isOpaque = this.dataType == CHUNK_OPAQUE;
        StreamHelper.writeIntLittleEndian (out, isOpaque ? 1 : 0);
        // Found: 100000, 100002, 10000C, 10FFFF
        StreamHelper.writeIntLittleEndian (out, 0x100000);

        createLines (vstChunk, this.encoder.encode (out.toByteArray ()));

        out.reset ();

        // Magic numbers or already VST data?
        if (!isOpaque)
        {
            StreamHelper.writeIntLittleEndian (out, MAGIC1);
            StreamHelper.writeIntLittleEndian (out, MAGIC2);
        }

        // Write the VST data
        out.write (this.vstData);

        createLines (vstChunk, this.encoder.encode (out.toByteArray ()));

        // Last line
        out.reset ();

        StreamHelper.writeString (out, this.programName);
        out.write (0);
        StreamHelper.writeString (out, this.presetName);
        out.write (0);
        out.write (0x10);
        out.write (0);
        out.write (0);
        out.write (0);
        createLines (vstChunk, this.encoder.encode (out.toByteArray ()));
    }


    /**
     * Footer - contains the preset name and some unknown stuff.
     *
     * @param bs The bytes to read
     * @throws IOException
     */
    private void readLastLine (final byte [] bs) throws IOException
    {
        if (bs.length <= 1)
        {
            this.programName = "";
            this.presetName = "";
            return;
        }

        final ByteArrayInputStream in = new ByteArrayInputStream (bs);
        this.programName = StreamHelper.readString (in);
        this.presetName = StreamHelper.readString (in);

        // Always: 0x10 00 00 00
        in.readNBytes (4);
    }


    /**
     * Write a VST 2 preset file.
     *
     * @param out The output stream to write to
     * @throws IOException If an error occurs
     */
    public void writeVST2Preset (final OutputStream out) throws IOException
    {
        // VST Chunk ID
        out.write (VST2_CHUNK);

        // Size of this chunk
        StreamHelper.writeIntBigEndian (out, 48 + this.dataSize);

        // 'FPCh' (opaque chunk)
        final boolean isOpaque = this.dataType == CHUNK_OPAQUE;
        if (isOpaque)
            out.write (VST2_CHUNK_OPAQUE);
        else
            out.write (VST2_CHUNK_REGULAR);

        // Format version (currently 1)
        StreamHelper.writeIntBigEndian (out, 1);

        // FX unique ID
        StreamHelper.writeIntBigEndian (out, this.vstID);

        // FX version - the info is not available, so set it always to 1
        StreamHelper.writeIntBigEndian (out, 1);

        // Number of parameters
        StreamHelper.writeIntBigEndian (out, isOpaque ? 0 : this.dataSize / 4);

        // Program name (null-terminated ASCII string)
        final String name = this.getPresetName ();
        final byte [] bytes = name.getBytes (StandardCharsets.US_ASCII);
        out.write (bytes);
        for (int i = 0; i < 28 - bytes.length; i++)
            out.write (0);

        // Write size
        if (isOpaque)
            StreamHelper.writeIntBigEndian (out, this.dataSize);

        out.write (this.vstData);
    }


    /**
     * Read a VST 2 preset file.
     *
     * @param in The output stream to write to
     * @throws IOException If an error occurs
     */
    public void readVST2Preset (final InputStream in) throws IOException
    {
        // VST Chunk ID
        in.skipNBytes (4);

        // Size of this chunk
        this.dataSize = StreamHelper.readIntBigEndian (in) - 48;

        final boolean isOpaque = "FPCh".equals (StreamHelper.readString (in, 4));
        this.dataType = isOpaque ? CHUNK_OPAQUE : CHUNK_REGULAR;

        // Format version (currently 1)
        StreamHelper.readIntBigEndian (in);

        // FX unique ID
        this.vstID = StreamHelper.readIntBigEndian (in);

        // FX version - the info is not available, so set it always to 1
        StreamHelper.readIntBigEndian (in);

        // Number of parameters
        StreamHelper.readIntBigEndian (in);

        // Program name (null-terminated ASCII string)
        this.presetName = new String (in.readNBytes (28), StandardCharsets.US_ASCII);

        // Data size
        if (isOpaque)
            StreamHelper.readIntBigEndian (in);

        this.vstData = in.readAllBytes ();
        this.dataSize = this.vstData.length;

        this.programName = "";
    }


    /**
     * Write a VST 3 preset file.
     *
     * @param out The output stream to write to
     * @param deviceID The ID of the device
     * @throws IOException If an error occurs
     */
    public void writeVST3Preset (final OutputStream out, final String deviceID) throws IOException
    {
        // The Reaper data block has the following structure:
        // 4 bytes: Length of the first chunk
        // 4 bytes: Reserved for future use (always 01 00 00 00)
        // n bytes: The first chunk
        // 4 bytes: Length of the second chunk
        // 4 bytes: Reserved for future use (always 00 00 00 00)
        // n bytes: The second chunk (may be empty)

        final List<byte []> chunks = new ArrayList<> ();

        final ByteArrayInputStream bais = new ByteArrayInputStream (this.vstData);
        while (bais.available () > 0)
        {
            final int length = StreamHelper.readIntLittleEndian (bais);
            bais.skipNBytes (4);
            chunks.add (bais.readNBytes (length));
        }

        if (chunks.size () > 2)
            throw new IOException ("Found more than 2 VST3 chunks?!");

        new Vst3Preset ().write (out, deviceID, chunks);
    }


    /**
     * Read a VST 3 preset file.
     *
     * @param in The output stream to write to
     * @throws IOException If an error occurs
     */
    public void readVST3Preset (final InputStream in) throws IOException
    {
        final Vst3Preset preset = new Vst3Preset ();
        preset.read (in);

        // Still no idea what is used here by Reaper
        this.vstID = 0;

        this.dataType = CHUNK_OPAQUE;

        final byte [] data = preset.getData ();
        final Vst3ChunkInfo [] chunkInfos = preset.getChunkInfos ();
        this.dataSize = data.length + 8 * chunkInfos.length;

        final ByteArrayOutputStream out = new ByteArrayOutputStream ();

        for (int i = 0; i < chunkInfos.length; i++)
        {
            StreamHelper.writeIntLittleEndian (out, (int) chunkInfos[i].size);
            StreamHelper.writeIntLittleEndian (out, i == 0 ? 0x01000000 : 0);

            final int offset = (int) (chunkInfos[i].offset - Vst3Preset.HEADER_SIZE);
            out.write (data, offset, (int) chunkInfos[i].size);
        }

        this.vstData = out.toByteArray ();

        this.programName = "";
        this.presetName = "";
    }


    /**
     * Get the preset name limited to a maximum of 28 character. If the preset name is not available
     * try the program name.
     *
     * @return The name
     */
    private String getPresetName ()
    {
        final String name = this.presetName.isBlank () ? this.programName : this.presetName;
        return name.length () < 28 ? name : name.substring (0, 27);
    }


    private static void createLines (final Chunk vstChunk, final byte [] lineData)
    {
        final String line = new String (lineData, StandardCharsets.US_ASCII);
        final int length = line.length ();
        for (int i = 0; i < length; i += 128)
        {
            final Node lineNode = new Node ();
            lineNode.setName (line.substring (i, Math.min (i + 128, length)));
            vstChunk.addChildNode (lineNode);
        }
    }
}
