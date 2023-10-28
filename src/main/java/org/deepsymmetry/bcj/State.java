package org.deepsymmetry.bcj;

import org.apiguardian.api.API;

import static org.apiguardian.api.API.Status.INTERNAL;

/**
 * An immutable value class representing a snapshot of the synchronization state.
 */
@API(status = API.Status.MAINTAINED)
public class State {

    /**
     * The port on which the Carabiner daemon is listening.
     */
    public final int port;

    /**
     * The estimated latency in milliseconds between when a beat is played on a CDJ and when we receive the packet
     * that reports this has happened. Negative values mean we are seeing the packets before the beats occur.
     */
    public final int latency;

    /**
     * The synchronization we are currently trying to maintain between the Ableton Link and Pro DJ Link networks,
     * if any.
     */
    public final SyncMode syncMode;

    /**
     * Whether the Ableton Link and Pro DJ Link timelines should be synchronized at the level of full musical measures
     * rather than individual beats.
     */
    public final boolean syncToBars;

    /**
     * Whether we are currently connected to a Carabiner daemon, and thus able to perform synchronization.
     */
    public final boolean running;

    /**
     * The current tempo reported by the Ableton Link network, will be {@code null} if {@link #running} is
     * {@code false}.
     */
    public final Double linkTempo;

    /**
     * The number of peers that the Ableton Link network reports are connected, will be {@code null} if
     * {@link #running} is {@code false}.
     */
    public final Integer linkPeers;

    /**
     * If we have been told to lock the Ableton Link tempo to a particular value, this will hold that value in beats
     * per minute; otherwise, it will be {@code null}.
     */
    public final Double targetTempo;

    /**
     * Constructor sets all the immutable data values.
     *
     * @param port the port on which the Carabiner daemon is listening
     * @param latency estimated latency between actual beats and received beat packets
     * @param syncMode what kind of synchronization, if any, we are performing
     * @param syncToBars whether we are synchronizing at the level of musical measures
     * @param running whether we have an active connection to a Carabiner daemon
     * @param linkTempo the tempo reported by the Ableton Link network if we are running
     * @param linkPeers the peer count reported by the Ableton Link network if we are running
     * @param targetTempo the tempo in BPM, if any, we are forcing Ableton Link to maintain
     */
    @API(status = INTERNAL)
    State(int port, int latency, SyncMode syncMode, boolean syncToBars, boolean running,
                 Double linkTempo, Integer linkPeers, Double targetTempo) {
        this.port = port;
        this.latency = latency;
        this.syncMode = syncMode;
        this.syncToBars = syncToBars;
        this.running = running;
        this.linkTempo = linkTempo;
        this.linkPeers = linkPeers;
        this.targetTempo = targetTempo;
    }
}
