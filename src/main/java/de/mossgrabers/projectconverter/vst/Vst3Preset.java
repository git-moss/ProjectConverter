// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.vst;

import de.mossgrabers.projectconverter.utils.StreamHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;


/**
 * Supports reading and writing of VST 3 preset files.
 *
 * @author Jürgen Moßgraber
 */
public class Vst3Preset
{
    /** The size of the VST 3 preset header. */
    public static final int     HEADER_SIZE = 48;

    private static final String HEADER_ID   = "VST3";
    private static final String LIST_ID     = "List";


    /** Information about one chunk. */
    public class Vst3ChunkInfo
    {
        /** The ID of the chunk. */
        public String id;
        /** Offset is from the beginning of the file. */
        public long   offset;
        /** The size of the chunk. */
        public long   size;
    }


    /** The IDs of the two default chunks. */
    private static final String [] CHUNK_IDS =
    {
        "Comp",
        "Cont"
    };

    private int                    version;
    private String                 classID;
    private byte []                data;
    private int                    numberOfChunks;
    private Vst3ChunkInfo []       chunkInfos;


    /**
     * Get the version.
     *
     * @return The version
     */
    public int getVersion ()
    {
        return this.version;
    }


    /**
     * Get the unique class ID of the plugin to which the preset belongs.
     *
     * @return The ID
     */
    public String getClassID ()
    {
        return this.classID;
    }


    /**
     * Get the data block.
     *
     * @return The data
     */
    public byte [] getData ()
    {
        return this.data;
    }


    /**
     * Get the chunk information which describes the structure of the data block.
     *
     * @return The chunk information
     */
    public Vst3ChunkInfo [] getChunkInfos ()
    {
        return this.chunkInfos;
    }


    /**
     * Read a preset from an input stream.
     *
     * @param input The input stream to read from
     * @throws IOException Error during read
     */
    public void read (final InputStream input) throws IOException
    {
        if (!HEADER_ID.equals (StreamHelper.readString (input, 4)))
            throw new IOException ("Not a VST3 preset file.");

        this.version = StreamHelper.readIntLittleEndian (input);
        this.classID = StreamHelper.readString (input, 32);

        final long offsetToChunkList = StreamHelper.readLongLittleEndian (input);

        // Should presets larger 4GB supported?
        final int dataSize = (int) offsetToChunkList - HEADER_SIZE;

        this.data = input.readNBytes (dataSize);

        if (!LIST_ID.equals (StreamHelper.readString (input, 4)))
            throw new IOException ("List chunk not found.");

        this.numberOfChunks = StreamHelper.readIntLittleEndian (input);
        this.chunkInfos = new Vst3ChunkInfo [this.numberOfChunks];
        for (int i = 0; i < this.numberOfChunks; i++)
        {
            this.chunkInfos[i] = new Vst3ChunkInfo ();
            this.chunkInfos[i].id = StreamHelper.readString (input, 4);
            this.chunkInfos[i].offset = StreamHelper.readLongLittleEndian (input);
            this.chunkInfos[i].size = StreamHelper.readLongLittleEndian (input);
        }
    }


    /**
     * Writes a VST 3 preset with 1 or 2 default chunks.
     *
     * @param output Where to write the preset to
     * @param classID The unique class ID of the plugin to which the preset belongs
     * @param chunks The content of the chunks
     * @throws IOException Could not write
     */
    public void write (final OutputStream output, final String classID, final List<byte []> chunks) throws IOException
    {
        StreamHelper.writeString (output, HEADER_ID);

        // Version
        StreamHelper.writeIntLittleEndian (output, 1);

        // ASCII-encoded class id - 32 bytes
        StreamHelper.writeString (output, classID);

        this.numberOfChunks = chunks.size ();
        this.chunkInfos = new Vst3ChunkInfo [this.numberOfChunks];

        // Calculate the chunk list
        int dataSize = HEADER_SIZE;
        for (int i = 0; i < this.numberOfChunks; i++)
        {
            this.chunkInfos[i] = new Vst3ChunkInfo ();
            this.chunkInfos[i].id = CHUNK_IDS[i];
            this.chunkInfos[i].offset = dataSize;
            this.chunkInfos[i].size = chunks.get (i).length;
            dataSize += this.chunkInfos[i].size;
        }

        // THe offset to the chunk list
        StreamHelper.writeIntLittleEndian (output, dataSize);
        StreamHelper.writeIntLittleEndian (output, 0);

        // Write the data area
        for (int i = 0; i < this.numberOfChunks; i++)
            output.write (chunks.get (i));

        // Write the list chunk
        StreamHelper.writeString (output, LIST_ID);

        // Number of chunks
        StreamHelper.writeIntLittleEndian (output, this.numberOfChunks);

        for (int i = 0; i < this.numberOfChunks; i++)
        {
            StreamHelper.writeString (output, this.chunkInfos[i].id);

            // Note: only chunks up to 4GB supported
            StreamHelper.writeIntLittleEndian (output, (int) this.chunkInfos[i].offset);
            StreamHelper.writeIntLittleEndian (output, 0);
            StreamHelper.writeIntLittleEndian (output, (int) this.chunkInfos[i].size);
            StreamHelper.writeIntLittleEndian (output, 0);
        }
    }
}
