/**
 *
 */
package de.mossgrabers.dawconverters;

import de.mossgrabers.dawconverters.reaper.Chunk;
import de.mossgrabers.dawconverters.reaper.ReaperConverter;
import de.mossgrabers.dawconverters.reaper.ReaperProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.List;


/**
 * @author mos
 *
 */
public final class Import
{

    /**
     * @param args
     */
    public static void main (final String [] args)
    {
        if (args.length != 1)
        {
            System.out.println ("Add the project file (with folder) as a parameter.");
            return;
        }

        final String absoluteFileName = args[0];

        System.out.println ("Reading: " + absoluteFileName);

        try
        {
            final Path inputFile = Paths.get (absoluteFileName);
            final List<String> lines = Files.readAllLines (inputFile, StandardCharsets.UTF_8);

            final Chunk rootChunk = ReaperProject.parse (lines);

            final ReaperConverter reaperConverter = new ReaperConverter (rootChunk);

            final String fileName = inputFile.toFile ().getName ();
            final int pos = fileName.lastIndexOf (".");
            final String outputFileName = pos > 0 ? fileName.substring (0, pos) : fileName;

            final File outputFile = new File (inputFile.getParent ().toFile (), outputFileName + ".dawproject");
            System.out.println ("Writing: " + outputFile.getAbsolutePath ());
            reaperConverter.saveDawProject (outputFile);
        }
        catch (final IOException ex)
        {
            ex.printStackTrace ();
        }
        catch (final ParseException ex)
        {
            ex.printStackTrace ();
        }
    }
}
