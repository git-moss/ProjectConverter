// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.dawproject;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IDestinationFormat;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import com.bitwig.dawproject.MetaData;
import com.bitwig.dawproject.Project;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


/**
 * The dawproject project destination.
 *
 * @author Jürgen Moßgraber
 */
public class DawProjectDestinationFormat extends AbstractCoreTask implements IDestinationFormat
{
    private static final String PROJECT_FILE  = "project.xml";
    private static final String METADATA_FILE = "metadata.xml";


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
    public boolean needsOverwrite (final String projectName, final File outputPath)
    {
        return getFile (projectName, outputPath).exists ();
    }


    /** {@inheritDoc} */
    @Override
    public void write (final DawProjectContainer dawProject, final File outputPath) throws IOException
    {
        final File outputFile = getFile (dawProject.getName (), outputPath);
        final Map<File, String> remap = new HashMap<> ();
        for (final Map.Entry<String, File> entry: dawProject.getMediaFiles ().getAll ().entrySet ())
            remap.put (entry.getValue (), entry.getKey ());
        this.save (dawProject.getProject (), dawProject.getMetadata (), remap, outputFile);
    }


    private static File getFile (final String projectName, final File outputPath)
    {
        return new File (outputPath, projectName + ".dawproject");
    }


    private void save (final Project project, final MetaData metadata, final Map<File, String> embeddedFiles, final File file) throws IOException
    {
        final String metadataXML = toXML (metadata);
        final String projectXML = toXML (project);

        if (this.notifier.isCancelled ())
            return;

        final ZipOutputStream zos = new ZipOutputStream (new FileOutputStream (file));

        addToZip (zos, METADATA_FILE, metadataXML.getBytes (StandardCharsets.UTF_8));
        addToZip (zos, PROJECT_FILE, projectXML.getBytes (StandardCharsets.UTF_8));

        for (final Map.Entry<File, String> entry: embeddedFiles.entrySet ())
        {
            if (this.notifier.isCancelled ())
                return;

            final String path = entry.getValue ();
            this.notifier.log ("IDS_NOTIFY_COMPRESSING_AUDIO_FILE", path);
            addToZip (zos, path, entry.getKey ());
        }

        zos.close ();
    }


    private static void addToZip (final ZipOutputStream zos, final String path, final byte [] data) throws IOException
    {
        final ZipEntry entry = new ZipEntry (path);
        zos.putNextEntry (entry);
        zos.write (data);
        zos.closeEntry ();
    }


    private static void addToZip (final ZipOutputStream zos, final String path, final File file) throws IOException
    {
        final ZipEntry entry = new ZipEntry (path);
        zos.putNextEntry (entry);

        try (FileInputStream fileInputStream = new FileInputStream (file))
        {
            final byte [] data = new byte [65536];
            int size = 0;
            while ((size = fileInputStream.read (data)) != -1)
                zos.write (data, 0, size);

            zos.flush ();
        }

        zos.closeEntry ();
    }


    private static JAXBContext createContext (final Class<? extends Object> cls) throws JAXBException
    {
        return JAXBContext.newInstance (cls);
    }


    private static String toXML (final Object object) throws IOException
    {
        try
        {
            final JAXBContext context = createContext (object.getClass ());
            final Marshaller marshaller = context.createMarshaller ();
            marshaller.setProperty (Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

            final var sw = new StringWriter ();
            marshaller.marshal (object, sw);

            return sw.toString ();
        }
        catch (final Exception ex)
        {
            throw new IOException (ex);
        }
    }
}
