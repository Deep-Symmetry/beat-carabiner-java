package org.deepsymmetry.bcj;

/**
 * Tracks the synchronization, if any, being performed between the Ableton Link network and the Pro DJ Link network.
 */
public enum SyncMode {
    /**
     * No synchronization is being performed.
     */
    OFF,

    /**
     * External code will be calling {@link Core#lockTempo(double)} and {@link Core#unlockTempo()} to manipulate the
     * Ableton Link session.
     */
    MANUAL,

    /**
     * Ableton Link always follows the Pro DJ Link network, and we do not attempt to control other players on that
     * network.
     */
    PASSIVE,

    /**
     * Bidirectional, determined by the Master and Sync states of players on the DJ Link network, including
     * Beat Link’s {@code VirtualCDJ}, which stands in for the Ableton Link session.
     */
    FULL
}
