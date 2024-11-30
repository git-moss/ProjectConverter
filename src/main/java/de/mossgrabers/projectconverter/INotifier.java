// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2024
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter;

/**
 * Interface to notify the user about notification messages.
 *
 * @author Jürgen Moßgraber
 */
public interface INotifier
{
    /**
     * Log the message to the notifier.
     *
     * @param messageID The ID of the message to get
     * @param replaceStrings Replaces the %1..%n in the message with the strings
     */
    void log (String messageID, String... replaceStrings);


    /**
     * Log the message to the notifier.
     *
     * @param messageID The ID of the message to get
     * @param replaceStrings Replaces the %1..%n in the message with the strings
     */
    void logError (String messageID, String... replaceStrings);


    /**
     * Log the message to the notifier.
     *
     * @param messageID The ID of the message to get
     * @param throwable A throwable
     */
    void logError (String messageID, Throwable throwable);


    /**
     * Log the message to the notifier.
     *
     * @param throwable A throwable
     */
    void logError (Throwable throwable);


    /**
     * Update the button execution states.
     *
     * @param canClose Execution can be closed
     */
    void updateButtonStates (boolean canClose);


    /**
     * Check if the process should be cancelled.
     *
     * @return True if the process should be cancelled.
     */
    boolean isCancelled ();
}
