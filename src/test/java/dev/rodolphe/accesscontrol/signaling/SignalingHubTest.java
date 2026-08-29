package dev.rodolphe.accesscontrol.signaling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The call state machine, tested without a server, a socket or a serialiser: a {@link ClientConnection}
 * is only "something I can send to", so a list stands in for a peer.
 *
 * <p>The seven first cases are the Kotlin hub's own tests, ported. The last two are new and cover the
 * ring timeout, which the Kotlin suite never exercised — it used virtual time but never advanced it,
 * so the expiry path and its race with an incoming accept were both untested.
 */
class SignalingHubTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final SignalingHub hub = new SignalingHub(scheduler, 30_000);

    private final List<SignalingMessage> residentSink = new ArrayList<>();
    private final List<SignalingMessage> intercomSink = new ArrayList<>();

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private void connectBoth() {
        hub.registerResident("u1", new ClientConnection("u1", residentSink::add));
        hub.registerIntercom("b1", new ClientConnection("b1", intercomSink::add));
    }

    private void ringAndAccept() {
        hub.onRingCall("b1", new SignalingMessage.Ring("c1", "u1", "Porte"));
        hub.onAcceptCall("u1", new SignalingMessage.Accept("c1"));
        residentSink.clear();
        intercomSink.clear();
    }

    @Test
    @DisplayName("a ring to a connected resident is routed to them")
    void ringIsRouted() {
        connectBoth();

        hub.onRingCall("b1", new SignalingMessage.Ring("c1", "u1", "Porte d'entrée"));

        assertEquals(List.of(new SignalingMessage.Ring("c1", "u1", "Porte d'entrée")), residentSink);
    }

    @Test
    @DisplayName("a ring to an absent resident errors back to the intercom")
    void ringToAbsentResident() {
        hub.registerIntercom("b1", new ClientConnection("b1", intercomSink::add));

        hub.onRingCall("b1", new SignalingMessage.Ring("c1", "u1", "Porte"));

        assertEquals(List.of(new SignalingMessage.ErrorMsg("c1", "Résident indisponible")), intercomSink);
    }

    @Test
    @DisplayName("accept moves the call to in-call and relays to the intercom")
    void acceptRelays() {
        connectBoth();
        hub.onRingCall("b1", new SignalingMessage.Ring("c1", "u1", "Porte"));
        intercomSink.clear();

        hub.onAcceptCall("u1", new SignalingMessage.Accept("c1"));

        assertEquals(List.of(new SignalingMessage.Accept("c1")), intercomSink);
    }

    @Test
    @DisplayName("opening the door mid-call relays, reports back, and leaves the call up")
    void openKeepsTheCallAlive() {
        connectBoth();
        ringAndAccept();

        hub.onOpenCall("u1", new SignalingMessage.Open("c1"));
        assertEquals(List.of(new SignalingMessage.Open("c1")), intercomSink);

        hub.onOpenResultReported("b1", new SignalingMessage.OpenResult("c1", true, null));
        assertEquals(List.of(new SignalingMessage.OpenResult("c1", true, null)), residentSink);

        // The point of the whole feature: the call survives the door opening, so it can be opened again.
        intercomSink.clear();
        hub.onOpenCall("u1", new SignalingMessage.Open("c1"));
        assertEquals(List.of(new SignalingMessage.Open("c1")), intercomSink);
    }

    @Test
    @DisplayName("media negotiation is relayed in both directions without being read")
    void mediaIsRelayed() {
        connectBoth();
        ringAndAccept();

        hub.relayFromIntercom("b1", new SignalingMessage.Offer("c1", "OFFER"));
        assertEquals(List.of(new SignalingMessage.Offer("c1", "OFFER")), residentSink);

        hub.relayFromResident("u1", new SignalingMessage.Answer("c1", "ANSWER"));
        assertEquals(List.of(new SignalingMessage.Answer("c1", "ANSWER")), intercomSink);
    }

    @Test
    @DisplayName("a hangup from the resident ends the call and reaches the intercom")
    void hangupEndsTheCall() {
        connectBoth();
        ringAndAccept();

        hub.onHangupCall("c1", true);
        assertEquals(List.of(new SignalingMessage.Hangup("c1")), intercomSink);

        // The call is really gone, not merely marked: acting on it afterwards is refused.
        residentSink.clear();
        hub.onOpenCall("u1", new SignalingMessage.Open("c1"));
        assertEquals(List.of(new SignalingMessage.ErrorMsg("c1", "Appel expiré")), residentSink);
    }

    @Test
    @DisplayName("decline forwards to the intercom and drops the call")
    void declineDropsTheCall() {
        connectBoth();
        hub.onRingCall("b1", new SignalingMessage.Ring("c1", "u1", "Porte"));
        intercomSink.clear();

        hub.onDeclineCall("u1", new SignalingMessage.Decline("c1"));

        assertTrue(intercomSink.contains(new SignalingMessage.Decline("c1")));
    }

    @Test
    @DisplayName("an unanswered ring times out and both sides are told")
    void ringTimesOut() {
        connectBoth();
        hub.onRingCall("b1", new SignalingMessage.Ring("c1", "u1", "Porte"));
        residentSink.clear();
        intercomSink.clear();

        scheduler.fireScheduledTask();

        assertEquals(List.of(new SignalingMessage.ErrorMsg("c1", "Pas de réponse")), intercomSink);
        assertEquals(List.of(new SignalingMessage.ErrorMsg("c1", "TIMED_OUT")), residentSink);
    }

    @Test
    @DisplayName("a call accepted just before the timeout fires is not torn down by it")
    void timeoutLosesTheRaceAgainstAccept() {
        connectBoth();
        hub.onRingCall("b1", new SignalingMessage.Ring("c1", "u1", "Porte"));
        hub.onAcceptCall("u1", new SignalingMessage.Accept("c1"));
        residentSink.clear();
        intercomSink.clear();

        // The timer had already fired when accept arrived — the classic interleaving. The
        // compare-and-set in CallState is what makes exactly one of the two win.
        scheduler.fireScheduledTask();

        assertEquals(List.of(), intercomSink, "the intercom must not be told nobody answered");
        assertEquals(List.of(), residentSink, "the resident must not be timed out mid-call");

        // And the call is still usable.
        hub.onOpenCall("u1", new SignalingMessage.Open("c1"));
        assertEquals(List.of(new SignalingMessage.Open("c1")), intercomSink);
    }

    /**
     * A scheduler whose one scheduled task the test fires by hand.
     *
     * <p>It still delegates to a real executor so the returned {@code ScheduledFuture} behaves — the
     * hub cancels it on accept — but with a delay long enough that it never goes off on its own.
     * Timing is therefore decided by the test, not by a sleep.
     */
    private static final class ManualScheduler implements TaskScheduler {

        private final ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "manual-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        private volatile Runnable pending;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            this.pending = task;
            return delegate.schedule(task, 1, TimeUnit.HOURS);
        }

        void fireScheduledTask() {
            Runnable task = pending;
            if (task == null) {
                throw new IllegalStateException("nothing was scheduled");
            }
            task.run();
        }

        void shutdown() {
            delegate.shutdownNow();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant start, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException();
        }
    }
}
