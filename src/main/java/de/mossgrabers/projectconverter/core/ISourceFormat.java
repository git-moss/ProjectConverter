// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;


/**
 * The interface to a project source.
 *
 * @author Jürgen Moßgraber
 */
public interface ISourceFormat extends ICoreTask
{
    /**
     * Read and convert the source file project into a dawproject.
     *
     * @param sourceFile The source project to load
     * @return The read, parsed and converted dawproject
     * @throws IOException Could not read the file
     * @throws ParseException Could not parse Reaper project file
     */
    DawProjectContainer read (File sourceFile) throws IOException, ParseException;


    /**
     * Get the extension filter to use for selecting matching files.
     *
     * @return The extension filter
     */
    FileChooser.ExtensionFilter getExtensionFilter ();
}
