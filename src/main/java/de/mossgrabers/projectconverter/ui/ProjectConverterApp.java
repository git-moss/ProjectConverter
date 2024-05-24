// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.ui;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.ConversionTask;
import de.mossgrabers.projectconverter.core.IDestinationFormat;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.projectconverter.format.dawproject.DawProjectDestinationFormat;
import de.mossgrabers.projectconverter.format.dawproject.DawProjectSourceFormat;
import de.mossgrabers.projectconverter.format.reaper.ReaperDestinationFormat;
import de.mossgrabers.projectconverter.format.reaper.ReaperSourceFormat;
import de.mossgrabers.tools.FileUtils;
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
import javafx.geometry.Side;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;


/**
 * The project converter application.
 *
 * @author Jürgen Moßgraber
 */
public class ProjectConverterApp extends AbstractFrame implements INotifier
{
    private static final String         ENABLE_DARK_MODE  = "EnableDarkMode";
    private static final String         DESTINATION_PATH  = "DestinationPath";
    private static final String         DESTINATION_TYPE  = "DestinationType";
    private static final String         SOURCE_PATH       = "SourcePath";
    private static final String         SOURCE_TYPE       = "SourceType";

    private final ISourceFormat []      sourceFormats;
    private final IDestinationFormat [] destinationFormats;
    private final ExtensionFilter []    sourceExtensionFilters;

    private TextField                   sourceFileField;
    private TextField                   destinationPathField;
    private File                        sourceFile;
    private File                        outputPath;
    private CheckBox                    enableDarkMode;
    private TabPane                     sourceTabPane;
    private TabPane                     destinationTabPane;
    private Button                      convertButton;
    private Button                      cancelButton;
    private final LoggerBox             loggingArea       = new LoggerBox ();

    private final ExecutorService       executor          = Executors.newSingleThreadExecutor ();
    private Optional<ConversionTask>    conversionTaskOpt = Optional.empty ();


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
        super ("de/mossgrabers/projectconverter", 1100, 500);

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

        ////////////////////////////////////////////////////////////////////
        // The main button panel
        ////////////////////////////////////////////////////////////////////

        final ButtonPanel buttonPanel = new ButtonPanel (Orientation.VERTICAL);
        this.convertButton = setupButton (buttonPanel, "Convert", "@IDS_MAIN_CONVERT");
        this.convertButton.setOnAction (event -> this.execute ());
        this.cancelButton = setupButton (buttonPanel, "Cancel", "@IDS_MAIN_CANCEL");
        this.cancelButton.setOnAction (event -> this.cancelExecution ());

        final BorderPane buttonPane = new BorderPane ();
        buttonPane.setTop (buttonPanel.getPane ());

        ////////////////////////////////////////////////////////////////////
        // Source pane
        ////////////////////////////////////////////////////////////////////

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
        setTabPaneLeftTabsHorizontal (this.sourceTabPane);

        ////////////////////////////////////////////////////////////////////
        // Destination pane
        ////////////////////////////////////////////////////////////////////

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
        setTabPaneLeftTabsHorizontal (this.destinationTabPane);

        ////////////////////////////////////////////////////////////////////
        // Tie it all together ...
        ////////////////////////////////////////////////////////////////////

        final HBox grid = new HBox ();
        grid.setFillHeight (true);
        grid.getChildren ().addAll (sourcePane, destinationPane);
        HBox.setHgrow (sourcePane, Priority.ALWAYS);
        HBox.setHgrow (destinationPane, Priority.ALWAYS);
        sourcePane.setMaxWidth (Double.MAX_VALUE);
        destinationPane.setMaxWidth (Double.MAX_VALUE);

        final BorderPane topPane = new BorderPane ();
        topPane.setCenter (grid);
        topPane.setRight (buttonPane);

        final ButtonPanel optionsPanel = new ButtonPanel (Orientation.HORIZONTAL);
        this.enableDarkMode = optionsPanel.createCheckBox ("@IDS_MAIN_ENABLE_DARK_MODE", "@IDS_MAIN_ENABLE_DARK_MODE_TOOLTIP");
        this.enableDarkMode.selectedProperty ().addListener ( (obs, wasSelected, isSelected) -> this.setDarkMode (isSelected.booleanValue ()));

        final BorderPane mainPane = new BorderPane ();
        mainPane.setTop (topPane);
        final WebView webView = this.loggingArea.getWebView ();
        final StackPane stackPane = new StackPane (webView);
        stackPane.getStyleClass ().add ("padding");
        mainPane.setCenter (stackPane);
        mainPane.setBottom (optionsPanel.getPane ());

        this.setCenterNode (mainPane);

        this.loadConfig ();

        this.updateTitle (null);
        this.updateButtonStates (false);
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
        this.executor.shutdown ();

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

        final IDestinationFormat destinationFormat = this.destinationFormats[selectedDestinationFormat];

        // Check for overwrite
        final String projectName = FileUtils.getNameWithoutType (this.sourceFile);
        if (destinationFormat.needsOverwrite (projectName, this.outputPath) && !Functions.yesOrNo ("@IDS_NOTIFY_OVERWRITE"))
        {
            this.log ("IDS_NOTIFY_CANCELED");
            return;
        }

        Platform.runLater ( () -> {

            final ConversionTask conversionTask = new ConversionTask (this.sourceFile, this.outputPath, this.sourceFormats[selectedSourceFormat], destinationFormat, this);
            this.conversionTaskOpt = Optional.of (conversionTask);
            conversionTask.setOnCancelled (event -> this.updateButtonStates (false));
            conversionTask.setOnFailed (event -> this.updateButtonStates (false));
            conversionTask.setOnSucceeded (event -> this.updateButtonStates (false));
            conversionTask.setOnRunning (event -> this.updateButtonStates (true));
            conversionTask.setOnScheduled (event -> this.updateButtonStates (true));
            this.executor.execute (conversionTask);

        });
    }


    /**
     * Cancel button was pressed.
     */
    private void cancelExecution ()
    {
        if (this.conversionTaskOpt.isPresent ())
            this.conversionTaskOpt.get ().cancel ();
    }


    /** {@inheritDoc} */
    @Override
    public boolean isCancelled ()
    {
        return this.conversionTaskOpt.isPresent () && this.conversionTaskOpt.get ().isCancelled ();
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
        if (isSelected)
        {
            stylesheets.add (stylesheet);
            this.loggingArea.getWebView ().setBlendMode (BlendMode.OVERLAY);
        }
        else
        {
            stylesheets.remove (stylesheet);
            this.loggingArea.getWebView ().setBlendMode (BlendMode.DARKEN);
        }
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


    /** {@inheritDoc} */
    @Override
    public void updateButtonStates (final boolean isExecuting)
    {
        Platform.runLater ( () -> {
            this.cancelButton.setDisable (!isExecuting);
            this.convertButton.setDisable (isExecuting);
        });
    }


    private static Button setupButton (final BasePanel panel, final String iconName, final String labelName) throws EndApplicationException
    {
        Image icon;
        try
        {
            icon = Functions.iconFor ("de/mossgrabers/projectconverter/images/" + iconName + ".png");
        }
        catch (final IOException ex)
        {
            throw new EndApplicationException (ex);
        }
        final Button button = panel.createButton (icon, labelName);
        button.alignmentProperty ().set (Pos.CENTER_LEFT);
        button.graphicTextGapProperty ().set (12);
        return button;
    }


    private static void setTabPaneLeftTabsHorizontal (final TabPane tabPane)
    {
        tabPane.setSide (Side.LEFT);
        tabPane.setRotateGraphic (true);
        tabPane.setTabMinHeight (80); // Determines tab width. I know, its odd.
        tabPane.setTabMaxHeight (200);
        tabPane.getStyleClass ().add ("horizontal-tab-pane");

        for (final Tab tab: tabPane.getTabs ())
        {
            final Label l = new Label ("xxxx");
            l.setVisible (false);
            l.setMaxHeight (0);
            l.setPrefHeight (0);
            tab.setGraphic (l);

            Platform.runLater ( () -> {
                // Get the "tab-container" node. This is what we want to rotate/shift for easy
                // left-alignment.
                final Parent tabContainer = tab.getGraphic ().getParent ().getParent ();
                tabContainer.setRotate (90);
                // By default the display will originate from the center. Applying a negative Y
                // transformation will move it left. Should be the 'TabMinHeight/2'
                tabContainer.setTranslateY (-60);
            });
        }
    }
}
