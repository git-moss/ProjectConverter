// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;


/**
 * The data of a device chunk in the Reaper project file.
 *
 * @author Jürgen Moßgraber
 */
public abstract class DeviceChunkHandler
{
    protected final Decoder decoder = Base64.getDecoder ();
    protected final Encoder encoder = Base64.getEncoder ();


    /**
     * Constructor.
     */
    protected DeviceChunkHandler ()
    {
        // Intentionally empty
    }


    /**
     * Parses the content of a device chunk and stores it to a preset file.
     *
     * @param chunk The chunk to parse
     * @param out Where to write the output
     * @throws IOException If an error occurs
     */
    public abstract void chunkToFile (final Chunk chunk, final OutputStream out) throws IOException;


    /**
     * Creates a device chunk from a preset file.
     * 
     * @param in Where to read the preset from
     * @param chunk The chunk to create
     * @throws IOException If an error occurs
     */
    public abstract void fileToChunk (final InputStream in, final Chunk chunk) throws IOException;


    /**
     * Creates Base64 encoded text as child nodes of the given parent chunk.
     *
     * @param parentChunk Where to add the Base64 encoded lines
     * @param lineData The data to encode
     */
    protected static void createLines (final Chunk parentChunk, final byte [] lineData)
    {
        final String line = new String (lineData, StandardCharsets.US_ASCII);
        final int length = line.length ();
        for (int i = 0; i < length; i += 128)
        {
            final Node lineNode = new Node ();
            lineNode.setName (line.substring (i, Math.min (i + 128, length)));
            parentChunk.addChildNode (lineNode);
        }
    }
}
