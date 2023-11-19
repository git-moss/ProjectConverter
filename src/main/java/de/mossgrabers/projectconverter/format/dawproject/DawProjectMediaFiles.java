// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.dawproject;

import de.mossgrabers.projectconverter.core.IMediaFiles;

import com.bitwig.dawproject.DawProject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * Access to additional dawproject media files.
 *
 * @author Jürgen Moßgraber
 */
public class DawProjectMediaFiles implements IMediaFiles
{
    private static final String PROJECT_FILE  = "project.xml";
    private static final String METADATA_FILE = "metadata.xml";

    private final File          sourceFile;
    private final File          parentPath;
    private final List<String>  mediaFiles    = new ArrayList<> ();


    /**
     * Constructor.
     *
     * @param sourceFile The dawproject source file
     * @throws IOException Could not open/read the ZIP source file
     */
    public DawProjectMediaFiles (final File sourceFile) throws IOException
    {
        this.sourceFile = sourceFile;
        this.parentPath = sourceFile.getParentFile ();

        try (final ZipFile zipFile = new ZipFile (this.sourceFile))
        {
            for (final ZipEntry zipEntry: Collections.list (zipFile.entries ()))
            {
                final String name = zipEntry.getName ();
                if (!PROJECT_FILE.equals (name) && !METADATA_FILE.equals (name))
                    this.mediaFiles.add (name);
            }
        }
    }


    /** {@inheritDoc} */
    @Override
    public InputStream stream (final String id) throws IOException
    {
        final File file = new File (this.parentPath, id);
        // Is the file external?
        if (file.exists ())
            return new FileInputStream (file);

        try
        {
            return DawProject.streamEmbedded (this.sourceFile, id);
        }
        catch (final NullPointerException ex)
        {
            throw new FileNotFoundException (this.sourceFile.getPath ());
        }
    }


    /** {@inheritDoc} */
    @Override
    public void add (final String id, final File mediaFile)
    {
        // Not used
    }


    /** {@inheritDoc} */
    @Override
    public List<String> getAll ()
    {
        return this.mediaFiles;
    }
}
