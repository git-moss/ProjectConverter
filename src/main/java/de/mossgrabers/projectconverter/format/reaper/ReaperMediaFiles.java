// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.mossgrabers.projectconverter.core.IMediaFiles;


/**
 * Access to additional Reaper media files. Audio and plug-in states.
 *
 * @author Jürgen Moßgraber
 */
public class ReaperMediaFiles implements IMediaFiles
{
    private final Map<String, File> mediaFiles = new HashMap<> ();


    /** {@inheritDoc} */
    @Override
    public InputStream stream (final String id) throws IOException
    {
        return new FileInputStream (this.mediaFiles.get (id));
    }


    /** {@inheritDoc} */
    @Override
    public void add (final String id, final File mediaFile)
    {
        this.mediaFiles.put (id, mediaFile);
    }


    /** {@inheritDoc} */
    @Override
    public List<String> getAll ()
    {
        return new ArrayList<> (this.mediaFiles.keySet ());
    }


    /** {@inheritDoc} */
    @Override
    public void close () throws IOException
    {
        // Nothing to clean up
    }
}
