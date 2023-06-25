// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.dawproject;

import de.mossgrabers.projectconverter.core.IMediaFiles;

import com.bitwig.dawproject.DawProject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;


/**
 * Access to additional dawproject media files.
 *
 * @author Jürgen Moßgraber
 */
public class DawProjectMediaFiles implements IMediaFiles
{
    private final File sourceFile;
    private final File parentPath;


    /**
     * COnstructor.
     *
     * @param sourceFile The dawproject source file
     */
    public DawProjectMediaFiles (final File sourceFile)
    {
        this.sourceFile = sourceFile;
        this.parentPath = sourceFile.getParentFile ();
    }


    /** {@inheritDoc} */
    @Override
    public InputStream stream (final String id) throws IOException
    {
        final File file = new File (this.parentPath, id);
        // Is the file external?
        if (file.exists ())
            return new FileInputStream (file);

        return DawProject.streamEmbedded (this.sourceFile, id);
    }


    /** {@inheritDoc} */
    @Override
    public void add (final String id, final File mediaFile)
    {
        // Not used
    }


    /** {@inheritDoc} */
    @Override
    public Map<String, File> getAll ()
    {
        // Not used
        return Collections.emptyMap ();
    }
}
