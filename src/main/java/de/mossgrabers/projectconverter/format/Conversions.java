// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format;

/**
 * Utility functions for converting values.
 *
 * @author Jürgen Moßgraber
 */
public class Conversions
{
    /**
     * Private constructor since this is a utility class.
     */
    private Conversions ()
    {
        // Intentionally empty
    }


    /**
     * Convert the time value to beats.
     *
     * @param beatsPerSecond Beats per second
     * @param value The value in time (seconds)
     * @return The value in beats
     */
    public static double toBeats (final double beatsPerSecond, final double value)
    {
        return value * beatsPerSecond;
    }


    /**
     * Convert the time value to beats.
     *
     * @param timeInSeconds The value in time
     * @param tempo The tempo
     * @return The value in beats
     */
    public static double toTempoBeats (final double timeInSeconds, final Double tempo)
    {
        final double beatsPerSecond = tempo.doubleValue () / 60.0;
        return beatsPerSecond * timeInSeconds;
    }


    /**
     * Convert the beats value to time.
     * 
     * @param valueInBeatsPerSecond The value in beats
     * @param beatsPerSecond Beats per second
     *
     * @return The value in time
     */
    public static double toTime (final double valueInBeatsPerSecond, final double beatsPerSecond)
    {
        return valueInBeatsPerSecond / beatsPerSecond;
    }


    /**
     * Convert the beats value to time. The beats per second are calculated from the given tempo.
     *
     * @param valueInBeatsPerSecond The value in beats
     * @param tempo The tempo
     * @return The value in time
     */
    public static double toTempoTime (final double valueInBeatsPerSecond, final Double tempo)
    {
        final double beatsPerSecond = tempo.doubleValue () / 60.0;
        return toTime (valueInBeatsPerSecond, beatsPerSecond);
    }


    /**
     * Converts a dB value to linear value.
     *
     * @param dbValue The dB value to convert
     * @param maxLevelDB The maximum possible dB value
     * @return The linear value
     */
    public static double dBToValue (final double dbValue, final double maxLevelDB)
    {
        final double linearValue = dbToLinear (dbValue);
        return 20 * Math.log10 (linearValue) + maxLevelDB;
    }


    private static double dbToLinear (final double dbValue)
    {
        if (dbValue <= -150)
            return 0.0000000298023223876953125;
        return Math.exp (dbValue / 8.6858896380650365530225783783321);
    }


    /**
     * Converts a linear value to a dB value.
     *
     * @param linearValue The linear value to convert
     * @param maxLevelDB The maximum possible dB value
     * @return The dB value
     */
    public static double valueToDb (final double linearValue, final double maxLevelDB)
    {
        final double dBValue = linearToDb (linearValue);
        return Math.pow (10, (dBValue - maxLevelDB) / 20.0);
    }


    private static double linearToDb (final double linearValue)
    {
        if (linearValue < 0.0000000298023223876953125)
            return -150;
        return Math.max (-150.0, Math.log (linearValue) * 8.6858896380650365530225783783321);
    }
}
