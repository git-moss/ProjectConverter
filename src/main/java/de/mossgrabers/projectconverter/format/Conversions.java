// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
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
     * Converts a dB value to linear value.
     *
     * @param valueDb The dB value to convert
     * @param maxLevelDB The maximum possible dB value
     * @return The linear value
     */
    public static double dBToValue (final double valueDb, final double maxLevelDB)
    {
        final double dbValue = 20 * Math.log10 (valueDb) + maxLevelDB;
        return dbToLinear (dbValue);
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
