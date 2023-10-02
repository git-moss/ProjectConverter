// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;


/**
 * The data of a CLAP chunk in the Reaper project file.
 *
 * @author Jürgen Moßgraber
 */
public class ClapChunkHandler extends DeviceChunkHandler
{
    /** {@inheritDoc} */
    @Override
    public void chunkToFile (final Chunk chunk, final OutputStream out) throws IOException
    {
        final Optional<Node> stateNode = chunk.getChildNode ("STATE");
        if (stateNode.isEmpty () || !(stateNode.get () instanceof final Chunk stateChunk))
            throw new IOException ("IDS_NOTIFY_NO_CLAP_STATE");

        for (final Node childNode: stateChunk.getChildNodes ())
            out.write (this.decoder.decode (childNode.getName ().trim ()));
    }


    /** {@inheritDoc} */
    @Override
    public void fileToChunk (final InputStream in, final Chunk chunk) throws IOException
    {
        final byte [] data = in.readAllBytes ();
        final Chunk stateChunk = new Chunk ();
        stateChunk.setName ("STATE");
        chunk.addChildNode (stateChunk);
        createLines (stateChunk, this.encoder.encode (data));
    }
}
