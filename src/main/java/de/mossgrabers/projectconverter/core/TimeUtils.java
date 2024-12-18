// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.core;

import java.util.List;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.ClipSlot;
import com.bitwig.dawproject.timeline.TimeUnit;
import com.bitwig.dawproject.timeline.Timeline;
import com.bitwig.dawproject.timeline.Warps;


/**
 * Utility functions for handling time units in DAWproject.
 *
 * @author Jürgen Moßgraber
 */
public class TimeUtils
{
    /**
     * Private constructor since this is a utility class.
     */
    private TimeUtils ()
    {
        // Intentionally empty
    }


    /**
     * Check for which time unit is set for the arrangement if any.
     *
     * @param arrangement The arrangement to check
     * @return True if the time unit is in beats otherwise seconds. True if no setting is found
     */
    public static boolean getArrangementTimeUnit (final Arrangement arrangement)
    {
        return arrangement == null || arrangement.lanes == null || updateIsBeats (arrangement.lanes, true);
    }


    /**
     * Checks if the given timeline object has a time unit setting, if not the current time unit is
     * returned.
     *
     * @param timeline The timeline to check
     * @param isBeats The current setting, true if set to Beats
     * @return True if beats otherwise seconds
     */
    public static boolean updateIsBeats (final Timeline timeline, final boolean isBeats)
    {
        return timeline.timeUnit == null ? isBeats : timeline.timeUnit == TimeUnit.BEATS;
    }


    /**
     * Checks if the given warps object has a time unit setting, if not the current time unit is
     * returned.
     *
     * @param warps The warps object to check
     * @param isBeats The current setting, true if set to Beats
     * @return True if beats otherwise seconds
     */
    public static boolean updateWarpsTimeIsBeats (final Warps warps, final boolean isBeats)
    {
        return warps.contentTimeUnit == null ? isBeats : warps.contentTimeUnit == TimeUnit.BEATS;
    }


    /**
     * Checks if the given clip object has a time unit setting, if not the current time unit is
     * returned.
     *
     * @param clip The clip object to check
     * @param isBeats The current setting, true if set to Beats
     * @return True if beats otherwise seconds
     */
    public static boolean updateClipContentTimeIsBeats (final Clip clip, final boolean isBeats)
    {
        return clip.contentTimeUnit == null ? isBeats : clip.contentTimeUnit == TimeUnit.BEATS;
    }


    /**
     * Set the time unit for a timeline.
     *
     * @param timeline The timeline for which to set the time unit
     * @param isBeats Set to beats if true otherwise seconds
     */
    public static void setTimeUnit (final Timeline timeline, final boolean isBeats)
    {
        timeline.timeUnit = isBeats ? TimeUnit.BEATS : TimeUnit.SECONDS;
    }


    /**
     * Get the duration of the clip.
     *
     * @param clip The clip
     * @return The duration or -1 if not set
     */
    public static double getDuration (final Clip clip)
    {
        return clip.duration == null ? -1 : clip.duration.doubleValue ();
    }


    /**
     * Get the length of the play start to end of the clip.
     *
     * @param clip The clip
     * @return The duration of the play range
     */
    public static double getPlayRange (final Clip clip)
    {
        if (clip.playStop == null)
            return -1;
        return clip.playStop.doubleValue () - (clip.playStart == null ? 0 : clip.playStart.doubleValue ());
    }


    /**
     * Find the clip with the longest duration of all the given clips in the clip slots and returns
     * it's duration.
     *
     * @param clipSlots The slots to check
     * @return The longest duration
     */
    public static double getMaxDuration (final List<ClipSlot> clipSlots)
    {
        double maxDuration = 0;
        for (final ClipSlot clipSlot: clipSlots)
        {
            final double duration = getDuration (clipSlot.clip);
            if (duration > maxDuration)
                maxDuration = duration;
        }
        return maxDuration;
    }
}
