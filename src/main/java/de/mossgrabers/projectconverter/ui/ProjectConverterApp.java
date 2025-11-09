// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.ui;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.ConversionTask;
import de.mossgrabers.projectconverter.core.IDestinationFormat;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.projectconverter.format.dawproject.DawProjectCreator;
import de.mossgrabers.projectconverter.format.dawproject.DawProjectDetector;
import de.mossgrabers.projectconverter.format.reaper.ReaperCreator;
import de.mossgrabers.projectconverter.format.reaper.ReaperDetector;
import de.mossgrabers.tools.FileUtils;
import de.mossgrabers.tools.ui.AbstractFrame;
import de.mossgrabers.tools.ui.DefaultApplication;
import de.mossgrabers.tools.ui.EndApplicationException;
import de.mossgrabers.tools.ui.Functions;
import de.mossgrabers.tools.ui.TraversalManager;
import de.mossgrabers.tools.ui.control.TitledSeparator;
import de.mossgrabers.tools.ui.control.loggerbox.LoggerBox;
import de.mossgrabers.tools.ui.control.loggerbox.LoggerBoxLogger;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;


/**
 * The project converter application.
 *
 * @author Jürgen Moßgraber
 */
public class ProjectConverterApp extends AbstractFrame implements INotifier
{
    private static final int            NUMBER_OF_DIRECTORIES         = 20;
    private static final int            MAXIMUM_NUMBER_OF_LOG_ENTRIES = 100000;

    private static final String         ENABLE_DARK_MODE              = "EnableDarkMode";
    private static final String         DESTINATION_PATH              = "DestinationPath";
    private static final String         DESTINATION_TYPE              = "DestinationType";
    private static final String         SOURCE_PATH                   = "SourcePath";
    private static final String         SOURCE_TYPE                   = "SourceType";

    private final ISourceFormat []      sourceFormats;
    private final IDestinationFormat [] destinationFormats;
    private final ExtensionFilter []    sourceExtensionFilters;

    private final ComboBox<String>      sourcePathField               = new ComboBox<> ();
    private final ComboBox<String>      destinationPathField          = new ComboBox<> ();
    private final List<String>          sourcePathHistory             = new ArrayList<> ();
    private final List<String>          destinationPathHistory        = new ArrayList<> ();

    private Button                      sourceFileSelectButton;
    private Button                      destinationFolderSelectButton;
    private File                        sourceFile;
    private File                        outputPath;
    private CheckBox                    enableDarkMode;
    private TabPane                     sourceTabPane;
    private TabPane                     destinationTabPane;
    private Button                      convertButton;
    private Button                      cancelButton;
    private final LoggerBoxLogger       logger                        = new LoggerBoxLogger (MAXIMUM_NUMBER_OF_LOG_ENTRIES);
    private final LoggerBox             loggingArea                   = new LoggerBox (this.logger);
    private boolean                     combineWithPreviousMessage    = false;
    private final TraversalManager      traversalManager              = new TraversalManager ();

    private final ExecutorService       executor                      = Executors.newSingleThreadExecutor ();
    private Optional<ConversionTask>    conversionTaskOpt             = Optional.empty ();


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
            new DawProjectDetector (this),
            new ReaperDetector (this)
        };

        this.sourceExtensionFilters = new ExtensionFilter [this.sourceFormats.length + 1];
        this.sourceExtensionFilters[0] = new ExtensionFilter ("All Project Files", "*.*");
        for (int i = 0; i < this.sourceFormats.length; i++)
            this.sourceExtensionFilters[i + 1] = this.sourceFormats[i].getExtensionFilter ();

        this.destinationFormats = new IDestinationFormat []
        {
            new DawProjectCreator (this),
            new ReaperCreator (this)
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
        this.convertButton = setupButton (buttonPanel, "Convert", "@IDS_MAIN_CONVERT", "@IDS_MAIN_CONVERT_TOOLTIP");
        this.convertButton.setOnAction (_ -> this.execute ());
        this.convertButton.setDefaultButton (true);
        this.cancelButton = setupButton (buttonPanel, "Cancel", "@IDS_MAIN_CANCEL", "@IDS_MAIN_CANCEL_TOOLTIP");
        this.cancelButton.setOnAction (_ -> this.cancelExecution ());

        final BorderPane buttonPane = new BorderPane ();
        buttonPane.setTop (buttonPanel.getPane ());

        ////////////////////////////////////////////////////////////////////
        // Source pane
        ////////////////////////////////////////////////////////////////////

        final BorderPane sourcePane = new BorderPane ();

        final BorderPane sourceFolderPanel = new BorderPane (this.sourcePathField);
        this.sourcePathField.setMaxWidth (Double.MAX_VALUE);

        this.sourceFileSelectButton = new Button (Functions.getText ("@IDS_MAIN_SELECT_SOURCE"));
        this.sourceFileSelectButton.setTooltip (new Tooltip (Functions.getText ("@IDS_MAIN_SELECT_SOURCE_TOOLTIP")));
        this.sourceFileSelectButton.setOnAction (_ -> this.selectSourceFile ());
        sourceFolderPanel.setRight (this.sourceFileSelectButton);

        final BoxPanel sourceUpperPart = new BoxPanel (Orientation.VERTICAL);
        final TitledSeparator sourceTitle = new TitledSeparator (Functions.getText ("@IDS_MAIN_SOURCE_HEADER"));
        sourceTitle.setLabelFor (this.sourcePathField);
        sourceUpperPart.addComponent (sourceTitle);
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

        final BorderPane destinationFolderPanel = new BorderPane (this.destinationPathField);
        this.destinationPathField.setMaxWidth (Double.MAX_VALUE);

        this.destinationFolderSelectButton = new Button (Functions.getText ("@IDS_MAIN_SELECT_DESTINATION"));
        this.destinationFolderSelectButton.setTooltip (new Tooltip (Functions.getText ("@IDS_MAIN_SELECT_DESTINATION_TOOLTIP")));
        this.destinationFolderSelectButton.setOnAction (_ -> this.selectDestinationFolder ());
        destinationFolderPanel.setRight (this.destinationFolderSelectButton);

        final BoxPanel destinationUpperPart = new BoxPanel (Orientation.VERTICAL);
        final TitledSeparator destinationHeader = new TitledSeparator ("@IDS_MAIN_DESTINATION_HEADER");
        destinationHeader.setLabelFor (this.destinationPathField);
        destinationUpperPart.addComponent (destinationHeader);
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
        this.enableDarkMode.selectedProperty ().addListener ( (_, _, isSelected) -> this.setDarkMode (isSelected.booleanValue ()));

        final BorderPane mainPane = new BorderPane ();
        mainPane.setTop (topPane);
        final StackPane stackPane = new StackPane (this.loggingArea);
        stackPane.getStyleClass ().add ("padding");
        mainPane.setCenter (stackPane);
        mainPane.setBottom (optionsPanel.getPane ());

        this.setCenterNode (mainPane);

        this.loadConfiguration ();

        this.updateTitle (null);
        this.updateButtonStates (false);

        this.sourcePathField.requestFocus ();
        this.configureTraversalManager ();
    }


    private void configureTraversalManager ()
    {
        this.traversalManager.add (this.sourcePathField);
        this.traversalManager.add (this.sourceFileSelectButton);
        this.traversalManager.add (this.sourceTabPane);
        for (final Tab tab: this.sourceTabPane.getTabs ())
            if (tab.getContent () instanceof final Parent content)
                this.traversalManager.addChildren (content);

        this.traversalManager.add (this.destinationPathField);
        this.traversalManager.add (this.destinationFolderSelectButton);
        this.traversalManager.add (this.destinationTabPane);
        for (final Tab tab: this.destinationTabPane.getTabs ())
            if (tab.getContent () instanceof final Parent content)
                this.traversalManager.addChildren (content);

        this.traversalManager.add (this.cancelButton);
        this.traversalManager.add (this.convertButton);

        this.traversalManager.add (this.loggingArea);
        this.traversalManager.add (this.enableDarkMode);

        this.traversalManager.register (this.getStage ());
    }


    /**
     * Load configuration settings.
     */
    private void loadConfiguration ()
    {
        for (int i = 0; i < NUMBER_OF_DIRECTORIES; i++)
        {
            final String sourcePath = this.config.getProperty (SOURCE_PATH + i);
            if (sourcePath == null || sourcePath.isBlank ())
                break;
            if (!this.sourcePathHistory.contains (sourcePath))
                this.sourcePathHistory.add (sourcePath);
        }
        this.sourcePathField.getItems ().addAll (this.sourcePathHistory);
        this.sourcePathField.setEditable (true);
        if (!this.sourcePathHistory.isEmpty ())
            this.sourcePathField.getEditor ().setText (this.sourcePathHistory.get (0));

        for (int i = 0; i < NUMBER_OF_DIRECTORIES; i++)
        {
            final String destinationPath = this.config.getProperty (DESTINATION_PATH + i);
            if (destinationPath == null || destinationPath.isBlank ())
                break;
            if (!this.destinationPathHistory.contains (destinationPath))
                this.destinationPathHistory.add (destinationPath);
        }
        this.destinationPathField.getItems ().addAll (this.destinationPathHistory);
        this.destinationPathField.setEditable (true);
        if (!this.destinationPathHistory.isEmpty ())
            this.destinationPathField.getEditor ().setText (this.destinationPathHistory.get (0));

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

        this.saveConfiguration ();
        // Store configuration
        super.exit ();

        Platform.exit ();
    }


    private void saveConfiguration ()
    {
        updateHistory (this.sourcePathField.getEditor ().getText (), this.sourcePathHistory);
        for (int i = 0; i < NUMBER_OF_DIRECTORIES; i++)
            this.config.setProperty (SOURCE_PATH + i, this.sourcePathHistory.size () > i ? this.sourcePathHistory.get (i) : "");

        updateHistory (this.destinationPathField.getEditor ().getText (), this.destinationPathHistory);
        for (int i = 0; i < NUMBER_OF_DIRECTORIES; i++)
            this.config.setProperty (DESTINATION_PATH + i, this.destinationPathHistory.size () > i ? this.destinationPathHistory.get (i) : "");

        this.config.setBoolean (ENABLE_DARK_MODE, this.enableDarkMode.isSelected ());

        for (final ISourceFormat detector: this.sourceFormats)
            detector.saveSettings (this.config);
        for (final IDestinationFormat creator: this.destinationFormats)
            creator.saveSettings (this.config);

        final int sourceSelectedIndex = this.sourceTabPane.getSelectionModel ().getSelectedIndex ();
        this.config.setInteger (SOURCE_TYPE, sourceSelectedIndex);
        final int destinationSelectedIndex = this.destinationTabPane.getSelectionModel ().getSelectedIndex ();
        this.config.setInteger (DESTINATION_TYPE, destinationSelectedIndex);
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
            conversionTask.setOnCancelled (_ -> this.updateButtonStates (false));
            conversionTask.setOnFailed (_ -> this.updateButtonStates (false));
            conversionTask.setOnSucceeded (_ -> this.updateButtonStates (false));
            conversionTask.setOnRunning (_ -> this.updateButtonStates (true));
            conversionTask.setOnScheduled (_ -> this.updateButtonStates (true));
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
        final String sourceFileName = this.sourcePathField.getEditor ().getText ();
        if (sourceFileName.isBlank ())
        {
            Functions.message ("@IDS_NOTIFY_SOURCE_NOT_SELECTED");
            this.sourcePathField.getEditor ().requestFocus ();
            return false;
        }
        this.sourceFile = new File (sourceFileName);
        if (!this.sourceFile.exists ())
        {
            Functions.message ("@IDS_NOTIFY_SOURCE_FILE_DOES_NOT_EXIST", this.sourceFile.getAbsolutePath ());
            this.sourcePathField.getEditor ().requestFocus ();
            return false;
        }

        // Check output folder
        final String outputFolderName = this.destinationPathField.getEditor ().getText ();
        if (outputFolderName.isBlank ())
        {
            Functions.message ("@IDS_NOTIFY_OUTPUT_FOLDER_NOT_SELECTED");
            this.destinationPathField.requestFocus ();
            return false;
        }
        this.outputPath = new File (outputFolderName);
        if (!this.outputPath.exists () && !this.outputPath.mkdirs ())
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
            if (!stylesheets.contains (stylesheet))
            {
                stylesheets.add (stylesheet);
                this.loggingArea.setBlendMode (BlendMode.OVERLAY);
            }
        }
        else
        {
            stylesheets.remove (stylesheet);
            this.loggingArea.setBlendMode (BlendMode.DARKEN);
        }
    }


    /**
     * Show a file selection dialog to select the source project.
     */
    private void selectSourceFile ()
    {
        final File currentSourcePath = new File (this.sourcePathField.getEditor ().getText ());
        if (currentSourcePath.exists () && currentSourcePath.isDirectory ())
            this.config.setActivePath (currentSourcePath);
        final Optional<File> file = Functions.getFileFromUser (this.getStage (), true, "@IDS_MAIN_SELECT_SOURCE_HEADER", this.config, this.sourceExtensionFilters);
        if (file.isPresent ())
        {
            final String absolutePath = file.get ().getAbsolutePath ();
            this.sourcePathField.getEditor ().setText (absolutePath);

            // Find the matching project tab, if any
            for (int i = 1; i < this.sourceExtensionFilters.length; i++)
            {
                final ExtensionFilter filter = this.sourceExtensionFilters[i];
                for (final String extension: filter.getExtensions ())
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
        final File currentDestinationPath = new File (this.destinationPathField.getEditor ().getText ());
        if (currentDestinationPath.exists () && currentDestinationPath.isDirectory ())
            this.config.setActivePath (currentDestinationPath);
        final Optional<File> file = Functions.getFolderFromUser (this.getStage (), this.config, "@IDS_MAIN_SELECT_DESTINATION_HEADER");
        if (file.isPresent ())
            this.destinationPathField.getEditor ().setText (file.get ().getAbsolutePath ());
    }


    /** {@inheritDoc} */
    @Override
    public void log (final String messageID, final String... replaceStrings)
    {
        this.logText (Functions.getMessage (messageID, replaceStrings));
    }


    /** {@inheritDoc} */
    @Override
    public void logError (final String messageID, final String... replaceStrings)
    {
        this.logErrorText (Functions.getMessage (messageID, replaceStrings));
    }


    /** {@inheritDoc} */
    @Override
    public void logError (final String messageID, final Throwable throwable)
    {
        this.logErrorText (Functions.getMessage (messageID, throwable));
    }


    /** {@inheritDoc} */
    @Override
    public void logError (final Throwable throwable)
    {
        this.logError (throwable, true);
    }


    private void logError (final Throwable throwable, final boolean logExceptionStack)
    {
        String message = throwable.getMessage ();
        if (message == null)
            message = throwable.getClass ().getName ();
        if (logExceptionStack)
        {
            final StringBuilder sb = new StringBuilder (message).append ('\n');
            final StringWriter sw = new StringWriter ();
            final PrintWriter pw = new PrintWriter (sw);
            throwable.printStackTrace (pw);
            sb.append (sw.toString ()).append ('\n');
            message = sb.toString ();
        }
        this.logErrorText (message);
    }


    private void logErrorText (final String message)
    {
        this.logger.error (message);
    }


    private void logText (final String text)
    {
        final boolean combine = this.combineWithPreviousMessage;
        this.combineWithPreviousMessage = !text.endsWith ("\n");
        this.logger.info (text, combine);
    }


    /** {@inheritDoc} */
    @Override
    public void updateButtonStates (final boolean isExecuting)
    {
        Platform.runLater ( () -> {

            this.cancelButton.setDisable (!isExecuting);
            this.convertButton.setDisable (isExecuting);
            if (!this.cancelButton.isDisabled ())
            {
                this.cancelButton.setDefaultButton (true);
                this.cancelButton.requestFocus ();
                this.loggingArea.setAccessibleText (Functions.getMessage ("IDS_NOTIFY_PROCESSING"));
            }
            else
            {
                this.convertButton.setDefaultButton (true);
                this.convertButton.requestFocus ();
                this.loggingArea.setAccessibleText (Functions.getMessage ("IDS_NOTIFY_FINISHED"));
            }

        });
    }


    private static Button setupButton (final BasePanel panel, final String iconName, final String labelName, final String mnemonic) throws EndApplicationException
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
        final Button button = panel.createButton (icon, labelName, mnemonic);
        button.alignmentProperty ().set (Pos.CENTER_LEFT);
        button.graphicTextGapProperty ().set (12);
        return button;
    }


    private static void setTabPaneLeftTabsHorizontal (final TabPane tabPane)
    {
        tabPane.setSide (Side.LEFT);
        tabPane.setRotateGraphic (true);
        tabPane.setTabMinHeight (160); // Determines tab width. I know, its odd.
        tabPane.setTabMaxHeight (200);
        tabPane.getStyleClass ().add ("horizontal-tab-pane");

        for (final Tab tab: tabPane.getTabs ())
        {
            final Label l = new Label ("    ");
            l.setVisible (false);
            l.setMaxHeight (0);
            l.setPrefHeight (0);
            tab.setGraphic (l);

            Platform.runLater ( () -> rotateTabLabels (tab));
        }
    }


    private static void rotateTabLabels (final Tab tab)
    {
        // Get the "tab-container" node. This is what we want to rotate/shift for easy
        // left-alignment.
        final Parent parent = tab.getGraphic ().getParent ();
        if (parent == null)
        {
            Platform.runLater ( () -> rotateTabLabels (tab));
            return;
        }
        final Parent tabContainer = parent.getParent ();
        tabContainer.setRotate (90);
        // By default the display will originate from the center.
        // Applying a negative Y transformation will move it left.
        tabContainer.setTranslateY (-80);
    }


    private static void updateHistory (final String newItem, final List<String> history)
    {
        history.remove (newItem);
        history.add (0, newItem);
    }
}
