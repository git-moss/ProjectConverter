// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


/**
 * Helper functions for input and output streams.
 *
 * @author Jürgen Moßgraber
 */
public class StreamHelper
{
    /**
     * Private due to helper class.
     */
    private StreamHelper ()
    {
        // Intentionally empty
    }


    /**
     * Reads a 4-byte integer in little endian format (LSB first).
     *
     * @param input The output stream
     * @return The integer value
     * @throws IOException Stream error
     */
    public static final int readIntBigEndian (final InputStream input) throws IOException
    {
        final byte [] word = input.readNBytes (4);
        return word[0] << 24 | (word[1] & 0xFF) << 16 | (word[2] & 0xFF) << 8 | word[3] & 0xFF;
    }


    /**
     * Reads a 4-byte integer in little endian format (LSB first).
     *
     * @param input The output stream
     * @return The integer value
     * @throws IOException Stream error
     */
    public static final int readIntLittleEndian (final InputStream input) throws IOException
    {
        final byte [] word = input.readNBytes (4);
        return word[3] << 24 | (word[2] & 0xFF) << 16 | (word[1] & 0xFF) << 8 | word[0] & 0xFF;
    }


    /**
     * Reads a 4-byte integer in little endian format (LSB first).
     *
     * @param input The output stream
     * @return The integer value
     * @throws IOException Stream error
     */
    public static final long readLongLittleEndian (final InputStream input) throws IOException
    {
        final byte [] b = input.readNBytes (8);
        long result = 0;
        for (int i = Long.BYTES - 1; i >= 0; i--)
        {
            result <<= Byte.SIZE;
            result |= b[i] & 0xFF;
        }
        return result;
    }


    /**
     * Writes a 4-byte integer in big endian format (MSB first).
     *
     * @param output The output stream
     * @param value The integer value
     * @throws IOException Stream error
     */
    public static final void writeIntBigEndian (final OutputStream output, final int value) throws IOException
    {
        output.write (value >>> 24 & 0xFF);
        output.write (value >>> 16 & 0xFF);
        output.write (value >>> 8 & 0xFF);
        output.write (value & 0xFF);
    }


    /**
     * Writes a 4-byte integer in little endian format (LSB first).
     *
     * @param output The output stream
     * @param value The integer value
     * @throws IOException Stream error
     */
    public static final void writeIntLittleEndian (final OutputStream output, final int value) throws IOException
    {
        output.write (value & 0xFF);
        output.write (value >>> 8 & 0xFF);
        output.write (value >>> 16 & 0xFF);
        output.write (value >>> 24 & 0xFF);
    }


    /**
     * Reads a null terminated ASCII text.
     *
     * @param input The input from which to read
     * @return The text
     * @throws IOException Could not read the text
     */
    public static String readString (final InputStream input) throws IOException
    {
        final StringBuilder text = new StringBuilder ();
        int value;
        while ((value = input.read ()) > 0)
            text.append ((char) value);
        return text.toString ();
    }


    /**
     * Reads a terminated ASCII text with a fixed number of characters.
     *
     * @param input The input from which to read
     * @param length The number of characters (bytes) to read
     * @return The text
     * @throws IOException Could not read the text
     */
    public static String readString (final InputStream input, final int length) throws IOException
    {
        final StringBuilder text = new StringBuilder ();
        int value;
        int i = 0;
        while (i < length && (value = input.read ()) != 0)
        {
            text.append ((char) value);
            i++;
        }
        return text.toString ();
    }


    /**
     * Write an ASCII text to the output stream.
     *
     * @param output The output stream
     * @param text The text to write
     * @throws IOException Could not write
     */
    public static void writeString (final OutputStream output, final String text) throws IOException
    {
        for (int i = 0; i < text.length (); i++)
            output.write (text.charAt (i));
    }
}
