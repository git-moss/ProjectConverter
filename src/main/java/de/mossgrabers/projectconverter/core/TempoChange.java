// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

/**
 * Helper class to convert between seconds and beats on a tempo timeline.
 *
 * @author Jürgen Moßgraber
 */
public class TempoChange
{
    private final double  time;
    private final double  tempo;
    private final boolean isLinear;


    /**
     * Constructor.
     * 
     * @param time The position of the tempo change event in seconds
     * @param tempo The new tempo starting at this position
     * @param isLinear If true, a linear change till the next tempo event will happen
     */
    public TempoChange (final double time, final double tempo, final boolean isLinear)
    {
        this.time = time;
        this.tempo = tempo;
        this.isLinear = isLinear;
    }


    /**
     * Get the position of the tempo change.
     * 
     * @return The position
     */
    public double getTime ()
    {
        return this.time;
    }


    /**
     * Get the tempo starting at the position.
     * 
     * @return The tempo
     */
    public double getTempo ()
    {
        return this.tempo;
    }


    /**
     * Should the tempo change happen immediately or linear till the next tempo event?
     * 
     * @return True if linear change should happen
     */
    public boolean isLinear ()
    {
        return this.isLinear;
    }
}