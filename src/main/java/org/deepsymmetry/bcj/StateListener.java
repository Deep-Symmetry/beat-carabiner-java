package org.deepsymmetry.bcj;

import org.apiguardian.api.API;

import static org.apiguardian.api.API.Status.MAINTAINED;

/**
 * Implement this interface if you would like to receive state updates whenever Carabiner reports status changes.
 */
@API(status = MAINTAINED)
public interface StateListener {
    /**
     * Called whenever we receive a status update from Carabiner.
     * @param state the new state of our connection
     */
    void carabinerStatusReceived(State state);
}
