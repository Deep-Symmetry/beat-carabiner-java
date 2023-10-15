package org.deepsymmetry.bcj;

import org.apiguardian.api.API;

import static org.apiguardian.api.API.Status.MAINTAINED;

/**
 * Implement this interface if you’d like to be notified when the Carabiner connection is closed, either
 * intentionally or unexpectedly.
 */
@API(status = MAINTAINED)
public interface DisconnectionListener {

    /**
     * Called when the Carabiner connection is closed.
     *
     * @param unexpected if the closure was not requested
     */
    @API(status = MAINTAINED)
    void connectionClosed(boolean unexpected);
}
