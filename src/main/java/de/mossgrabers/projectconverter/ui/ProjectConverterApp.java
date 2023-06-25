// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2019-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.ui;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IDestinationFormat;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.projectconverter.format.dawproject.DawProjectDestinationFormat;
import de.mossgrabers.projectconverter.format.dawproject.DawProjectSourceFormat;
import de.mossgrabers.projectconverter.format.reaper.ReaperDestinationFormat;
import de.mossgrabers.projectconverter.format.reaper.ReaperSourceFormat;
import de.mossgrabers.tools.ui.AbstractFrame;
import de.mossgrabers.tools.ui.DefaultApplication;
import de.mossgrabers.tools.ui.EndApplicationException;
import de.mossgrabers.tools.ui.Functions;
import de.mossgrabers.tools.ui.control.LoggerBox;
import de.mossgrabers.tools.ui.control.TitledSeparator;
import de.mossgrabers.tools.ui.panel.BasePanel;
import de.mossgrabers.tools.ui.panel.BoxPanel;
import de.mossgrabers.tools.ui.panel.ButtonPanel;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.Optional;


/**
 * The project converter application.
 *
 * @author Jürgen Moßgraber
 */
public class ProjectConverterApp extends AbstractFrame implements INotifier
{
    private static final String         ENABLE_DARK_MODE = "EnableDarkMode";
    private static final String         DESTINATION_PATH = "DestinationPath";
    private static final String         DESTINATION_TYPE = "DestinationType";
    private static final String         SOURCE_PATH      = "SourcePath";
    private static final String         SOURCE_TYPE      = "SourceType";

    private final ISourceFormat []      sourceFormats;
    private final IDestinationFormat [] destinationFormats;
    private final ExtensionFilter []    sourceExtensionFilters;

    private BorderPane                  mainPane;
    private TextField                   sourceFileField;
    private TextField                   destinationPathField;
    private File                        sourceFile;
    private File                        outputPath;
    private CheckBox                    enableDarkMode;
    private TabPane                     sourceTabPane;
    private TabPane                     destinationTabPane;
    private final LoggerBox             loggingArea      = new LoggerBox ();


    /**
     * Main-method.
     *
     * @param args The startup arguments
     */
    public static void main (final String [] args)
    {
        Application.launch (DefaultApplication.class, ProjectConverterApp.class.getName ());
    }


    /**
     * Constructor.
     *
     * @throws EndApplicationException Startup crash
     */
    public ProjectConverterApp () throws EndApplicationException
    {
        super ("de/mossgrabers/projectconverter", 800, 600);

        this.sourceFormats = new ISourceFormat []
        {
            new DawProjectSourceFormat (this),
            new ReaperSourceFormat (this)
        };

        this.sourceExtensionFilters = new ExtensionFilter [this.sourceFormats.length + 1];
        this.sourceExtensionFilters[0] = new ExtensionFilter ("All Project Files", "*.*");
        for (int i = 0; i < this.sourceFormats.length; i++)
            this.sourceExtensionFilters[i + 1] = this.sourceFormats[i].getExtensionFilter ();

        this.destinationFormats = new IDestinationFormat []
        {
            new DawProjectDestinationFormat (this),
            new ReaperDestinationFormat (this)
        };
    }


    /** {@inheritDoc} */
    @Override
    public void initialise (final Stage stage, final Optional<String> baseTitleOptional) throws EndApplicationException
    {
        super.initialise (stage, baseTitleOptional, true, true, true);

        // The main button panel
        final ButtonPanel buttonPanel = new ButtonPanel (Orientation.VERTICAL);
        final Button convertButton = setupButton (buttonPanel, "Convert", "@IDS_MAIN_CONVERT");
        convertButton.setOnAction (event -> this.execute ());

        final ButtonPanel optionsPanel = new ButtonPanel (Orientation.VERTICAL);
        this.enableDarkMode = optionsPanel.createCheckBox ("@IDS_MAIN_ENABLE_DARK_MODE", "@IDS_MAIN_ENABLE_DARK_MODE_TOOLTIP");
        this.enableDarkMode.selectedProperty ().addListener ( (obs, wasSelected, isSelected) -> this.setDarkMode (isSelected.booleanValue ()));

        final BorderPane buttonPane = new BorderPane ();
        buttonPane.setTop (buttonPanel.getPane ());
        buttonPane.setBottom (optionsPanel.getPane ());

        // Source pane
        final BorderPane sourcePane = new BorderPane ();

        this.sourceFileField = new TextField ();
        final BorderPane sourceFolderPanel = new BorderPane (this.sourceFileField);

        final Button sourceFileSelectButton = new Button (Functions.getText ("@IDS_MAIN_SELECT_SOURCE"));
        sourceFileSelectButton.setOnAction (event -> this.selectSourceFile ());
        sourceFolderPanel.setRight (sourceFileSelectButton);

        final BoxPanel sourceUpperPart = new BoxPanel (Orientation.VERTICAL);
        sourceUpperPart.addComponent (new TitledSeparator (Functions.getText ("@IDS_MAIN_SOURCE_HEADER")));
        sourceUpperPart.addComponent (sourceFolderPanel);

        sourcePane.setTop (sourceUpperPart.getPane ());
        this.sourceTabPane = new TabPane ();
        this.sourceTabPane.getStyleClass ().add ("paddingLeftBottomRight");
        sourcePane.setCenter (this.sourceTabPane);

        final ObservableList<Tab> tabs = this.sourceTabPane.getTabs ();
        for (final ISourceFormat sourceFormat: this.sourceFormats)
        {
            final Tab tab = new Tab (sourceFormat.getName (), sourceFormat.getEditPane ());
            tab.setClosable (false);
            tabs.add (tab);
        }

        // Destination pane
        final BorderPane destinationPane = new BorderPane ();

        this.destinationPathField = new TextField ();
        final BorderPane destinationFolderPanel = new BorderPane (this.destinationPathField);

        final Button destinationFolderSelectButton = new Button (Functions.getText ("@IDS_MAIN_SELECT_DESTINATION"));
        destinationFolderSelectButton.setOnAction (event -> this.selectDestinationFolder ());
        destinationFolderPanel.setRight (destinationFolderSelectButton);

        final BoxPanel destinationUpperPart = new BoxPanel (Orientation.VERTICAL);
        destinationUpperPart.addComponent (new TitledSeparator ("@IDS_MAIN_DESTINATION_HEADER"));
        destinationUpperPart.addComponent (destinationFolderPanel);

        destinationPane.setTop (destinationUpperPart.getPane ());
        this.destinationTabPane = new TabPane ();
        this.destinationTabPane.getStyleClass ().add ("paddingLeftBottomRight");
        destinationPane.setCenter (this.destinationTabPane);

        final ObservableList<Tab> destinationTabs = this.destinationTabPane.getTabs ();
        for (final IDestinationFormat creator: this.destinationFormats)
        {
            final Tab tab = new Tab (creator.getName (), creator.getEditPane ());
            tab.setClosable (false);
            destinationTabs.add (tab);
        }

        // Tie it all together ...
        final HBox grid = new HBox ();
        grid.setFillHeight (true);
        grid.getChildren ().addAll (sourcePane, destinationPane);
        HBox.setHgrow (sourcePane, Priority.ALWAYS);
        HBox.setHgrow (destinationPane, Priority.ALWAYS);

        this.mainPane = new BorderPane ();
        this.mainPane.setCenter (grid);
        this.mainPane.setRight (buttonPane);
        this.mainPane.setBottom (this.loggingArea.getWebView ());

        this.setCenterNode (this.mainPane);

        this.loadConfig ();

        this.updateTitle (null);
    }


    /**
     * Load configuration settings.
     */
    private void loadConfig ()
    {
        final String sourcePath = this.config.getProperty (SOURCE_PATH);
        if (sourcePath != null)
            this.sourceFileField.setText (sourcePath);

        final String destinationPath = this.config.getProperty (DESTINATION_PATH);
        if (destinationPath != null)
            this.destinationPathField.setText (destinationPath);

        this.enableDarkMode.setSelected (this.config.getBoolean (ENABLE_DARK_MODE, false));

        for (final ISourceFormat detector: this.sourceFormats)
            detector.loadSettings (this.config);
        for (final IDestinationFormat creator: this.destinationFormats)
            creator.loadSettings (this.config);

        final int sourceType = this.config.getInteger (SOURCE_TYPE, 0);
        this.sourceTabPane.getSelectionModel ().select (sourceType);
        final int destinationType = this.config.getInteger (DESTINATION_TYPE, 0);
        this.destinationTabPane.getSelectionModel ().select (destinationType);
    }


    /** {@inheritDoc} */
    @Override
    public void exit ()
    {
        this.config.setProperty (SOURCE_PATH, this.sourceFileField.getText ());
        this.config.setProperty (DESTINATION_PATH, this.destinationPathField.getText ());
        this.config.setBoolean (ENABLE_DARK_MODE, this.enableDarkMode.isSelected ());

        for (final ISourceFormat detector: this.sourceFormats)
            detector.saveSettings (this.config);
        for (final IDestinationFormat creator: this.destinationFormats)
            creator.saveSettings (this.config);

        final int sourceSelectedIndex = this.sourceTabPane.getSelectionModel ().getSelectedIndex ();
        this.config.setInteger (SOURCE_TYPE, sourceSelectedIndex);
        final int destinationSelectedIndex = this.destinationTabPane.getSelectionModel ().getSelectedIndex ();
        this.config.setInteger (DESTINATION_TYPE, destinationSelectedIndex);

        // Store configuration
        super.exit ();

        Platform.exit ();
    }


    /**
     * Execute the conversion.
     */
    private void execute ()
    {
        if (!this.verifyProjectFiles ())
            return;

        final int selectedSourceFormat = this.sourceTabPane.getSelectionModel ().getSelectedIndex ();
        if (selectedSourceFormat < 0)
            return;
        final int selectedDestinationFormat = this.destinationTabPane.getSelectionModel ().getSelectedIndex ();
        if (selectedDestinationFormat < 0)
            return;

        this.loggingArea.clear ();

        Platform.runLater ( () -> {

            // Parse the project file
            this.log ("IDS_NOTIFY_PARSING_FILE", this.sourceFile.getAbsolutePath ());

            Platform.runLater ( () -> {
                final DawProjectContainer dawProject;
                try
                {
                    dawProject = this.sourceFormats[selectedSourceFormat].read (this.sourceFile);
                }
                catch (final IOException | ParseException ex)
                {
                    this.logError ("IDS_NOTIFY_COULD_NOT_READ", ex);
                    return;
                }

                // Check for overwrite
                if (this.destinationFormats[selectedDestinationFormat].needsOverwrite (dawProject, this.outputPath))
                {
                    if (!Functions.yesOrNo ("@IDS_NOTIFY_OVERWRITE"))
                    {
                        this.log ("IDS_NOTIFY_CANCELED");
                        return;
                    }
                }

                Platform.runLater ( () -> {
                    // Write output file(s)
                    this.log ("IDS_NOTIFY_WRITING_FILE", this.outputPath.getAbsolutePath ());

                    Platform.runLater ( () -> {

                        try
                        {
                            this.destinationFormats[selectedDestinationFormat].write (dawProject, this.outputPath);
                        }
                        catch (final IOException ex)
                        {
                            this.logError ("IDS_NOTIFY_COULD_NOT_WRITE_FILE", ex);
                            return;
                        }

                        this.log ("IDS_NOTIFY_CONVERSION_FINISHED");
                    });

                });

            });

        });
    }


    /**
     * Set and check source and destination project names.
     *
     * @return True if OK
     */
    private boolean verifyProjectFiles ()
    {
        // Check source file
        final String sourceFileName = this.sourceFileField.getText ();
        if (sourceFileName.isBlank ())
        {
            Functions.message ("@IDS_NOTIFY_SOURCE_NOT_SELECTED");
            this.sourceFileField.requestFocus ();
            return false;
        }
        this.sourceFile = new File (sourceFileName);
        if (!this.sourceFile.exists ())
        {
            Functions.message ("@IDS_NOTIFY_SOURCE_FILE_DOES_NOT_EXIST", this.sourceFile.getAbsolutePath ());
            this.sourceFileField.requestFocus ();
            return false;
        }

        // Check output folder
        final String outputFolderName = this.destinationPathField.getText ();
        if (outputFolderName.isBlank ())
        {
            Functions.message ("@IDS_NOTIFY_OUTPUT_FOLDER_NOT_SELECTED");
            this.destinationPathField.requestFocus ();
            return false;
        }
        this.outputPath = new File (outputFolderName);
        if (!this.outputPath.exists ())
        {
            Functions.message ("@IDS_NOTIFY_OUTPUT_FOLDER_DOES_NOT_EXIST", this.outputPath.getAbsolutePath ());
            this.destinationPathField.requestFocus ();
            return false;
        }
        if (!this.outputPath.isDirectory ())
        {
            Functions.message ("@IDS_NOTIFY_OUTPUT_FOLDER_NOT_A_FOLDER", this.outputPath.getAbsolutePath ());
            this.destinationPathField.requestFocus ();
            return false;
        }

        return true;
    }


    /**
     * Turn the dark mode on or off.
     *
     * @param isSelected True to turn on dark mode
     */
    private void setDarkMode (final boolean isSelected)
    {
        final ObservableList<String> stylesheets = this.scene.getStylesheets ();
        final String stylesheet = this.startPath + "/css/Darkmode.css";
        this.loggingArea.setDarkmode (isSelected);
        if (isSelected)
            stylesheets.add (stylesheet);
        else
            stylesheets.remove (stylesheet);
    }


    /**
     * Show a file selection dialog to select the source project.
     */
    private void selectSourceFile ()
    {
        final Optional<File> file = Functions.getFileFromUser (this.getStage (), true, "@IDS_MAIN_SELECT_SOURCE_HEADER", this.config, this.sourceExtensionFilters);
        if (file.isEmpty ())
            return;
        final String absolutePath = file.get ().getAbsolutePath ();
        this.sourceFileField.setText (absolutePath);

        // Find the matching project tab, if any
        for (int i = 1; i < this.sourceExtensionFilters.length; i++)
        {
            final ExtensionFilter filter = this.sourceExtensionFilters[i];
            for (final String extension: filter.getExtensions ())
            {
                // Remove '*' and compare ending
                if (absolutePath.endsWith (extension.substring (1)))
                {
                    this.sourceTabPane.getSelectionModel ().select (i - 1);
                    return;
                }
            }
        }
    }


    /**
     * Show a folder selection dialog to select the destination folder for the converted project.
     */
    private void selectDestinationFolder ()
    {
        final Optional<File> file = Functions.getFolderFromUser (this.getStage (), this.config, Functions.getText ("@IDS_MAIN_SELECT_DESTINATION_HEADER"));
        if (file.isPresent ())
            this.destinationPathField.setText (file.get ().getAbsolutePath ());
    }


    /** {@inheritDoc} */
    @Override
    public void log (final String messageID, final String... replaceStrings)
    {
        this.loggingArea.notify (Functions.getMessage (messageID, replaceStrings));
    }


    /** {@inheritDoc} */
    @Override
    public void logError (final String messageID, final String... replaceStrings)
    {
        this.loggingArea.notifyError (Functions.getMessage (messageID, replaceStrings));
    }


    /** {@inheritDoc} */
    @Override
    public void logError (final String messageID, final Throwable throwable)
    {
        this.loggingArea.notifyError (Functions.getMessage (messageID, throwable));
    }


    /** {@inheritDoc} */
    @Override
    public void logError (final Throwable throwable)
    {
        this.loggingArea.notifyError (throwable.getMessage (), throwable);
    }


    private static Button setupButton (final BasePanel panel, final String iconName, final String labelName)
    {
        final Image icon = Functions.iconFor ("de/mossgrabers/projectconverter/images/" + iconName + ".png");
        final Button button = panel.createButton (icon, labelName);
        button.alignmentProperty ().set (Pos.CENTER_LEFT);
        button.graphicTextGapProperty ().set (12);
        return button;
    }
}
