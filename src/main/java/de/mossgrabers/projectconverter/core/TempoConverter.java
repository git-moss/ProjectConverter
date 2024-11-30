// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

import java.util.List;


/**
 * Helper class to convert between seconds and beats on a tempo timeline.
 *
 * @author Jürgen Moßgraber
 */
public class TempoConverter
{
    /**
     * Convert a time value in seconds to beats.
     *
     * @param seconds The time value in seconds
     * @param tempo The tempo in BPM
     * @return The time in beats
     */
    public static double secondsToBeats (final double seconds, final double tempo)
    {
        final double beatsPerSecond = tempo / 60.0;
        return seconds * beatsPerSecond;
    }


    /**
     * Calculates the number of beats at a certain position which is given in seconds based on a
     * tempo map. If the tempo change is linear (isLinear is true), it will calculate the average
     * tempo between the current tempo and the next tempo, and use this average tempo to calculate
     * the beats. If seconds is within a linear tempo change, it will calculate the proportion of
     * the elapsed time within this tempo change and adjust the current tempo accordingly.
     * 
     * @param seconds The seconds value
     * @param tempoMap The tempo map
     * @return The value in beats
     */
    public static double secondsToBeats (final double seconds, final List<TempoChange> tempoMap)
    {
        // There must be at least one tempo change in the list which is at the beginning!
        final int size = tempoMap.size ();
        TempoChange previous = tempoMap.get (0);
        if (size == 1)
            return secondsToBeats (seconds, previous.getTempo ());

        double beats = 0;
        for (int i = 1; i < size; i++)
        {
            // One loop iteration calculates the range from the previous tempo change to the current

            final TempoChange current = tempoMap.get (i);
            final double currentTime = current.getTime ();
            final double currentTempo = current.getTempo ();
            final double previousTime = previous.getTime ();
            final double previousTempo = previous.getTempo ();

            if (previous.isLinear ())
            {
                // Calculate a linear change

                if (seconds <= currentTime)
                {
                    // The position is inside of the ramp, calculate the exact value

                    // First, calculate the tempo at the position
                    final double rangeTime = seconds - previousTime;
                    final double fullRangeTime = currentTime - previousTime;
                    final double tempoPos = previousTempo + rangeTime / fullRangeTime * (currentTempo - previousTempo);

                    // Then integrate the space below the linear change from the previous tempo
                    // change to the position. This can be done by using the formula for the area of
                    // a trapezoid, which is (a+b)/2*h
                    return beats + ((previousTempo + tempoPos) / 60.0) / 2.0 * rangeTime;
                }

                // The position is after the tempo ramp, we can simply use the average tempo to
                // calculate the full range of the linear change
                final double avgTempo = (previousTempo + currentTempo) / 2.0;
                beats += diffToBeats (previousTime, currentTime, avgTempo);
            }
            else
            {
                // Calculate the immediate tempo change

                if (seconds <= currentTime)
                    return beats + diffToBeats (previousTime, seconds, previousTempo);

                beats += diffToBeats (previousTime, currentTime, previousTempo);
            }

            previous = current;
        }

        // Calculate the range after the last tempo change event, if necessary
        beats += diffToBeats (previous.getTime (), seconds, previous.getTempo ());
        return beats;
    }


    /**
     * Convert a time value in beats to seconds.
     *
     * @param beats The time value in beats
     * @param tempo The tempo in BPM
     * @return The time in seconds
     */
    public static double beatsToSeconds (final double beats, final double tempo)
    {
        final double beatsPerSecond = tempo / 60.0;
        return beats / beatsPerSecond;
    }


    /**
     * Calculates the seconds at a certain position which is given in number of beats based on a
     * tempo map. If the tempo change is linear (isLinear is true), it will calculate the proportion
     * of the elapsed beats within this tempo change and adjust the current time accordingly. If
     * beats is within a linear tempo change, it will calculate the average tempo using the
     * proportion of the elapsed beats and use this average tempo to calculate the seconds.
     * 
     * @param beats The beats value
     * @param tempoMap The tempo-map
     * @return The value in seconds
     */
    public static double beatsToSeconds (final double beats, final List<TempoChange> tempoMap)
    {
        // There must be at least one tempo change in the list which is at the beginning!
        final int size = tempoMap.size ();
        TempoChange previous = tempoMap.get (0);
        if (size == 1)
            return beatsToSeconds (beats, previous.getTempo ());

        double seconds = 0;
        double previousBeats = 0;
        for (int i = 1; i < size; i++)
        {
            final TempoChange current = tempoMap.get (i);
            final double currentTime = current.getTime ();
            final double currentTempo = current.getTempo ();
            final double previousTime = previous.getTime ();
            final double previousTempo = previous.getTempo ();

            if (previous.isLinear ())
            {
                final double rangeTime = currentTime - previousTime;
                final double previousTempoInSeconds = previousTempo / 60.0;
                final double currentTempoInSeconds = currentTempo / 60.0;
                final double fullRangeBeats = ((previousTempoInSeconds + currentTempoInSeconds) / 2.0) * rangeTime;
                final double currentBeats = previousBeats + fullRangeBeats;

                if (beats <= currentBeats)
                {
                    // Calculate a, b, and c for the quadratic formula
                    double a = 0.5 * ((currentTempoInSeconds - previousTempoInSeconds) / rangeTime);
                    double b = previousTempoInSeconds;
                    double c = -fullRangeBeats;
                    double discriminant = Math.pow (b, 2) - (4 * a * c);
                    // Only 1 result of the quadratic formula since the seconds are always positive!
                    seconds += (-b + Math.sqrt (discriminant)) / (2 * a);
                    return seconds;
                }

                previousBeats = currentBeats;
                seconds = current.getTime ();
            }
            else
            {
                double bps = previousTempo / 60.0;
                final double currentBeats = previousBeats + (currentTime - previousTime) * bps;
                if (beats <= currentBeats)
                {
                    seconds += (beats - previousBeats) / bps;
                    return seconds;
                }

                seconds += (currentBeats - previousBeats) / bps;
                previousBeats = currentBeats;
            }

            previous = current;
        }

        // Calculate the range after the last tempo change event, if necessary
        seconds += (beats - previousBeats) / (previous.getTempo () / 60.0);
        return seconds;
    }


    private static double diffToBeats (final double prevTime, final double nextTime, final double tempo)
    {
        return (nextTime - prevTime) * tempo / 60.0;
    }
}
