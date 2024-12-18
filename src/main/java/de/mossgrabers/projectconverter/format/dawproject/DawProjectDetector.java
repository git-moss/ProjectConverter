// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.dawproject;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.tools.ui.panel.BoxPanel;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.stage.FileChooser.ExtensionFilter;


/**
 * Loads a dawproject as the source.
 *
 * @author Jürgen Moßgraber
 */
public class DawProjectDetector extends AbstractCoreTask implements ISourceFormat
{
    private static final ExtensionFilter EXTENSION_FILTER = new ExtensionFilter ("DawProject", "*.dawproject");


    /**
     * Constructor.
     *
     * @param notifier The notifier
     */
    public DawProjectDetector (final INotifier notifier)
    {
        super ("DawProject", notifier);
    }


    /** {@inheritDoc} */
    @Override
    public ExtensionFilter getExtensionFilter ()
    {
        return EXTENSION_FILTER;
    }


    /** {@inheritDoc} */
    @Override
    public javafx.scene.Node getEditPane ()
    {
        final BoxPanel panel = new BoxPanel (Orientation.VERTICAL);
        final Label label = panel.createLabel ("@IDS_SELECT_DAWPROJECT_FILE");
        label.setStyle ("-fx-font-style: italic;");
        panel.addComponent (label);
        return panel.getPane ();
    }


    /** {@inheritDoc} Note: Resource is closed in DawProjectContainer. */
    @SuppressWarnings("resource")
    @Override
    public DawProjectContainer read (final File sourceFile) throws IOException, ParseException
    {
        return new DawProjectContainer (sourceFile, new DawProjectMediaFiles (sourceFile));
    }
}
