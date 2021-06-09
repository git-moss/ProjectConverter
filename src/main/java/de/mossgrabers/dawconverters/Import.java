/**
 * 
 */
package de.mossgrabers.dawconverters;

import de.mossgrabers.dawconverters.reaper.Chunk;
import de.mossgrabers.dawconverters.reaper.ReaperConverter;
import de.mossgrabers.dawconverters.reaper.ReaperProject;

import com.bitwig.dawproject.DawProject;
import com.bitwig.dawproject.Metadata;
import com.bitwig.dawproject.Project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * @author mos
 *
 */
public final class Import
{

    /**
     * @param args
     */
    public static void main (String [] args)
    {
        if (args.length != 1)
        {
            System.out.println ("Add the project file (with folder) as a parameter.");
            return;
        }

        final String fileName = args[0];

        System.out.println ("Reading: " + fileName);

        try
        {
            final List<String> lines = Files.readAllLines (Paths.get (fileName), StandardCharsets.UTF_8);

            final Chunk rootChunk = ReaperProject.parse (lines);

            Project project = ReaperConverter.createProject (rootChunk);
            saveDawProject (project);

            final String projectFileContent = ReaperProject.format (rootChunk);

            System.out.println (projectFileContent);

        }
        catch (IOException ex)
        {
            ex.printStackTrace ();
        }
        catch (ParseException ex)
        {
            ex.printStackTrace ();
        }
    }


    public static void saveDawProject (final Project project) throws IOException
    {
        // TODO fill metadata
        final Metadata metadata = new Metadata ();
        // TODO fill embedded files
        final Map<File, String> embeddedFiles = new HashMap<> ();

        // TODO set name
        DawProject.save (project, metadata, embeddedFiles, new File ("target/test.dawproject"));

        // TODO remove
        DawProject.saveXML (project, new File ("target/test.dawproject.xml"));
    }
}
