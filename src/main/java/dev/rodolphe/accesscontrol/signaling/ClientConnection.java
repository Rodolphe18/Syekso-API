package dev.rodolphe.accesscontrol.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One connected client — a resident or an intercom — seen by the hub as "something I can send a
 * message to".
 *
 * <p>Deliberately not a WebSocket session. The hub receives a {@link Sink} lambda instead, which is
 * what lets the whole call state machine be unit-tested with a list as the destination, without a
 * server, a socket or a serialiser in sight.
 *
 * <p>The lock serialises concurrent sends to the same socket. Two threads writing a WebSocket session
 * at once interleave their frames and corrupt both — the counterpart of the Kotlin version's Mutex.
 */
public final class ClientConnection {

    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    /** Where a message actually goes. Implemented by the WebSocket handler, faked by tests. */
    @FunctionalInterface
    public interface Sink {
        void send(SignalingMessage message) throws IOException;
    }

    private final String id;
    private final Sink sink;
    private final ReentrantLock lock = new ReentrantLock();

    public ClientConnection(String id, Sink sink) {
        this.id = id;
        this.sink = sink;
    }

    public String id() {
        return id;
    }

    /**
     * A failed send is logged and swallowed rather than thrown.
     *
     * <p>A peer that has gone away is a normal condition in a relay, not an error that should unwind
     * the caller's state machine — the Kotlin version propagated it, which meant a dead resident could
     * abort a transition that had already half happened. The transport notices the disconnection on
     * its own and calls {@code unregister}, which is where cleanup belongs.
     */
    public void send(SignalingMessage message) {
        lock.lock();
        try {
            sink.send(message);
        } catch (IOException e) {
            log.warn("Send failed to {}, connection is likely gone", id, e);
        } finally {
            lock.unlock();
        }
    }
}
