// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

import de.mossgrabers.projectconverter.INotifier;

import com.bitwig.dawproject.DawProject;

import javafx.concurrent.Task;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;


/**
 * The task to run the actual conversion process.
 *
 * @author Jürgen Moßgraber
 */
public class ConversionTask extends Task<Void>
{
    private final File               sourceFile;
    private final ISourceFormat      sourceFormat;
    private final IDestinationFormat destinationFormat;
    private final INotifier          notifier;
    private final File               outputPath;


    /**
     * Constructor.
     *
     * @param sourceFile The source file to convert
     * @param outputPath The output path in which to write the destination file
     * @param sourceFormat The format of the source file
     * @param destinationFormat The destination format
     * @param notifier Where to log to
     */
    public ConversionTask (final File sourceFile, final File outputPath, final ISourceFormat sourceFormat, final IDestinationFormat destinationFormat, final INotifier notifier)
    {
        this.sourceFile = sourceFile;
        this.outputPath = outputPath;
        this.sourceFormat = sourceFormat;
        this.destinationFormat = destinationFormat;
        this.notifier = notifier;
    }


    /** {@inheritDoc} */
    @Override
    protected Void call ()
    {
        try
        {
            // Parse the project file
            this.notifier.log ("IDS_NOTIFY_PARSING_FILE", this.sourceFile.getAbsolutePath ());

            final DawProjectContainer dawProject;
            try
            {
                dawProject = this.sourceFormat.read (this.sourceFile);
            }
            catch (final IOException | ParseException ex)
            {
                this.notifier.logError ("IDS_NOTIFY_COULD_NOT_READ", ex);
                return null;
            }

            if (this.waitForDelivery ())
                return null;

            try
            {
                this.notifier.log ("IDS_NOTIFY_VALIDATING_FILE");
                DawProject.validate (dawProject.getProject ());
            }
            catch (final IOException ex)
            {
                this.notifier.logError ("IDS_NOTIFY_COULD_NOT_VALIDATE_PROJECT", ex);
            }

            if (this.waitForDelivery ())
                return null;

            // Write output file(s)
            this.notifier.log ("IDS_NOTIFY_WRITING_FILE", this.outputPath.getAbsolutePath ());

            try
            {
                this.destinationFormat.write (dawProject, this.outputPath);
            }
            catch (final IOException ex)
            {
                this.notifier.logError ("IDS_NOTIFY_COULD_NOT_WRITE_FILE", ex);
                return null;
            }
        }
        catch (final Exception ex)
        {
            this.notifier.logError (ex);
        }

        final boolean cancelled = this.isCancelled ();
        this.notifier.log (cancelled ? "IDS_NOTIFY_CANCELED" : "IDS_NOTIFY_CONVERSION_FINISHED");
        return null;
    }


    /**
     * Wait a bit.
     *
     * @return The thread was cancelled if true
     */
    protected boolean waitForDelivery ()
    {
        try
        {
            Thread.sleep (10);
        }
        catch (final InterruptedException ex)
        {
            if (this.isCancelled ())
                return true;
            Thread.currentThread ().interrupt ();
        }
        return false;
    }
}
