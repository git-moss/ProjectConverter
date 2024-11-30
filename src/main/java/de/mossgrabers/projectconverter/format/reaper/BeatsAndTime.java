// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.reaper;

import java.util.List;

import de.mossgrabers.projectconverter.core.TempoChange;


/**
 * Little helper class to aggregate time related parameters.
 *
 * @author Jürgen Moßgraber
 */
class BeatsAndTime
{
    boolean           sourceIsBeats;
    boolean           sourceIsEnvelopeBeats;
    boolean           destinationIsBeats;
    List<TempoChange> tempoEnvelope;


    BeatsAndTime duplicateWithNewSource (final boolean newSourceIsBeats)
    {
        final BeatsAndTime duplicate = new BeatsAndTime ();
        duplicate.sourceIsBeats = newSourceIsBeats;
        duplicate.destinationIsBeats = this.destinationIsBeats;
        duplicate.tempoEnvelope = this.tempoEnvelope;
        return duplicate;
    }
}