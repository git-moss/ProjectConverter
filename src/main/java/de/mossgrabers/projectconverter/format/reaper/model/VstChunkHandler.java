// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.mossgrabers.projectconverter.utils.StreamHelper;
import de.mossgrabers.projectconverter.vst.Vst3Preset;
import de.mossgrabers.projectconverter.vst.Vst3Preset.Vst3ChunkInfo;
import de.mossgrabers.tools.ui.Functions;


/**
 * The data of a VST chunk in the Reaper project file.
 *
 * @author Jürgen Moßgraber
 */
public class VstChunkHandler extends DeviceChunkHandler
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

    private final boolean        isVST2;
    private final String         deviceID;

    /** The VST ID. */
    private int                  vstID;

    /** The data type (at least I guess so). */
    private int                  dataType;

    /** Null terminated preset name. */
    private String               presetName;
    private String               programName;


    /**
     * Constructor.
     *
     * @param isVST2 True if VST2 otherwise VST3
     * @param deviceID The device ID, if VST3
     */
    public VstChunkHandler (final boolean isVST2, final String deviceID)
    {
        this.isVST2 = isVST2;
        this.deviceID = deviceID;
    }


    /** {@inheritDoc} */
    @Override
    public void chunkToFile (final Chunk chunk, final OutputStream out) throws IOException
    {
        final List<Node> childNodes = new ArrayList<> (chunk.getChildNodes ());
        final String lastLine = childNodes.remove (childNodes.size () - 1).getName ();
        this.readLastLine (this.decoder.decode (lastLine.trim ()));

        final ByteArrayOutputStream baos = new ByteArrayOutputStream ();
        for (final Node childNode: childNodes)
            baos.write (this.decoder.decode (childNode.getName ().trim ()));
        final InputStream in = new ByteArrayInputStream (baos.toByteArray ());

        this.vstID = StreamHelper.readIntLittleEndian (in);

        // 0xFEED5EEE or 0xFEED5EEF for the ones with magic numbers set
        this.dataType = StreamHelper.readIntLittleEndian (in);
        if (this.dataType != CHUNK_OPAQUE && this.dataType != CHUNK_REGULAR)
            throw new IOException (String.format ("Unsupported data format: %X", Integer.valueOf (this.dataType)));

        // Number of input channels for routing
        final int numInputs = StreamHelper.readIntLittleEndian (in);

        // A bit mask array of 8 bytes each, for the input routings
        // bit 0 = input channel 1 is set; bit 63 = input channel 64 is set; etc.
        // The length of the above array is defined by the number of inputs
        // Total size is 8*NumInputs bytes
        in.skipNBytes (8L * numInputs);

        // Number of output channels for routing
        final int numOutputs = StreamHelper.readIntLittleEndian (in);

        // Again bit mask array 8 bytes each for outputs this time...
        in.skipNBytes (8L * numOutputs);

        // Size of the VST Data and the 2 magic numbers
        int dataSize = StreamHelper.readIntLittleEndian (in);

        // Seems to indicate the magic numbers (0 = magic / regular, 1 = no magic / opaque)
        StreamHelper.readIntLittleEndian (in);
        // Found: 100000, 100002, 10000C, 10FFFF
        StreamHelper.readIntLittleEndian (in);

        // Magic numbers or already VST data?
        in.mark (2);
        final int magicNumber1 = StreamHelper.readIntLittleEndian (in);
        final int magicNumber2 = StreamHelper.readIntLittleEndian (in);
        if (magicNumber1 == MAGIC1 && magicNumber2 == MAGIC2)
            dataSize -= 8;
        else
            in.reset ();

        // Read the VST data
        final byte [] data = in.readNBytes (dataSize);

        if (this.isVST2)
            this.writeVST2Preset (out, data);
        else
            this.writeVST3Preset (out, data);
    }


    /** {@inheritDoc} */
    @Override
    public void fileToChunk (final InputStream in, final Chunk chunk) throws IOException
    {
        final byte [] data = this.isVST2 ? this.readVST2Preset (in) : this.readVST3Preset (in);

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
        StreamHelper.writeIntLittleEndian (out, data.length);

        // Seems to indicate the magic numbers (0 = magic, 1 = no magic)
        final boolean isOpaque = this.dataType == CHUNK_OPAQUE;
        StreamHelper.writeIntLittleEndian (out, isOpaque ? 1 : 0);
        // Found: 100000, 100002, 10000C, 10FFFF
        StreamHelper.writeIntLittleEndian (out, 0x100000);

        createLines (chunk, this.encoder.encode (out.toByteArray ()));

        out.reset ();

        // Magic numbers or already VST data?
        if (!isOpaque)
        {
            StreamHelper.writeIntLittleEndian (out, MAGIC1);
            StreamHelper.writeIntLittleEndian (out, MAGIC2);
        }

        // Write the VST data
        out.write (data);

        createLines (chunk, this.encoder.encode (out.toByteArray ()));

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
        createLines (chunk, this.encoder.encode (out.toByteArray ()));
    }


    /**
     * Footer - contains the preset name and some unknown stuff.
     *
     * @param bs The bytes to read
     * @throws IOException Could not read the bytes
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
     * @param data The data to store
     * @throws IOException If an error occurs
     */
    private void writeVST2Preset (final OutputStream out, final byte [] data) throws IOException
    {
        // VST Chunk ID
        out.write (VST2_CHUNK);

        // Size of this chunk
        StreamHelper.writeIntBigEndian (out, 48 + data.length);

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
        StreamHelper.writeIntBigEndian (out, isOpaque ? 0 : data.length / 4);

        // Program name (null-terminated ASCII string)
        final String name = this.getPresetName ();
        final byte [] bytes = name.getBytes (StandardCharsets.US_ASCII);
        out.write (bytes);
        for (int i = 0; i < 28 - bytes.length; i++)
            out.write (0);

        // Write size
        if (isOpaque)
            StreamHelper.writeIntBigEndian (out, data.length);

        out.write (data);
    }


    /**
     * Read a VST 2 preset file.
     *
     * @param in The output stream to write to
     * @return The read preset data
     * @throws IOException If an error occurs
     */
    private byte [] readVST2Preset (final InputStream in) throws IOException
    {
        // VST Chunk ID
        in.skipNBytes (4);

        // Size of this chunk (the following data, header is 48 bytes)
        StreamHelper.readIntBigEndian (in);

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

        this.programName = "";

        return in.readAllBytes ();
    }


    /**
     * Write a VST 3 preset file.
     *
     * @param out The output stream to write to
     * @param data The data to write
     * @throws IOException If an error occurs
     */
    private void writeVST3Preset (final OutputStream out, final byte [] data) throws IOException
    {
        // The Reaper data block has the following structure:
        // 4 bytes: Length of the first chunk
        // 4 bytes: Reserved for future use (always 01 00 00 00)
        // n bytes: The first chunk
        // 4 bytes: Length of the second chunk
        // 4 bytes: Reserved for future use (always 00 00 00 00)
        // n bytes: The second chunk (may be empty)

        final List<byte []> chunks = new ArrayList<> ();

        final ByteArrayInputStream bais = new ByteArrayInputStream (data);
        while (bais.available () > 8)
        {
            final int length = StreamHelper.readIntLittleEndian (bais);
            bais.skipNBytes (4);
            chunks.add (bais.readNBytes (length));
        }

        if (chunks.size () > 2)
            throw new IOException (Functions.getMessage ("IDS_NOTIFY_MORE_THAN_2_VST3_CHUNKS"));

        new Vst3Preset ().write (out, this.deviceID, chunks);
    }


    /**
     * Read a VST 3 preset file.
     *
     * @param in The output stream to write to
     * @return The read preset data
     * @throws IOException If an error occurs
     */
    private byte [] readVST3Preset (final InputStream in) throws IOException
    {
        final Vst3Preset preset = new Vst3Preset ();
        preset.read (in);

        // Still no idea what is used here by Reaper, could be retrieved from Reaper VST cache
        // file if necessary but is now (since around 6.4x) loaded anyway
        this.vstID = 0;

        this.dataType = CHUNK_OPAQUE;

        final byte [] data = preset.getData ();
        final Vst3ChunkInfo [] chunkInfos = preset.getChunkInfos ();
        // Note: dataSize is: data.length + 8 * chunkInfos.length;

        final ByteArrayOutputStream out = new ByteArrayOutputStream ();

        for (int i = 0; i < chunkInfos.length; i++)
        {
            StreamHelper.writeIntLittleEndian (out, (int) chunkInfos[i].size);
            StreamHelper.writeIntLittleEndian (out, i == 0 ? 0x01000000 : 0);

            final int offset = (int) (chunkInfos[i].offset - Vst3Preset.HEADER_SIZE);
            out.write (data, offset, (int) chunkInfos[i].size);
        }

        this.programName = "";
        this.presetName = "";

        return out.toByteArray ();
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
}
