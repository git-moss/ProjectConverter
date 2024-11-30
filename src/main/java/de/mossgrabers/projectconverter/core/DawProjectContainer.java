// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import com.bitwig.dawproject.DawProject;
import com.bitwig.dawproject.MetaData;
import com.bitwig.dawproject.Project;

import de.mossgrabers.tools.FileUtils;


/**
 * A container for all DAWproject components: metadata, project and embedded files.
 *
 * @author Jürgen Moßgraber
 */
public class DawProjectContainer implements Closeable
{
    private final String      name;
    private final MetaData    metadata;
    private final Project     project;
    private final IMediaFiles mediaFiles;


    /**
     * Constructor.
     *
     * @param name The name of the project
     * @param mediaFiles Access to additional media files
     */
    public DawProjectContainer (final String name, final IMediaFiles mediaFiles)
    {
        this.name = name;
        this.mediaFiles = mediaFiles;

        this.metadata = new MetaData ();
        this.project = new Project ();
    }


    /**
     * Constructor.
     *
     * @param dawProjectSourceFile A dawproject to load
     * @param mediaFiles Access to additional media files
     * @throws IOException Could not load the file
     */
    public DawProjectContainer (final File dawProjectSourceFile, final IMediaFiles mediaFiles) throws IOException
    {
        this.name = FileUtils.getNameWithoutType (dawProjectSourceFile);
        this.mediaFiles = mediaFiles;
        this.metadata = DawProject.loadMetadata (dawProjectSourceFile);
        this.project = DawProject.loadProject (dawProjectSourceFile);
    }


    /**
     * Get the name of the project.
     *
     * @return The projects' name
     */
    public String getName ()
    {
        return this.name;
    }


    /**
     * Get the metadata.
     *
     * @return The metadata
     */
    public MetaData getMetadata ()
    {
        return this.metadata;
    }


    /**
     * Get the project.
     *
     * @return The project
     */
    public Project getProject ()
    {
        return this.project;
    }


    /**
     * Get the accessor to the media files.
     *
     * @return The media files
     */
    public IMediaFiles getMediaFiles ()
    {
        return this.mediaFiles;
    }


    /**
     * Get the beats per second of the timeline. Currently, static but should calculated from the
     * timeline position in the future.
     *
     * @return The beats per second
     */
    public double getTempo ()
    {
        return this.project.transport.tempo.value.doubleValue ();
    }


    /** {@inheritDoc} */
    @Override
    public void close () throws IOException
    {
        this.mediaFiles.close ();
    }
}
