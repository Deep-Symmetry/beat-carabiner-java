package org.deepsymmetry.bcj;

/**
 * Implement this interface if you’d like to be notified when the Carabiner connection is closed, either
 * intentionally or unexpectedly.
 */
public interface DisconnectionListener {

    /**
     * Called when the Carabiner connection is closed.
     *
     * @param unexpected if the closure was not requested
     */
    void connectionClosed(boolean unexpected);
}
