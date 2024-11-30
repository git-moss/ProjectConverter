// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * A chunk in a Reaper project.
 *
 * @author Jürgen Moßgraber
 */
public class Chunk extends Node
{
    private final List<Node> childNodes = new ArrayList<> ();


    /**
     * Add a child node to the chunk.
     *
     * @param node The node to add
     */
    public void addChildNode (final Node node)
    {
        this.childNodes.add (node);
    }


    /**
     * Get all child nodes of the chunk.
     *
     * @return The child notes
     */
    public List<Node> getChildNodes ()
    {
        return this.childNodes;
    }


    /**
     * Lookup a child node of the chunk with a certain name.
     *
     * @param name The name of the node to look up
     * @return The first matching node or empty if not found
     */
    public Optional<Node> getChildNode (final String name)
    {
        for (final Node childNode: this.childNodes)
        {
            if (name.equals (childNode.getName ()))
                return Optional.of (childNode);
        }
        return Optional.empty ();
    }


    /**
     * Lookup all child nodes of the chunk with a certain name.
     *
     * @param name The name of the node to look up
     * @return The first matching node or empty if not found
     */
    public List<Node> getChildNodes (final String name)
    {
        final List<Node> results = new ArrayList<> ();
        for (final Node childNode: this.childNodes)
        {
            if (name.equals (childNode.getName ()))
                results.add (childNode);
        }
        return results;
    }
}
