// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.bento;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.Referenceable;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.projectconverter.core.StreamUtils;
import de.mossgrabers.tools.FileUtils;
import de.mossgrabers.tools.XMLUtils;
import de.mossgrabers.tools.ui.Functions;
import javafx.stage.FileChooser.ExtensionFilter;


/**
 * Converts a 1010music Bento project file into a dawproject structure. Needs to be state-less.
 *
 * @author Jürgen Moßgraber
 */
public class BentoDetector extends AbstractCoreTask implements ISourceFormat
{
    private static final String          ERR_BAD_METADATA_FILE = "IDS_NOTIFY_ERR_BAD_METADATA_FILE";
    private static final ExtensionFilter EXTENSION_FILTER      = new ExtensionFilter ("Bento Project", "*.xml");


    /**
     * Constructor.
     *
     * @param notifier The notifier
     */
    public BentoDetector (final INotifier notifier)
    {
        super ("Bento", notifier);
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
        if (!"project.xml".equals (sourceFile.getName ()))
            return null;

        Referenceable.setAutoID (true);

        final String basePath = sourceFile.getParentFile ().getParentFile ().getParent ();
        final Document document = this.loadXmlDocument (sourceFile);
        return document == null ? null : this.parseXMLFile (sourceFile, basePath, document);
    }


    private Document loadXmlDocument (final File sourceFile)
    {
        try (final FileInputStream in = new FileInputStream (sourceFile))
        {
            final String content = StreamUtils.readUTF8 (in);
            return XMLUtils.parseDocument (new InputSource (new StringReader (content)));
        }
        catch (final IOException | SAXException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_ERR_COULD_NOT_READ_PROJECT", ex);
            return null;
        }
    }


    /**
     * Load and parse the XML description file.
     *
     * @param sourceFile The preset or library file
     * @param basePath The parent folder, in case of a library the relative folder in the ZIP
     *            directory structure
     * @param document The XML document to parse
     * @throws IOException Could not parse the XML file
     */
    private DawProjectContainer parseXMLFile (final File sourceFile, final String basePath, final Document document) throws IOException
    {
        final Element top = document.getDocumentElement ();
        if (!Music1010Tag.ROOT.equals (top.getNodeName ()))
        {
            this.notifier.logError (ERR_BAD_METADATA_FILE);
            return null;
        }

        final Element sessionElement = XMLUtils.getChildElementByName (top, Music1010Tag.SESSION);
        if (sessionElement == null)
        {
            this.notifier.logError (ERR_BAD_METADATA_FILE);
            return null;
        }
        final String versionAttribute = sessionElement.getAttribute (Music1010Tag.ATTR_VERSION);
        if (versionAttribute == null || !"1".equals (versionAttribute))
        {
            this.notifier.logError ("IDS_1010_MUSIC_WRONG_VERSION", versionAttribute == null ? "'none'" : versionAttribute);
            return null;
        }

        final List<Element> trackElements = XMLUtils.getChildElementsByName (sessionElement, Music1010Tag.TRACK);
        if (trackElements.isEmpty ())
            return null;

        try (final DawProjectContainer dawProject = new DawProjectContainer (FileUtils.getNameWithoutType (sourceFile), null))
        {
            final Project project = dawProject.getProject ();

            project.application.name = "1010music Bento (converted with " + Functions.getMessage ("TITLE") + ")";
            project.application.version = versionAttribute;

            for (final Element trackElement: trackElements)
            {
                final String trackType = trackElement.getAttribute (Music1010Tag.ATTR_TYPE);
                if (!"multisamtrack".equals (trackType))
                    continue;

                final List<Element> assetElements = new ArrayList<> ();
                Element instElement = null;

                for (final Element cellElement: XMLUtils.getChildElementsByName (trackElement, Music1010Tag.CELL))
                    switch (cellElement.getAttribute (Music1010Tag.ATTR_TYPE))
                    {
                        case "saminst":
                            instElement = cellElement;
                            break;
                        case "samasst":
                            assetElements.add (cellElement);
                            break;
                        default:
                            break;
                    }
                if (instElement == null || assetElements.isEmpty ())
                    return null;

            }

            // convertMetadata (dawProject.getMetadata (), rootChunk);
            //
            // convertArrangement (project, rootChunk, beatsAndTime);
            // beatsAndTime.destinationIsBeats = false;
            // TimeUtils.setTimeUnit (project.arrangement.lanes, beatsAndTime.destinationIsBeats);
            //
            // final FolderStructure structure = new FolderStructure ();
            // structure.folderTracks = new ArrayList<> ();
            //
            // convertTransport (project, rootChunk);
            //
            // final Map<String, File> mediaFilesMap = new HashMap<> ();
            // beatsAndTime.tempoEnvelope = this.convertMaster (dawProject, mediaFilesMap,
            // rootChunk, structure, beatsAndTime);
            //
            // this.convertTracks (dawProject, mediaFilesMap, rootChunk, sourceFile.getParentFile
            // (), structure, beatsAndTime);
            // convertMarkers (dawProject, rootChunk, beatsAndTime);

            // try (final IMediaFiles mediaFiles = dawProject.getMediaFiles ())
            // {
            // for (final Map.Entry<String, File> entry: mediaFilesMap.entrySet ())
            // mediaFiles.add (entry.getKey (), entry.getValue ());
            // }
            //
            // project.structure.addAll (structure.folderTracks);

            return dawProject;
        }
    }
}
