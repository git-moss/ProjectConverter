package de.mossgrabers.dawconverters.reaper;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ReaperProject
{
    private static final String  PROJECT_CHUNK  = "REAPER_PROJECT";
    private static final char    START_OF_CHUNK = '<';
    private static final char    END_OF_CHUNK   = '>';
    private static final Pattern LINE_PATTERN   = Pattern.compile ("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
    private static final Object  CRLF           = "\r\n";


    private ReaperProject ()
    {
        // Intentionally empty
    }


    public static Chunk parse (final List<String> lines) throws ParseException
    {
        final boolean isEmpty = lines.isEmpty ();
        if (isEmpty || !lines.get (0).startsWith (START_OF_CHUNK + PROJECT_CHUNK))
            throw new ParseException ("No Reaper file. Project chunk not found.", 0);

        final Chunk rootChunk = new Chunk ();
        parseChunk (rootChunk, lines, 0);
        return rootChunk;
    }


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
            if (line.length () == 1 && line.charAt (0) == END_OF_CHUNK)
                return index;

            // Sub-chunk?
            final Node node;
            if (line.indexOf (START_OF_CHUNK) != -1)
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


    private static Node parseNode (final Node node, final String line)
    {
        final List<String> paramParts = parseLine (line);
        node.setName (paramParts.get (0));
        node.addParameters (paramParts.subList (1, paramParts.size ()));
        return node;
    }


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


    public static String format (Chunk rootChunk)
    {
        final StringBuilder result = new StringBuilder ();
        formatChunk (result, rootChunk);
        return result.toString ();
    }


    private static void formatChunk (final StringBuilder result, final Chunk chunk)
    {
        final StringBuilder line = new StringBuilder ().append (START_OF_CHUNK);
        formatNode (line, chunk);

        final StringBuilder subResult = new StringBuilder ();

        for (final Node node: chunk.getChildNodes ())
        {
            if (node instanceof Chunk subChunk)
                formatChunk (subResult, subChunk);
            else
                formatNode (subResult, node);
        }

        result.append (line).append (subResult.toString ().indent (2)).append (END_OF_CHUNK).append (CRLF);
    }


    private static void formatNode (final StringBuilder result, final Node node)
    {
        result.append (node.getName ());

        for (String param: node.getParameters ())
            result.append (' ').append (param);

        result.append (CRLF);
    }
}
