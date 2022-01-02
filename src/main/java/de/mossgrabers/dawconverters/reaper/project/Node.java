// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.dawconverters.reaper.project;

import java.util.ArrayList;
import java.util.List;


/**
 * A node in a Reaper project.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public class Node
{
    private String             line;
    private String             name;
    private final List<String> parameters = new ArrayList<> ();


    /**
     * Set the name of the node.
     *
     * @param name The name
     */
    public void setName (final String name)
    {
        this.name = name;
    }


    /**
     * Get the name of the node.
     *
     * @return The name
     */
    public String getName ()
    {
        return this.name;
    }


    /**
     * Add parameters to the node.
     *
     * @param parameters The parameters to add
     */
    public void addParameters (final List<String> parameters)
    {
        this.parameters.addAll (parameters);
    }


    /**
     * Get the parameters of the node.
     *
     * @return The parameters
     */
    public List<String> getParameters ()
    {
        return this.parameters;
    }


    /**
     * Set the raw text line of the node.
     *
     * @param line THe text
     */
    public void setLine (final String line)
    {
        this.line = line;
    }


    /**
     * Get the text line.
     *
     * @return The text
     */
    public String getLine ()
    {
        return this.line;
    }
}
