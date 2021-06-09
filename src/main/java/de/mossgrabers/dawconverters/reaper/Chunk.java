package de.mossgrabers.dawconverters.reaper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class Chunk extends Node
{
    private final List<Node> childNodes = new ArrayList<> ();


    public Chunk ()
    {
    }


    public void addChildNode (final Node node)
    {
        this.childNodes.add (node);
    }


    public List<Node> getChildNodes ()
    {
        return this.childNodes;
    }


    public Optional<Node> getParameter (String name)
    {
        for (final Node childNode: this.childNodes)
        {
            if (name.equals (childNode.getName ()))
                return Optional.of (childNode);
        }
        return Optional.empty ();
    }
}
