// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

/**
 * The project converter module.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
module de.mossgrabers.projectconverter
{
    requires java.desktop;
    requires java.logging;
    requires transitive java.prefs;
    requires transitive javafx.controls;
    requires transitive javafx.web;
    requires transitive java.xml;
    requires transitive de.mossgrabers.uitools;

    requires transitive dawproject;
    requires javafx.graphics;


    exports de.mossgrabers.projectconverter;
    exports de.mossgrabers.projectconverter.ui;
    exports de.mossgrabers.projectconverter.core;
    exports de.mossgrabers.projectconverter.format.reaper;
    exports de.mossgrabers.projectconverter.format.reaper.model;
    exports de.mossgrabers.projectconverter.format.dawproject;


    opens de.mossgrabers.projectconverter.css;
    opens de.mossgrabers.projectconverter.images;
}