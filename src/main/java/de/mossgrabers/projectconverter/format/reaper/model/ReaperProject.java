// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper.model;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Support for parsing and formatting Reaper project files.
 *
 * @author Jürgen Moßgraber
 */
public class ReaperProject
{
    private static final String  PROJECT_CHUNK  = "REAPER_PROJECT";
    private static final char    START_OF_CHUNK = '<';
    private static final char    END_OF_CHUNK   = '>';
    private static final Pattern LINE_PATTERN   = Pattern.compile ("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
    private static final Object  CRLF           = "\r\n";


    /**
     * Constructor.
     */
    private ReaperProject ()
    {
        // Intentionally empty
    }


    /**
     * Parses the lines of a Reaper project files into its' parts the so-called chunks.
     *
     * @param lines The lines to parse
     * @return The parsed chunks
     * @throws ParseException Error during parsing
     */
    public static Chunk parse (final List<String> lines) throws ParseException
    {
        final boolean isEmpty = lines.isEmpty ();
        if (isEmpty || !lines.get (0).startsWith (START_OF_CHUNK + PROJECT_CHUNK))
            throw new ParseException ("No Reaper file. Project chunk not found.", 0);

        final Chunk rootChunk = new Chunk ();
        parseChunk (rootChunk, lines, 0);
        return rootChunk;
    }


    /**
     * Parses a chunk.
     *
     * @param chunk The chunk to fill
     * @param lines The lines to parse from
     * @param lineIndex The current index of the line to parse
     * @return The index of the last parsed chunk
     * @throws ParseException Error during parsing
     */
    private static int parseChunk (final Chunk chunk, final List<String> lines, final int lineIndex) throws ParseException
    {
        // Parse chunk name and attributes
        String line = lines.get (lineIndex).trim ();
        parseNode (chunk, line.substring (1));

        // Parse all parameters of the chunk with their attributes and all sub-chunks
        int index = lineIndex + 1;
        while (index < lines.size ())
        {
            line = lines.get (index).trim ();

            // Are we finished with the chunk?
            final int length = line.length ();
            if (length == 1 && line.charAt (0) == END_OF_CHUNK)
                return index;

            // Sub-chunk?
            final Node node;
            if (length > 0 && line.charAt (0) == START_OF_CHUNK)
            {
                final Chunk subChunk = new Chunk ();
                index = parseChunk (subChunk, lines, index);
                node = subChunk;
            }
            else
                node = parseNode (new Node (), line);

            chunk.addChildNode (node);
            index++;
        }

        throw new ParseException ("Unsound file. Chunk not closed.", 0);
    }


    /**
     * Parses a node (name/values pair) of a chunk.
     *
     * @param node The node to fill
     * @param line The line to parse from
     * @return The node for convenience
     */
    private static Node parseNode (final Node node, final String line)
    {
        final List<String> paramParts = parseLine (line);
        node.setLine (line);
        if (!paramParts.isEmpty ())
        {
            node.setName (paramParts.get (0));
            node.addParameters (paramParts.subList (1, paramParts.size ()));
        }
        return node;
    }


    /**
     * Splits the line of a node into its' parts. Handles single and double quotes.
     *
     * @param line The line to split
     * @return The parts
     */
    private static List<String> parseLine (final String line)
    {
        final List<String> parts = new ArrayList<> ();
        final Matcher matcher = LINE_PATTERN.matcher (line);
        while (matcher.find ())
        {
            if (matcher.group (1) != null)
            {
                // Add double-quoted string without the quotes
                parts.add (matcher.group (1));
            }
            else if (matcher.group (2) != null)
            {
                // Add single-quoted string without the quotes
                parts.add (matcher.group (2));
            }
            else
            {
                // Add unquoted word
                parts.add (matcher.group ());
            }
        }
        return parts;
    }


    /**
     * Formats the root chunk as a Reaper project file.
     *
     * @param rootChunk The root chunk
     * @return The formatted file content
     */
    public static String format (final Chunk rootChunk)
    {
        final StringBuilder result = new StringBuilder ();
        formatChunk (result, rootChunk);
        return result.toString ();
    }


    /**
     * Format recursively the chunk and all sub-chunks.
     *
     * @param result Where to append the formatted sub-chunks
     * @param chunk The chunk to format
     */
    private static void formatChunk (final StringBuilder result, final Chunk chunk)
    {
        final StringBuilder line = new StringBuilder ().append (START_OF_CHUNK);
        formatNode (line, chunk);

        final StringBuilder subResult = new StringBuilder ();

        for (final Node node: chunk.getChildNodes ())
        {
            if (node instanceof final Chunk subChunk)
                formatChunk (subResult, subChunk);
            else
                formatNode (subResult, node);
        }

        result.append (line).append (subResult.toString ().indent (2)).append (END_OF_CHUNK).append (CRLF);
    }


    /**
     * Format one node (one name/values line).
     *
     * @param result Where to add the formatted line
     * @param node The node to format
     */
    private static void formatNode (final StringBuilder result, final Node node)
    {
        result.append (node.getName ());

        for (final String param: node.getParameters ())
        {
            if (param == null)
                continue;
            result.append (' ');
            if (param.isBlank () || param.contains (" ") || param.contains ("/"))
                result.append ('"').append (param).append ('"');
            else
                result.append (param);
        }

        result.append (CRLF);
    }
}
