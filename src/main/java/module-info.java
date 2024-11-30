// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

/**
 * The project converter module.
 *
 * @author Jürgen Moßgraber
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
    requires com.github.trilarion.sound;

    requires javafx.graphics;
    requires transitive com.bitwig.dawproject;
    requires jakarta.xml.bind;


    exports de.mossgrabers.projectconverter;
    exports de.mossgrabers.projectconverter.ui;
    exports de.mossgrabers.projectconverter.core;
    exports de.mossgrabers.projectconverter.format.reaper;
    exports de.mossgrabers.projectconverter.format.reaper.model;
    exports de.mossgrabers.projectconverter.format.dawproject;


    opens de.mossgrabers.projectconverter.css;
    opens de.mossgrabers.projectconverter.images;
}