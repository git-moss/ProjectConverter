// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2022
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper.model;

import java.text.ParseException;
import java.util.List;


/**
 * A MIDI event in a clip.
 *
 * The start of a note: "E 0 90 24 2d". The end of a note: "E 480 80 24 00"
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public class ReaperMidiEvent
{
    private boolean isMidiEvent = false;

    private long    position;
    private long    offset;
    private int     code;
    private int     channel;
    private int     data1;
    private int     data2;


    /**
     * Constructor.
     *
     * @param noteNode The node of the note
     * @throws ParseException Could not parse the node
     */
    public ReaperMidiEvent (final Node noteNode) throws ParseException
    {
        final String name = noteNode.getName ();
        if (!"E".equalsIgnoreCase (name))
            return;

        final List<String> nodeParts = noteNode.getParameters ();
        if (nodeParts.size () < 4)
            return;

        try
        {
            this.offset = Long.parseLong (nodeParts.get (0));

            final int status = Integer.parseInt (nodeParts.get (1), 16);
            this.code = status & 0xF0;
            this.channel = status & 0xF;
            this.data1 = Integer.parseInt (nodeParts.get (2), 16);
            this.data2 = Integer.parseInt (nodeParts.get (3), 16);

            this.isMidiEvent = true;
        }
        catch (final NumberFormatException ex)
        {
            throw new ParseException ("Malformed MIDI event in MIDI source section.", 0);
        }
    }


    /**
     * Constructor.
     *
     * @param position The position of the MIDI event
     * @param channel The MIDI channel of the event
     * @param code The status code of the event (without the channel)
     * @param data1 The first data byte of the event
     * @param data2 The second data byte of the event
     */
    public ReaperMidiEvent (final long position, final int channel, final int code, final int data1, final int data2)
    {
        this.position = position;
        this.channel = channel;
        this.code = code;
        this.data1 = data1;
        this.data2 = data2;
    }


    /**
     * Convert the values back to a note node.
     *
     * @return The node
     */
    public Node toNode ()
    {
        final Node node = new Node ();

        node.setName ("E");

        final List<String> parameters = node.getParameters ();

        parameters.add (Long.toString (this.offset));
        parameters.add (String.format ("%02x", Integer.valueOf (this.code + this.channel)));
        parameters.add (String.format ("%02x", Integer.valueOf (this.data1)));
        parameters.add (String.format ("%02x", Integer.valueOf (this.data2)));

        return node;
    }


    /**
     * Is it a MIDI event?
     *
     * @return True if yes
     */
    public boolean isMidiEvent ()
    {
        return this.isMidiEvent;
    }


    /**
     * Get the start of the event.
     *
     * @return The start
     */
    public long getPosition ()
    {
        return this.position;
    }


    /**
     * Set the start of the event.
     *
     * @param position The start
     */
    public void setPosition (final long position)
    {
        this.position = position;
    }


    /**
     * The offset of the event in PPQ (ticks per quarters) to the previous MIDI event. The maximum
     * number of ticks per quarter is defined for the source of this event (normally 960).
     *
     * @return The offset
     */
    public long getOffset ()
    {
        return this.offset;
    }


    /**
     * Set the offset of the event to the previous MIDI event.
     *
     * @param offset The offset in PPQ (ticks per quarters)
     */
    public void setOffset (final long offset)
    {
        this.offset = offset;
    }


    /**
     * Get the MIDI event code.
     *
     * @return The code
     */
    public int getCode ()
    {
        return this.code;
    }


    /**
     * Get the channel of the MIDI event.
     *
     * @return The channel
     */
    public int getChannel ()
    {
        return this.channel;
    }


    /**
     * Get the first value byte of the MIDI event.
     *
     * @return The byte
     */
    public int getData1 ()
    {
        return this.data1;
    }


    /**
     * Get the second value byte of the MIDI event.
     *
     * @return The byte
     */
    public int getData2 ()
    {
        return this.data2;
    }
}
