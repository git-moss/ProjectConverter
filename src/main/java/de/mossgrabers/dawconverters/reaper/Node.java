package de.mossgrabers.dawconverters.reaper;

import java.util.ArrayList;
import java.util.List;


public class Node
{
    private String             line;
    private String             name;
    private final List<String> parameters = new ArrayList<> ();


    public Node ()
    {
    }


    public void setName (final String name)
    {
        this.name = name;
    }


    public String getName ()
    {
        return this.name;
    }


    public void addParameters (final List<String> parameter)
    {
        this.parameters.addAll (parameter);
    }


    public List<String> getParameters ()
    {
        return this.parameters;
    }


    public void setRaw (final String line)
    {
        this.line = line;
    }


    public String getLine ()
    {
        return this.line;
    }


    public void setLine (String line)
    {
        this.line = line;
    }
}
