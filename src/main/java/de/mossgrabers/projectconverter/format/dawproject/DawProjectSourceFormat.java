// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.dawproject;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.ISourceFormat;

import javafx.stage.FileChooser.ExtensionFilter;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;


/**
 * Loads a dawproject as the source.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public class DawProjectSourceFormat extends AbstractCoreTask implements ISourceFormat
{
    private static final ExtensionFilter EXTENSION_FILTER = new ExtensionFilter ("DawProject", "*.dawproject");


    /**
     * Constructor.
     *
     * @param notifier The notifier
     */
    public DawProjectSourceFormat (final INotifier notifier)
    {
        super ("DawProject", notifier);
    }


    /** {@inheritDoc} */
    @Override
    public ExtensionFilter getExtensionFilter ()
    {
        return EXTENSION_FILTER;
    }


    /** {@inheritDoc} */
    @Override
    public DawProjectContainer read (final File sourceFile) throws IOException, ParseException
    {
        return new DawProjectContainer (sourceFile, new DawProjectMediaFiles (sourceFile));
    }
}
