// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.dawconverters;

import de.mossgrabers.dawconverters.reaper.DawProjectToReaperConverter;
import de.mossgrabers.dawconverters.reaper.ReaperToDawProjectConverter;
import de.mossgrabers.dawconverters.reaper.project.Chunk;
import de.mossgrabers.dawconverters.reaper.project.ReaperProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.util.List;


/**
 * The main class for converting a Reaper project file to a dawproject file from the command line.
 * Converts the given Reaper project file and stores the result in the same directory as the source
 * file.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public final class ConvertProject
{
    /**
     * The main function.
     *
     * @param args One parameter, the Reaper project file to convert
     */
    public static void main (final String [] args)
    {
        if (args.length == 0)
        {
            System.out.println ("Add the project file (with folder) as a parameter.");
            return;
        }

        final String absoluteFileName = args[0];

        final boolean isReaperProject = absoluteFileName.toLowerCase ().endsWith (".rpp");

        System.out.println ("Reading: " + absoluteFileName);

        try
        {
            final File inputFile = new File (absoluteFileName);
            final String fileName = inputFile.getName ();
            final int pos = fileName.lastIndexOf (".");
            final String outputFileName = pos > 0 ? fileName.substring (0, pos) : fileName;
            final String ending = isReaperProject ? ".dawproject" : ".rpp";
            final File sourcePath = inputFile.getParentFile ();
            final File outputFile = new File (sourcePath, outputFileName + ending);

            System.out.println ("Writing: " + outputFile.getAbsolutePath ());
            if (isReaperProject)
            {
                final List<String> lines = Files.readAllLines (inputFile.toPath (), StandardCharsets.UTF_8);
                final Chunk rootChunk = ReaperProject.parse (lines);
                new ReaperToDawProjectConverter (sourcePath, rootChunk).saveProject (outputFile);
            }
            else
            {
                new DawProjectToReaperConverter (inputFile).saveProject (outputFile);
            }
            System.out.println ("Done.");
        }
        catch (final IOException | ParseException ex)
        {
            ex.printStackTrace ();
        }
    }
}
