// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

import java.io.File;
import java.io.IOException;


/**
 * The interface to a project destination.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public interface IDestinationFormat extends ICoreTask
{
    /**
     * Write the destination project file(s).
     *
     * @param dawProject The dawproject to store
     * @param outputPath The path in which to store the output file(s)
     * @throws IOException Could not write the file(s)
     */
    void write (DawProjectContainer dawProject, File outputPath) throws IOException;


    /**
     * Check if the file(s) already exist and therefore need an overwrite confirmation.
     *
     * @param dawProject The dawproject to store
     * @param outputPath The path in which to store the output file(s)
     * @return True if files exist
     */
    boolean needsOverwrite (DawProjectContainer dawProject, File outputPath);
}
