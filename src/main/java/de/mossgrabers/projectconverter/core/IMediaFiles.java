// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;


/**
 * Interface for streaming media files between source to destination format. A media file can be an
 * audio file or a device state.
 *
 * @author Jürgen Moßgraber
 */
public interface IMediaFiles
{
    /**
     * Get an input stream to read a media file.
     *
     * @param id The ID of the media file
     * @return The input stream to read the media file from
     * @throws IOException An exception occurred during writing or the media file was not found
     */
    InputStream stream (String id) throws IOException;


    /**
     * Add a media file.
     *
     * @param id The ID of the media file
     * @param mediaFile The media file
     */
    void add (String id, File mediaFile);


    /**
     * Get the IDs of all media files.
     *
     * @return All media files
     */
    List<String> getAll ();
}
