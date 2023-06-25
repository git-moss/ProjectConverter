// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.dawproject;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IDestinationFormat;

import com.bitwig.dawproject.DawProject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


/**
 * The dawproject project destination.
 *
 * @author Jürgen Moßgraber
 */
public class DawProjectDestinationFormat extends AbstractCoreTask implements IDestinationFormat
{
    /**
     * Constructor.
     *
     * @param notifier The notifier for error messages
     */
    public DawProjectDestinationFormat (final INotifier notifier)
    {
        super ("DawProject", notifier);
    }


    /** {@inheritDoc} */
    @Override
    public boolean needsOverwrite (final DawProjectContainer dawProject, final File outputPath)
    {
        return getFile (dawProject, outputPath).exists ();
    }


    /** {@inheritDoc} */
    @Override
    public void write (final DawProjectContainer dawProject, final File outputPath) throws IOException
    {
        final File outputFile = getFile (dawProject, outputPath);
        final Map<File, String> remap = new HashMap<> ();
        for (final Map.Entry<String, File> entry: dawProject.getMediaFiles ().getAll ().entrySet ())
            remap.put (entry.getValue (), entry.getKey ());
        DawProject.save (dawProject.getProject (), dawProject.getMetadata (), remap, outputFile);
    }


    private static File getFile (final DawProjectContainer dawProject, final File outputPath)
    {
        return new File (outputPath, dawProject.getName () + ".dawproject");
    }
}
