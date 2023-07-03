// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2023
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


/**
 * Tags used in Reaper project files.
 *
 * @author Jürgen Moßgraber
 */
public class ReaperTags
{
    protected static final String    PROJECT_ROOT               = "REAPER_PROJECT";
    protected static final String    PROJECT_TEMPO              = "TEMPO";
    protected static final String    PROJECT_RENDER_METADATA    = "RENDER_METADATA";
    protected static final String    PROJECT_AUTHOR             = "AUTHOR";
    protected static final String    PROJECT_NOTES              = "NOTES";
    protected static final String    PROJECT_TIME_LOCKMODE      = "TIMELOCKMODE";
    protected static final String    PROJECT_TIME_ENV_LOCKMODE  = "TEMPOENVLOCKMODE";
    protected static final String    PROJECT_MARKER             = "MARKER";
    protected static final String    PROJECT_TEMPO_ENVELOPE     = "TEMPOENVEX";

    protected static final String    METADATA_TAG               = "TAG";

    protected static final String    MASTER_COLOR               = "MASTERPEAKCOL";
    protected static final String    MASTER_NUMBER_OF_CHANNELS  = "MASTER_NCH";
    protected static final String    MASTER_MUTE_SOLO           = "MASTERMUTESOLO";
    protected static final String    MASTER_VOLUME_PAN          = "MASTER_VOLUME";
    protected static final String    MASTER_CHUNK_FXCHAIN       = "MASTERFXLIST";
    protected static final String    MASTER_VOLUME_ENVELOPE     = "MASTERVOLENV2";
    protected static final String    MASTER_PANORAMA_ENVELOPE   = "MASTERPANENV2";

    protected static final String    CHUNK_TRACK                = "TRACK";
    protected static final String    TRACK_NAME                 = "NAME";
    protected static final String    TRACK_COLOR                = "PEAKCOL";
    protected static final String    TRACK_STRUCTURE            = "ISBUS";
    protected static final String    TRACK_NUMBER_OF_CHANNELS   = "NCHAN";
    protected static final String    TRACK_MUTE_SOLO            = "MUTESOLO";
    protected static final String    TRACK_VOLUME_PAN           = "VOLPAN";
    protected static final String    TRACK_AUX_RECEIVE          = "AUXRECV";

    protected static final String    TRACK_VOLUME_ENVELOPE      = "VOLENV2";
    protected static final String    TRACK_PANORAMA_ENVELOPE    = "PANENV2";
    protected static final String    TRACK_MUTE_ENVELOPE        = "MUTEENV";
    protected static final String    TRACK_AUX_ENVELOPE         = "AUXVOLENV";
    protected static final String    ENVELOPE_POINT             = "PT";

    protected static final String    CHUNK_ITEM                 = "ITEM";
    protected static final String    ITEM_NAME                  = "NAME";
    protected static final String    ITEM_POSITION              = "POSITION";
    protected static final String    ITEM_LENGTH                = "LENGTH";
    protected static final String    ITEM_FADEIN                = "FADEIN";
    protected static final String    ITEM_FADEOUT               = "FADEOUT";
    protected static final String    ITEM_SAMPLE_OFFSET         = "SOFFS";
    protected static final String    ITEM_PLAYRATE              = "PLAYRATE";
    protected static final String    ITEM_LOOP                  = "LOOP";
    protected static final String    CHUNK_ITEM_SOURCE          = "SOURCE";
    protected static final String    SOURCE_HASDATA             = "HASDATA";
    protected static final String    SOURCE_FILE                = "FILE";

    protected static final String    CHUNK_FXCHAIN              = "FXCHAIN";
    protected static final String    FXCHAIN_BYPASS             = "BYPASS";
    protected static final String    FXCHAIN_PARAMETER_ENVELOPE = "PARMENV";
    protected static final String    CHUNK_CLAP                 = "CLAP";
    protected static final String    CHUNK_VST                  = "VST";

    protected static final String    PLUGIN_CLAP                = "CLAP";
    protected static final String    PLUGIN_CLAP_INSTRUMENT     = "CLAPi";
    protected static final String    PLUGIN_VST_2               = "VST";
    protected static final String    PLUGIN_VST_2_INSTRUMENT    = "VSTi";
    protected static final String    PLUGIN_VST_3               = "VST3";
    protected static final String    PLUGIN_VST_3_INSTRUMENT    = "VST3i";

    private static final Set<String> INSTRUMENT_TAGS            = new HashSet<> ();

    static
    {
        Collections.addAll (INSTRUMENT_TAGS, PLUGIN_CLAP_INSTRUMENT, PLUGIN_VST_2_INSTRUMENT, PLUGIN_VST_3_INSTRUMENT);
    }


    /**
     * Constructor.
     */
    private ReaperTags ()
    {
        // Intentionally empty
    }


    /**
     * Is the given tag an instrument plugin tag?
     *
     * @param tag The tag to check
     * @return True if it is an instrument plugin tag
     */
    public static boolean isInstrumentPlugin (final String tag)
    {
        return INSTRUMENT_TAGS.contains (tag);
    }
}
