package org.deepsymmetry.bcj;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.Beat;
import org.deepsymmetry.beatlink.MasterAdapter;
import org.deepsymmetry.beatlink.MasterListener;
import org.deepsymmetry.beatlink.VirtualCdj;
import org.deepsymmetry.electro.Metronome;
import org.deepsymmetry.electro.Snapshot;
import org.deepsymmetry.libcarabiner.Message;
import org.deepsymmetry.libcarabiner.Runner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.bpsm.edn.Symbol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;
import static org.apiguardian.api.API.Status.MAINTAINED;

/**
 * Manages tempo synchronization between an Ableton Link session and a Pro DJ Link network.
 */
@API(status = MAINTAINED)
public class Core {

    private static final Logger logger = LoggerFactory.getLogger(Core.class);

    /**
     * Holds the singleton instance of this class.
     */
    private static final Core ourInstance = new Core();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class that exists.
     */
    @API(status = MAINTAINED)
    public static Core getInstance() {
        return ourInstance;
    }

    /**
     * Private constructor prevents instantiation other than the singleton instance.
     */
    private Core() {
        // Configure and start our daemon synchronization thread that periodically aligns the Pro DJ Link tempo and
        // beat grid to the Ableton Link session timeline when our sync mode requires it.
        Thread fullSyncDaemon = new Thread(() -> {
            while (true) {
                try {
                    // If we are due to send a probe to align the Virtual CDJ beat grid to Ableton Link’s, do so.
                    if (syncMode.get() == SyncMode.FULL && VirtualCdj.getInstance().isTempoMaster()) {
                        alignPioneerPhaseToAbleton();
                    }
                    //noinspection BusyWait
                    Thread.sleep(200);
                } catch (Exception e) {
                    logger.error("Problem aligning Pro DJ Link beat grid with Ableton Link timeline:", e);
                }
            }
        }, "Beat Carabiner Phase Alignment");
        fullSyncDaemon.setPriority(Thread.MIN_PRIORITY);
        fullSyncDaemon.setDaemon(true);
        fullSyncDaemon.start();
    }

    /**
     * The amount by which the Link tempo can differ from our target tempo without triggering an adjustment.
     */
    private static final double BPM_TOLERANCE = 0.00001;

    /**
     * The amount by which the start of a beat can be off without triggering an adjustment. This can’t be
     * larger than the normal beat packet jitter without causing spurious readjustments.
     */
    private static final double SKEW_TOLERANCE = 0.0166;

    /**
     * The number of milliseconds the connection attempt to the Carabiner daemon can take before we give up on
     * being able to reach it.
     */
    private static final int CONNECT_TIMEOUT = 5000;

    /**
     * The number of milliseconds that reads from the Carabiner daemon should block so that we can periodically check
     * if we have been instructed to close the connection.
     */
    private static final int READ_TIMEOUT = 2000;

    /**
     * The port on which we should communicate with the local Carabiner daemon.
     */
    private final AtomicInteger carabinerPort = new AtomicInteger(17000);

    /**
     * The estimated latency, in milliseconds, between an actual beat played on a CDJ and when we receive
     * the packet.
     */
    private final AtomicInteger latency = new AtomicInteger(1);

    /**
     * The type of synchronization, if any, being performed between Ableton Link and Pro DJ Link.
     */
    private final AtomicReference<SyncMode> syncMode = new AtomicReference<>(SyncMode.OFF);

    /**
     * Whether we started our own instance of Carabiner, so we should shut it down when finished.
     */
    private final AtomicBoolean embeddedCarabiner = new AtomicBoolean(false);

    /**
     * Whether we should sync to entire musical bars rather than beats.
     */
    private final AtomicBoolean syncToBars = new AtomicBoolean(false);

    /**
     * Holds the connection to Carabiner while we are active.
     */
    private final AtomicReference<Socket> carabinerSocket = new AtomicReference<>(null);

    /**
     * The number of peers that are participating in the Ableton Link network. Zero if we are not connected.
     */
    private final AtomicInteger linkPeers = new AtomicInteger(0);

    /**
     * Holds the current tempo reported on the Ableton Link network. Only valid when we are connected.
     */
    private final AtomicReference<Double> linkBpm = new AtomicReference<>(null);

    /**
     * Check whether there is currently an active connection to a Carabiner daemon, so synchronization is possible.
     *
     * @return {@code true} if we are connected to a Carabiner daemon and can synchronize tempos.
     */
    public boolean isActive() {
        return carabinerSocket.get() != null;
    }

    /**
     * Check whether we have an active connection and are in any sync mode other than {@link SyncMode#OFF}.
     *
     * @return {@code true} if some form of synchronization is taking place.
     */
    @API(status = MAINTAINED)
    public synchronized boolean isSyncEnabled() {
        return isActive() && syncMode.get() != SyncMode.OFF;
    }

    /**
     * Set the port number to be used to connect to Carabiner. Can only be called when not connected.
     *
     * @param port a TCP port number, which must be between 1 and 65,535.
     *
     * @throws IllegalStateException if called while connected to a Carabiner daemon
     * @throws IllegalArgumentException if port is less than 1 or more than 65,535
     */
    @API(status = MAINTAINED)
    public synchronized void setCarabinerPort(int port) {
        if (isActive()) {
            throw new IllegalStateException("Cannot set port when already connected.");
        }
        if ((port < 1) || (port > 65535)) {
            throw new IllegalArgumentException("port must be in range 1-65535");
        }
        carabinerPort.set(port);
    }

    /**
     * Gets the port number that is being used to connect to Carabiner.
     *
     * @return a TCP port number, between 1 and 65,535
     */
    @API(status = MAINTAINED)
    public int getCarabinerPort() {
        return carabinerPort.get();
    }

    /**
     * Sets the estimated latency in milliseconds between an actual beat played on a CDJ and when we receive
     * the packet.
     *
     * @param latency estimated latency in milliseconds until we receive packets reporting a beat has occurred
     */
    @API(status = MAINTAINED)
    public void setLatency(int latency) {
        if ((latency < 0) || (latency > 1000)) {
            throw new IllegalArgumentException("latency must be in range 1-1000");
        }
        this.latency.set(latency);
    }

    /**
     * Get the estimated latency in milliseconds between an actual beat played on a CDJ and when we receive
     * the packet.
     *
     * @return estimated latency in milliseconds until we receive packets reporting a beat has occurred
     */
    @API(status = MAINTAINED)
    public int getLatency() {
        return latency.get();
    }

    /**
     * Set whether we should synchronize the Ableton Link and Pioneer timelines at the level of entire measures,
     * rather than individual beats.
     *
     * @param syncToBars when {@code true}, synchronization will be at the level of musical bars rather than beats
     */
    @API(status = MAINTAINED)
    public void setSyncToBars (boolean syncToBars) {
        this.syncToBars.set(syncToBars);
    }

    /**
     * Check whether we should synchronize the Ableton Link and Pioneer timelines at the level of entire measures,
     * rather than individual beats.
     *
     * @return an indication of whether synchronization will be at the level of musical bars rather than beats
     */
    @API(status = MAINTAINED)
    public boolean getSyncToBars() {
        return syncToBars.get();
    }

    /**
     * Throws an exception if there is no active connection.
     */
    private void ensureActive() {
        if (!isActive()) {
            throw new IllegalStateException("No active Carabiner connection.");
        }
    }

    /**
     * Sends a message to the active Carabiner daemon.
     *
     * @param message the message to be sent
     * @throws IOException if there is a problem communicating with the daemon
     */
    private void sendMessage(String message) throws IOException {
        ensureActive();
        OutputStream os = carabinerSocket.get().getOutputStream();
        String terminated = message + "\n";
        os.write(terminated.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * When we are trying to enforce a tempo on the Ableton Link network, this holds the desired beats per minute.
     */
    private final AtomicReference<Double> targetBpm = new AtomicReference<>(null);

    /**
     * If we are supposed to enforce a tempo on the Ableton Link network, make sure the Link tempo is close enough
     * to our target value, and adjust it if needed. Otherwise, if the Virtual CDJ is the tempo master, set its tempo
     * to match Ableton Link's.
     *
     * @throws IOException if there is a problem communicating with the Carabiner daemon.
     */
    private synchronized void checkLinkTempo() throws IOException {
        double tempo = (linkBpm.get() != null)? linkBpm.get() : 0.0;
        Double target = targetBpm.get();
        if (target != null) {
            if (Math.abs(tempo - target) > BPM_TOLERANCE) {
                sendMessage("bpm " + target);
            }
            if (VirtualCdj.getInstance().isTempoMaster() && (tempo > 0.0)) {
                VirtualCdj.getInstance().setTempo(tempo);
            }
        }
    }

    /**
     * Keeps track of the registered state listeners.
     */
    private final Set<StateListener> stateListeners = Collections.newSetFromMap((new ConcurrentHashMap<>()));

    /**
     * Adds the specified state listener to receive the current connection state whenever we receive status updates
     * from Carabiner. If {@code listener} is {@code null} or already present in the list of registered listeners,
     * no exception is thrown, and no action is performed.
     *
     * <p>To reduce latency, listeners are called on the same thread which is receiving updates from Carabiner.
     * If you are going to do anything that takes significant time or might block, you must do so on another thread.
     * If you are going to interact with user interface objects in response to such events, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * @param listener the state listener to add
     */
    @API(status = MAINTAINED)
    public void addStateListener(StateListener listener) {
        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    /**
     * Removes the specified state listener so it no longer receives connection state updates when we receive
     * status updates from Carabiner.
     *
     * <p>If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * @param listener the state listener to remove
     */
    @API(status = MAINTAINED)
    public void removeStateListener(StateListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    /**
     * Get the set of state listeners that are currently registered.
     *
     * @return the currently registered state listeners
     */
    @API(status = MAINTAINED)
    public Set<StateListener> getStateListeners() {
        return Collections.unmodifiableSet(new HashSet<>(stateListeners));
    }

    /**
     * Send a state update to all registered listeners.
     *
     * @param state the current synchronization state
     */
    private void deliverStateUpdate(State state) {
        for (StateListener listener : getStateListeners()) {
            try {
                listener.carabinerStatusReceived(state);
            } catch (Throwable t) {
                logger.warn("Problem delivering state update to listener", t);
            }
        }
    }

    /**
     * Get the current synchronization state.
     *
     * @return the current configuration and state of synchronization
     */
    @API(status = MAINTAINED)
    public synchronized State getState() {
        if (!isActive()) {
            return new State(carabinerPort.get(), latency.get(), syncMode.get(), syncToBars.get(), false,
                    null, null,  null);
        }
        return new State(carabinerPort.get(), latency.get(), syncMode.get(), syncToBars.get(), true,
                linkBpm.get(), linkPeers.get(), targetBpm.get());
    }

    /**
     * Processes a status update from Carabiner. Calls any registered state listeners with the resulting state,
     * and performs any synchronization operations required by our current configuration.
     *
     * @param details the status response details received from Carabiner
     *
     * @throws IOException if there is a problem communicating with Carabiner
     */
    private void handleStatus(Map<String, Object>details) throws IOException {
        linkBpm.set((Double) details.get("bpm"));
        linkPeers.set((Integer) details.get("peers"));
        checkLinkTempo();
        deliverStateUpdate(getState());
    }

    /**
     * Holds the time in milliseconds for which a beat is being probed in the Ableton timeline.
     */
    private final AtomicLong abletonBeatTimeProbe = new AtomicLong();

    /**
     * Holds the beat number (within a measure) that is being probed for. If not {@code null}, we will
     * move the timeline by more than a beat if necessary to get the timelines aligned.
     */
    private final AtomicReference<Integer> abletonBeatNumberProbe = new AtomicReference<>();

    /**
     * Processes a beat probe response from Carabiner, adjusting the Ableton Link timeline if needed.
     *
     * @param details the beat probe response message details
     *
     * @throws IOException if there is a problem talking to Carabiner to resynchronize timelines
     */
    private synchronized void handleBeatAtTime(Map<String, Object> details) throws IOException {
        double beat = (Double) details.get("beat");
        long rawBeat = Math.round(beat);
        double beatSkew = beat % 1.0;
        Integer beatNumber = abletonBeatNumberProbe.get();
        long time = abletonBeatTimeProbe.get();
        long barSkew = 0;
        if (beatNumber != null) {
            barSkew = (beatNumber - 1) - (rawBeat % 4);
        }

        long adjustment = barSkew;
        if (adjustment <= -2) {
            adjustment += 4;
        }

        long targetBeat = rawBeat;
        if ((beatNumber != null) && (time == (Long) details.get("time"))) {
            targetBeat += adjustment;
        }
        if (targetBeat < 0) {
            targetBeat += 4;
        }

        if ((Math.abs(beatSkew) > SKEW_TOLERANCE) || (targetBeat != rawBeat)) {
            logger.info("Realigning to beat {} by {}", targetBeat, beatSkew);
            sendMessage("force-beat-at-time " + targetBeat + " " + details.get("when") + " 4.0");
        }
    }

    /**
     * Keeps track of the metronome snapshot associated with a phase probe request.
     */
    private final AtomicReference<Snapshot> phaseProbeSnapshot = new AtomicReference<>();

    /**
     * Holds the time for which a phase probe request was made.
     */
    private final AtomicLong phaseProbeTime = new AtomicLong();

    /**
     * Processes a phase probe response from Carabiner, adjusting the Pro DJ Link timeline if needed.
     *
     * @param details the phase probe response details
     */
    private synchronized void handlePhaseAtTime(Map<String, Object>details) {
        if (phaseProbeTime.get() == (Long) details.get("when")) {
            double phase = (Double) details.get("phase");
            Snapshot snapshot = phaseProbeSnapshot.get();
            double desiredPhase;
            double actualPhase;
            double phaseInterval;
            if (syncToBars.get()) {
                desiredPhase = phase / 4.0;
                actualPhase = snapshot.getBarPhase();
                phaseInterval = snapshot.getBarInterval();
            } else {
                desiredPhase = phase - (long) phase;
                actualPhase = snapshot.getBeatPhase();
                phaseInterval = snapshot.getBeatInterval();
            }
            double phaseDelta = Metronome.findClosestDelta(desiredPhase - actualPhase);
            int millisecondDelta = (int) (phaseDelta * phaseInterval);
            if (Math.abs(millisecondDelta) > 0) {
                // We should drift the Pro DJ Link timeline. But if this would cause us to skip or repeat a beat,
                // and we are shifting 1/5 of a beat or less, hold off until a safer moment.
                double beatPhase = VirtualCdj.getInstance().getPlaybackPosition().getBeatPhase();
                double beatDelta = phaseDelta;
                if (syncToBars.get()) {
                    beatDelta *= 4.0;
                }
                if (beatDelta > 0.0) {
                    beatDelta += 0.1;  // Account for sending lag.
                }

                if (Math.floor(beatPhase + beatDelta) == 0.0 ||  // We are staying in the same beat, we are fine
                        Math.abs(beatDelta) > 0.2) {  // We are moving more than 1/5 of a beat, so do it anyway
                    logger.info("Adjusting Pro DJ Link timeline, millisecondDelta: {}", millisecondDelta);
                    VirtualCdj.getInstance().adjustPlaybackPosition(millisecondDelta);
                }
            }
        } else {
            logger.warn("Ignoring phase-at-time response for time {} since was expecting {}", details.get("when"),
                    phaseProbeTime.get());
        }
    }

    /**
     * Processes the response to a recognized version command. Warns if Carabiner should be upgraded.
     *
     * @param version the version that Carabiner reported
     */
    private void handleVersion(String version) {
        logger.info("Connected to Carabiner daemon, version: {}", version);
        if (version.equals("1.1.0")) {
            logger.warn("Carabiner needs to be upgraded to at least version 1.1.1 to avoid sync glitches.");
        }
    }

    /**
     * Processes an unsupported command response from Carabiner. If it is in response to our version query,
     * warn the user that they should upgrade Carabiner.
     *
     * @param command the command that was not recognized
     */
    private void handleUnsupported(Symbol command) {
        if (command.equals(Symbol.newSymbol("version"))) {
            logger.warn("Carabiner needs to be upgraded to at least version 1.1.1 to avoid multiple issues.");
        } else {
            logger.error("Carabiner complained about not recognizing our command: {}", command);
        }
    }

    /**
     * Keeps track of the registered disconnection listeners.
     */
    private final Set<DisconnectionListener> disconnectionListeners = Collections.newSetFromMap((new ConcurrentHashMap<>()));

    /**
     * Adds the specified disconnection listener to be notified when we close our Carabiner connection.
     * If {@code listener} is {@code null} or already present in the list of registered listeners,
     * no exception is thrown, and no action is performed.
     *
     * <p>To reduce latency, listeners are called on the same thread which is receiving updates from Carabiner.
     * If you are going to do anything that takes significant time or might block, you must do so on another thread.
     * If you are going to interact with user interface objects in response to such events, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * @param listener the disconnection listener to add
     */
    @API(status = MAINTAINED)
    public void addDisconnectionListener(DisconnectionListener listener) {
        if (listener != null) {
            disconnectionListeners.add(listener);
        }
    }

    /**
     * Removes the specified disconnection listener so it no longer receives notifications when we close our
     * Carabiner connection.
     *
     * <p>If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * @param listener the disconnection listener to remove
     */
    @API(status = MAINTAINED)
    public void removeDisconnectionListener(DisconnectionListener listener) {
        if (listener != null) {
            disconnectionListeners.remove(listener);
        }
    }

    /**
     * Get the set of state listeners that are currently registered.
     *
     * @return the currently registered state listeners
     */
    @API(status = MAINTAINED)
    public Set<DisconnectionListener> getDisconnectionListeners() {
        return Collections.unmodifiableSet(new HashSet<>(disconnectionListeners));
    }

    /**
     * Send a disconnection update to all registered listeners.
     *
     * @param unexpected will be {@code true} if we did not request Carabiner to shut down
     */
    private void deliverDisconnectionNotice(boolean unexpected) {
        for (DisconnectionListener listener : getDisconnectionListeners()) {
            try {
                listener.connectionClosed(unexpected);
            } catch (Throwable t) {
                logger.warn("Problem delivering disconnection notice to listener", t);
            }
        }
    }

    /**
     * If we started the Carabiner server we are disconnecting from, shut it down, but do so on another thread,
     * and in several milliseconds, so our read loop has time to close from its end gracefully first.
     */
    private void shutdownEmbeddedCarabiner() {
        if (embeddedCarabiner.get()) {
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while sleeping before shutting down embedded Carabiner instance.");
                }
                Runner.getInstance().stop();
                embeddedCarabiner.set(false);
            }).start();
        }
    }

    /**
     * Counts how many times we have opened a new connection to Carabiner, so instances of the run loop can know
     * when they are obsolete and shut themselves down.
     */
    private final AtomicInteger connectionNumber = new AtomicInteger(0);

    /**
     * Builds loop that reads messages from Carabiner as long as it is supposed to be running, and takes appropriate
     * action on them.
     *
     * @param socket the socket on which Carabiner will send messages
     * @param runForConnectionNumber the number which identifies the connection for which this loop is running,
     *                               so it can end itself if we’ve moved on to a new one.
     */
    private Runnable buildResponseHandler(final Socket socket, final int runForConnectionNumber) {
        return new Runnable() {
            @SuppressWarnings("unchecked")
            @Override
            public void run() {
                try {
                    boolean unexpected = false;  // Tracks whether Carabiner unexpectedly closed its connection.
                    byte[] buffer = new byte[1024];
                    InputStream input = socket.getInputStream();
                    while (runForConnectionNumber == connectionNumber.get() && !socket.isClosed()) {
                        try {
                            int n = input.read(buffer);
                            if (n > 0 && runForConnectionNumber == connectionNumber.get()) {
                                // We got data, and were not told to shut down while reading.
                                String response = new String(buffer, StandardCharsets.UTF_8);
                                logger.debug("Received: {}", response);
                                Message message = new Message(response);
                                switch (message.messageType) {
                                    case "status":
                                        handleStatus((Map<String, Object>) message.details);
                                        break;

                                    case "beat-at-time":
                                        handleBeatAtTime((Map<String, Object>) message.details);
                                        break;

                                    case "phase-at-time":
                                        handlePhaseAtTime((Map<String, Object>) message.details);

                                    case "version":
                                        //noinspection DataFlowIssue
                                        handleVersion((String) message.details);
                                        break;

                                    case "unsupported":
                                        handleUnsupported((Symbol) message.details);

                                    default:
                                        logger.error("Unrecognized message from Carabiner: {}", response);
                                }
                            } else {
                                // We read zero, meaning the other side closed, or we have been instructed to end.
                                socket.close();
                                unexpected = isActive();
                            }
                        } catch (SocketTimeoutException e) {
                            logger.debug("Read from Carabiner timed out, checking if we should exit loop.");
                        } catch (Throwable t) {
                            logger.error("Problem reading from Carabiner.", t);
                        }
                    }
                    logger.info("Ending read loop from Carabiner.");
                    if (runForConnectionNumber == connectionNumber.get()) {
                        // We are causing the ending, because Carabiner closed its connection.
                        synchronized (this) {
                            shutdownEmbeddedCarabiner();
                            carabinerSocket.set(null);
                            linkBpm.set(null);
                            linkPeers.set(0);
                            deliverDisconnectionNotice(unexpected);
                            socket.close();
                        }
                    }
                } catch (Throwable t) {
                    logger.error("Problem managing Carabiner read loop.", t);
                }
            }
        };
    }

    /**
     * Closes any active Carabiner connection. The run loop will notice that its run ID is no longer current, and
     * gracefully terminate, closing its socket without processing any more responses. Also shuts down the embedded
     * Carabiner process if we started it.
     */
    @API(status = MAINTAINED)
    public synchronized void disconnect() {
        shutdownEmbeddedCarabiner();
        connectionNumber.incrementAndGet();
        carabinerSocket.set(null);
        linkBpm.set(null);
        linkPeers.set(0);
    }

    /**
     * Helper function that attempts to connect to the Carabiner daemon. If we just started an embedded
     * Carabiner daemon instance, keep trying to connect every ten milliseconds for up to two seconds,
     * to give it a chance to start up.
     *
     * @param embedded indicates whether we have started an embedded daemon to try to connect to.
     *
     * @throws IOException if something goes wrong trying to connect
     */
    private void connectInternal(boolean embedded) throws IOException {
        Socket socket;
        int tries = 200;
        do {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", carabinerPort.get()), CONNECT_TIMEOUT);
            } catch (ConnectException e) {
                socket = null;
                if (embedded && --tries > 0) {
                    try {
                        Thread.sleep(10);
                    } catch (Exception e2) {
                        logger.debug("Interrupted while sleeping to retry Carabiner connection.");
                    }
                } else {
                    throw e;
                }
            }
        } while (socket == null);

        // We have connected successfully.
        socket.setSoTimeout(READ_TIMEOUT);
        carabinerSocket.set(socket);
        embeddedCarabiner.set(embedded);
        new Thread(buildResponseHandler(socket, connectionNumber.incrementAndGet())).start();
    }

    /**
     * Try to establish a connection to Carabiner. Does nothing if we already have one.
     * First checks to see if there is already an independently managed instance of Carabiner
     * running on the configured port (see {@link #setCarabinerPort(int)}), and if so, simply
     * uses that. Otherwise, checks whether we are on a platform where we can install and run
     * our own temporary copy of Carabiner. If so, tries to do that and connect to it.
     *
     * <p>A successful return indicates we are now connected to Carabiner. Sets up a background
     * thread to reject the connection if we have not received an initial status report from the
     * Carabiner daemon within a second of opening it.</p>
     *
     * @throws IOException if there is a problem opening the connection.
     */
    @API(status = MAINTAINED)
    public synchronized void connect() throws IOException {
        if (isActive()) {
            return;  // We were already connected.
        }
        try {
            try {
                connectInternal(false);
            } catch (ConnectException e) {
                // If we couldn't connect, see if we can run Carabiner ourselves and try again.
                if (Runner.getInstance().canRunCarabiner()) {
                    Runner.getInstance().setPort(carabinerPort.get());
                    Runner.getInstance().start();
                    connectInternal(true);
                } else {
                    throw e;
                }
            }
        } catch (ConnectException e) {
            // Provide a more tailored message to help people figure out what they need to do.
            throw new ConnectException("Unable to connect to Carabiner; make sure it is running on the specified port. Cause: " + e.getMessage());
        }

        // We succeeded in connecting, set up delayed check that it seems to be a Carabiner daemon, and a good version.
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
                logger.warn("Interrupted while sleeping before checking for Carabiner status response.");
            }
            if (linkBpm.get() != null) {  // Received a Carabiner status! Check version and configure for start/stop sync.
                try {
                    sendMessage("version");  // Probe whether a recent-enough version is running.
                } catch (IOException e) {
                    logger.error("Problem probing Carabiner version.", e);
                }
                try {
                    sendMessage("enable-start-stop-sync");  // Set up support for start/stop triggers.
                } catch (IOException e) {
                    logger.error("Problem enabling start/stop sync.", e);
                }
            } else {  // We did not receive a status response.
                logger.error("Did not receive expected response from Carabiner, is something else running on the specified port? Disconnecting.");
                disconnect();
            }
        }).start();
    }

    /**
     * Checks whether a tempo is a reasonable number of beats per minute. Ableton Link supports the range 20 to 999 BPM.
     * If you want something outside that range, pick the closest multiple or fraction; for example for 15 BPM, propose 30 BPM.
     *
     * @param bpm a tempo in beats per minute.
     *
     * @return whether that tempo can be used with Ableton Link.
     */
    @API(status = MAINTAINED)
    public boolean isTempoValid(double bpm) {
        return (bpm >= 20.0) && (bpm <= 999.0);
    }

    /**
     * Makes sure a tempo request is compatible with Ableton Link.
     *
     * @param bpm the desired tempo in beats per minute.
     *
     * @throws IllegalArgumentException if bpm is outside the range 20 to 999 BPM
     */
    private void validateTempo(double bpm) {
        if (!isTempoValid(bpm)) {
            throw new IllegalArgumentException("Tempo must be between 20 and 999 BPM.");
        }
    }

    /**
     * Starts holding the tempo of the Ableton Link session to the specified number of beats per minute.
     *
     * @param bpm the desired tempo in beats per minute.
     *
     * @throws IllegalArgumentException if bpm is outside the range 20 to 999 BPM
     * @throws IllegalStateException if the current sync mode is {@link SyncMode#OFF}
     * @throws IOException if there is a problem communicating with Carabiner
     */
    @API(status = MAINTAINED)
    public void lockTempo(double bpm) throws IOException {
        if (syncMode.get() == SyncMode.OFF) {
            throw new IllegalStateException("Must be synchronizing to lock tempo.");
        }
        validateTempo(bpm);
        targetBpm.set(bpm);
        deliverStateUpdate(getState());
        checkLinkTempo();
    }

    /**
     * Allow the tempo of the Ableton Link session to be controlled by other participants.
     */
    @API(status = MAINTAINED)
    public void unlockTempo() {
        targetBpm.set(null);
        deliverStateUpdate(getState());
    }

    /**
     * Find out what beat falls at the specified time in the link timeline, assuming 4 beats
     * per bar since we are also dealing with Pro DJ Link, and taking into account the configured
     * latency (see {@link #setLatency(int)}).
     *
     * <p>When the response comes, if we are configured to be the tempo master, we will nudge the
     * link timeline the smallest distance possible so that it had a beat at the same time.</p>
     *
     * <p>To align musical bars as well, use {@link #probeAbletonBeatAtTime(long, int)}.</p>
     *
     * @param beatMilliseconds the time (according to the system monotonic clock) at which a
     *                         beat has been received from Pro DJ Link
     *
     * @throws IOException if there is a problem talking to the Carabiner daemon
     */
    @API(status = EXPERIMENTAL)
    public void probeAbletonBeatAtTime(long beatMilliseconds) throws IOException {
        final long adjustedTime = beatMilliseconds - TimeUnit.MILLISECONDS.toMicros(latency.get());
        abletonBeatTimeProbe.set(adjustedTime);
        abletonBeatNumberProbe.set(null);
        sendMessage("beat-at-time " + adjustedTime + " 4.0");
    }

    /**
     * Find out what beat falls at the specified time in the link timeline, assuming 4 beats
     * per bar since we are also dealing with Pro DJ Link, and taking into account the configured
     * latency (see {@link #setLatency(int)}).
     *
     * <p>When the response comes, if we are configured to be the tempo master, we will nudge the
     * link timeline the smallest distance possible so that it had a beat at the same time.</p>
     *
     * <p>To align beats only, use {@link #probeAbletonBeatAtTime(long)}.</p>
     *
     * @param beatMilliseconds the time (according to the system monotonic clock) at which a
     *                         beat has been received from Pro DJ Link
     * @param beatWithinBar the beat number (ranging from 1 to 4) that was assigned to the beat
     *
     * @throws IOException if there is a problem talking to the Carabiner daemon
     */
    @API(status = EXPERIMENTAL)
    public void probeAbletonBeatAtTime(long beatMilliseconds, int beatWithinBar) throws IOException {
        final long adjustedTime = beatMilliseconds - TimeUnit.MILLISECONDS.toMicros(latency.get());
        abletonBeatTimeProbe.set(adjustedTime);
        abletonBeatNumberProbe.set(beatWithinBar);
        sendMessage("beat-at-time " + adjustedTime + " 4.0");
    }

    /**
     * Tell Carabiner to start the Link session playing immediately, for any participants using Start/Stop Sync.
     * To start it at a particular point on the timeline, use {@link #startAbletonTransport(long)}.
     *
     * @throws IOException if there is a problem talking to the Carabiner daemon
     */
    @API(status = MAINTAINED)
    public void startAbletonTransport() throws IOException {
        startAbletonTransport(TimeUnit.NANOSECONDS.toMicros(System.nanoTime()));
    }

    /**
     * Tell Carabiner to start the Link session playing at the specified future moment, for any participants
     * using Start/Stop Sync. To start it immediately, use {@link #startAbletonTransport()}.
     *
     * @param microsecondTime the time relative to the system monotonic clock at which you would like playback to begin
     *
     * @throws IOException if there is a problem talking to the Carabiner daemon
     */
    @API(status = MAINTAINED)
    public void startAbletonTransport(long microsecondTime) throws IOException {
        sendMessage("start-playing " + microsecondTime);
    }

    /**
     * Tell Carabiner to stop the Link session playing immediately, for any participants using Start/Stop Sync.
     * To start it at a particular point on the timeline, use {@link #stopAbletonTransport(long)}.
     *
     * @throws IOException if there is a problem talking to the Carabiner daemon
     */
    @API(status = MAINTAINED)
    public void stopAbletonTransport() throws IOException {
        stopAbletonTransport(TimeUnit.NANOSECONDS.toMicros(System.nanoTime()));
    }

    /**
     * Tell Carabiner to stop the Link session playing at the specified future moment, for any participants
     * using Start/Stop Sync. To stop it immediately, use {@link #startAbletonTransport()}.
     *
     * @param microsecondTime the time relative to the system monotonic clock at which you would like playback to end
     *
     * @throws IOException if there is a problem talking to the Carabiner daemon
     */
    @API(status = MAINTAINED)
    public void stopAbletonTransport(long microsecondTime) throws IOException {
        sendMessage("stop-playing " + microsecondTime);
    }

    /**
     * Send a probe that will allow us to align the Virtual CDJ (and thus the Pioneer Pro DJ Link timeline)
     * to Ableton Link’s.
     *
     * @throws IOException if there is a problem talking to the Carabiner daemon
     */
    private void alignPioneerPhaseToAbleton() throws IOException {
        long abletonNow = TimeUnit.NANOSECONDS.toMicros(System.nanoTime()) + TimeUnit.MICROSECONDS.toMicros(latency.get());
        phaseProbeTime.set(abletonNow);
        phaseProbeSnapshot.set(VirtualCdj.getInstance().getPlaybackPosition());
        sendMessage("phase-at-time " + abletonNow + " 4.0");
    }

    /**
     * Responds to tempo changes and beat packets from the tempo master CDJ when we are configured to
     * control the Ableton Link timeline (in {@link SyncMode#PASSIVE} or {@link SyncMode#FULL} mode).
     */
    private final MasterListener masterListener = new MasterAdapter() {
        /**
         * <p>Invoked when a beat is reported by the tempo master, as long as the {@link org.deepsymmetry.beatlink.BeatFinder} is active.
         * Even though beats contain far less detailed information than status updates, they can be passed to
         * {@link VirtualCdj#getLatestStatusFor(org.deepsymmetry.beatlink.DeviceUpdate)} to find the current detailed status for that device,
         * as long as the Virtual CDJ is active.</p>
         *
         * <p>To reduce latency, tempo master updates are delivered to listeners directly on the thread that is receiving them
         * from the network, so if you want to interact with user interface objects in this method, you need to use
         * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
         * to do so on the Event Dispatch Thread.</p>
         *
         * <p>Even if you are not interacting with user interface objects, any code in this method
         * <em>must</em> finish quickly, or it will add latency for other listeners, and master updates will back up.
         * If you want to perform lengthy processing of any sort, do so on another thread.</p>
         *
         * @param beat the message which announced the start of the new beat
         */
        @Override
        public void newBeat(Beat beat) {
            try {
                if (VirtualCdj.getInstance().isRunning() && beat.isTempoMaster()) {
                    if (syncToBars.get()) {
                        probeAbletonBeatAtTime(TimeUnit.NANOSECONDS.toMicros(beat.getTimestamp()), beat.getBeatWithinBar());
                    } else {
                        probeAbletonBeatAtTime(TimeUnit.NANOSECONDS.toMicros(beat.getTimestamp()));
                    }
                }
            } catch (Exception e) {
                logger.error("Problem responding to beat packet in beat-carabiner-java");
            }
        }

        @Override
        public void tempoChanged(double tempo) {
            if (isTempoValid(tempo)) {
                try {
                    lockTempo(tempo);
                } catch (IOException e) {
                    logger.error("Problem communicating with Carabiner when responding to Pro DJ Link tempo change:", e);
                }
            } else {
                unlockTempo();
            }
        }
    };

    /**
     * Start forcing the Ableton Link timeline to follow the tempo and beats (and maybe bars) of the Pioneer
     * master player.
     */
    private void tieAbletonToPioneer() {
        VirtualCdj.getInstance().addMasterListener(masterListener);
        masterListener.tempoChanged(VirtualCdj.getInstance().getMasterTempo());
    }

    /**
     * Stop forcing the Ableton Link timeline to follow the Pioneer master player.
     */
    private void freeAbletonFromPioneer() {
        VirtualCdj.getInstance().removeMasterListener(masterListener);
        unlockTempo();
    }

    /**
     * Start forcing the Pioneer DJ Link tempo and beat grid to follow the Ableton Link timeline.
     *
     * @throws IOException if there is a problem communicating with the Carabiner daemon
     */
    private void tiePioneerToAbleton() throws IOException {
        freeAbletonFromPioneer();  // When we are tempo master, we don’t follow anyone else.
        alignPioneerPhaseToAbleton();
        VirtualCdj.getInstance().setTempo(linkBpm.get());
        VirtualCdj.getInstance().setPlaying(true);
        VirtualCdj.getInstance().becomeTempoMaster();
        new Thread(() -> {
            // Realign the Pro DJ Link tempo in a millisecond or so, in case it gets changed by the outgoing
            // master during handoff.
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while sleeping for 1ms, what?", e);
            }
            try {
                sendMessage("status");
            } catch (IOException e) {
                logger.error("Problem communicating with Carabiner while tying Pro DJ Link tempo to Ableton Link:", e);
            }
        }).start();
    }

    /**
     * Stop forcing the Pioneer DJ Link tempo and beat grid to follow the Ableton Link timeline.
     */
    private void freePioneerFromAbleton() {
        VirtualCdj.getInstance().setPlaying(false);
        // If we are also supposed to be synced in the other direction, it is time to turn that back on.
        if ((syncMode.get() == SyncMode.PASSIVE || syncMode.get() == SyncMode.FULL) &&
                VirtualCdj.getInstance().isSynced()) {
            tieAbletonToPioneer();
        }
    }

    /**
     * Controls whether the Ableton Link session timeline is tied to the tempo and beat grid of the
     * Pro DJ Link devices. Also reflects that in the sync state of the {@link VirtualCdj} so it can
     * be seen on the DJ Link network. Finally, if our Sync mode is {@link SyncMode#PASSIVE} or
     * {@link SyncMode#FULL}, unless we are the tempo master, start tying the Ableton Link session timeline
     * to the Pioneer DJ Link tempo master.
     *
     * @param enabled specifies whether Ableton Link be tied to Pioneer DJ Link
     */
    @API(status = MAINTAINED)
    public void syncAbletonLink(boolean enabled) {
       if (VirtualCdj.getInstance().isSynced() != enabled) {
           VirtualCdj.getInstance().setSynced(enabled);
       }
       if (enabled) {
           tieAbletonToPioneer();
       } else {
           freeAbletonFromPioneer();
       }
    }

    /**
     * Controls whether the Ableton link session timeline should be the tempo master for the Pro DJ Link devices.
     * Has no effect if we are not in a compatible sync mode (see {@link #setSyncMode(SyncMode)}).
     *
     * @param enabled specifies whether the Ableton Link session should be tempo master if possible
     *
     * @throws IOException if there is a problem communicating with the Carabiner daemon
     */
    @API(status = MAINTAINED)
    public void abletonLinkIsMaster(boolean enabled) throws IOException {
        if (enabled) {
            if (syncMode.get() == SyncMode.FULL) {
                tiePioneerToAbleton();
            }
        } else {
            freePioneerFromAbleton();
        }
    }

    /**
     * Validates that the desired mode is compatible with the current state, and if so, updates our state to put us
     * in that mode and performs any necessary synchronization. Choices are:
     *
     * <p>{@link SyncMode#OFF}: No synchronization is attempted.</p>
     *
     * <p>{@link SyncMode#MANUAL}: External code will be calling {@link #lockTempo(double)} and {@link #unlockTempo()}
     * to manipulate the Ableton Link session timeline.</p>
     *
     * <p>{@link SyncMode#PASSIVE}: Ableton Link always follows the Pro DJ Link network, and we do not attempt to
     * control other Pro DJ Link players.</p>
     *
     * <p>{@link SyncMode#FULL}: Bidirectional synchronization occurs, determined by the Master and Sync state of
     * players on the Pro DJ Link network, including Beat Link’s {@link VirtualCdj}, which stands in for the
     * Ableton Link session.</p>
     *
     * @param mode the sync mode that is desired
     *
     * @throws IllegalStateException if our current state is incompatible with the desired sync mode
     * @throws IOException if there is a problem communicating with the Carabiner daemon
     */
    @API(status = MAINTAINED)
    public void setSyncMode(SyncMode mode) throws IOException {
        if (mode != SyncMode.OFF && !isActive()) {
            throw new IllegalStateException("Cannot synchronize without an active Carabiner connection.");
        }
        if (mode != SyncMode.OFF && !VirtualCdj.getInstance().isRunning()) {
            throw new IllegalStateException("Cannot synchronize when VirtualCdj isn’t running.");
        }
        if (mode == SyncMode.FULL && !VirtualCdj.getInstance().isSendingStatus()) {
            throw new IllegalStateException("Cannot use full sync mode when VirtualCDJ isn’t sending status packets.");
        }

        syncMode.set(mode);
        if (mode == SyncMode.PASSIVE || mode == SyncMode.FULL) {
            syncAbletonLink(VirtualCdj.getInstance().isSynced());
            if (mode == SyncMode.FULL && VirtualCdj.getInstance().isTempoMaster()) {
                tiePioneerToAbleton();
            }
        } else {
            freeAbletonFromPioneer();
            freePioneerFromAbleton();
        }
    }

    /**
     * Sets the Ableton Link session tempo to the specified number of beats per minute, unless it is already close
     * enough (within 0.005 beats per minute).
     *
     * @param bpm the desired tempo, which must be valid for Ableton Link (between 20 and 999 beats per minute)
     *
     * @throws IllegalStateException if we are not connected to a Carabiner daemon
     * @throws IllegalArgumentException if the tempo is out of range for Ableton Link
     * @throws IOException if there is a problem communicating with the Carabiner daemon
     */
    @API(status = MAINTAINED)
    public void setAbletonLinkTempo(double bpm) throws IOException {
        validateTempo(bpm);
        ensureActive();
        if (Math.abs(bpm - linkBpm.get()) > 0.005) {
            sendMessage("bpm " + bpm);
        }
    }

}
