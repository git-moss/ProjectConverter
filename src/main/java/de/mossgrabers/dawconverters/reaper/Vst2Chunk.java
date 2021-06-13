package de.mossgrabers.dawconverters.reaper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;


public class Vst2Chunk
{
    /** The VST ID. */
    private int     vstID;

    /** 0xFEED5EED or something like that (not important). */
    private int     magicNumber;

    /** Number of input channels for routing. */
    private int     numInputs;

    /** Number of output channels for routing. */
    private int     numOutputs;

    /** Size of the VST Data. */
    private int     vstDataSize;

    /** The VST data. */
    private byte [] vstData;

    /** Null terminated preset name. */
    private String  presetName;


    public Vst2Chunk ()
    {

    }


    public void read (final InputStream in) throws IOException
    {
        this.vstID = this.readIntLittleEndian (in);
        this.magicNumber = this.readIntLittleEndian (in);
        this.numInputs = this.readIntLittleEndian (in);

        // A bit mask array of 8 bytes each, for the input routings
        // bit 0 = input channel 1 is set; bit 63 = input channel 64 is set; etc.
        // The length of the above array is defined by the number of inputs
        // Total size is 8*NumInputs bytes
        in.skipNBytes (8 * this.numInputs);

        this.numOutputs = this.readIntLittleEndian (in);

        // Again bit mask array 8 bytes each for outputs this time...
        in.skipNBytes (8 * this.numOutputs);

        // Size of the VST Data
        this.vstDataSize = this.readIntLittleEndian (in);

        // Nobody knows what these are about...
        in.skipNBytes (8);

        this.vstData = in.readNBytes (this.vstDataSize);

        // Footer
        //

        final StringBuilder name = new StringBuilder ();
        int value;
        while ((value = in.read ()) != 0)
            name.append ((char) value);
        this.presetName = name.toString ();

        // Ignore the rest of unknown bytes...
        in.skipNBytes (in.available ());
    }


    public void writePreset (final OutputStream out) throws IOException
    {
        // Chunk Magic
        out.write (new byte []
        {
            'C',
            'c',
            'n',
            'K'
        });

        // Size of this chunk, excluding magic + byteSize
        final int chunkSize = 4 + 4 + 4 + 4 + 4 + 28 + this.vstDataSize;
        this.writeIntBigEndian (out, chunkSize);

        // 'FxCk' (regular) or 'FPCh' (opaque chunk)
        out.write (new byte []
        {
            'F',
            'x',
            'C',
            'K'
        });

        // Format version (currently 1)
        this.writeIntBigEndian (out, 1);

        // FX unique ID
        this.writeIntBigEndian (out, this.vstID);

        // FX version - TODO
        this.writeIntBigEndian (out, 180);

        // Number of parameters - TODO
        this.writeIntBigEndian (out, 1);

        // program name (null-terminated ASCII string)
        final String name = this.presetName.length () < 28 ? this.presetName : this.presetName.substring (0, 27);
        final byte [] bytes = name.getBytes (StandardCharsets.US_ASCII);
        out.write (bytes);
        for (int i = 0; i < 28 - bytes.length; i++)
            out.write (0);

        out.write (this.vstData);
    }


    public int getVstID ()
    {
        return this.vstID;
    }


    public int getMagicNumber ()
    {
        return this.magicNumber;
    }


    public int getNumInputs ()
    {
        return this.numInputs;
    }


    public int getNumOutputs ()
    {
        return this.numOutputs;
    }


    public int getVstDataSize ()
    {
        return this.vstDataSize;
    }


    public byte [] getVstData ()
    {
        return this.vstData;
    }


    public String getPresetName ()
    {
        return this.presetName;
    }


    private final int readIntLittleEndian (final InputStream input) throws IOException
    {
        final byte [] w = input.readNBytes (4);
        return w[3] << 24 | (w[2] & 0xff) << 16 | (w[1] & 0xff) << 8 | w[0] & 0xff;
    }


    private final void writeIntBigEndian (final OutputStream output, final int value) throws IOException
    {
        output.write (value >> 24 & 0xff);
        output.write (value >> 16 & 0xff);
        output.write (value >> 8 & 0xff);
        output.write (value & 0xff);
    }
}
