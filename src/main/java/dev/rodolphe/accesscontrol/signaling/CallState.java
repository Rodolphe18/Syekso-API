package dev.rodolphe.accesscontrol.signaling;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One call in flight: who is being rung, from which building, and the task that will give up on it.
 *
 * <p>Not a record, because the status changes — a call moves from ringing to in-call, or expires.
 *
 * <p>The status is an {@link AtomicReference} rather than a plain field, and that fixes a real race
 * the Kotlin version has. There, {@code onAcceptCall} sets the status then cancels the timeout, while
 * the timeout callback reads the status and drops the call. Interleave them at the thirtieth second
 * and a call that was just answered gets torn down as unanswered. A compare-and-set makes the
 * transition the arbiter: whoever wins it acts, the loser does nothing.
 */
final class CallState {

    private final String buildingId;
    private final String residentUserId;
    private final AtomicReference<CallStatus> status = new AtomicReference<>(CallStatus.RINGING);
    private volatile ScheduledFuture<?> timeout;

    CallState(String buildingId, String residentUserId) {
        this.buildingId = buildingId;
        this.residentUserId = residentUserId;
    }

    String buildingId() {
        return buildingId;
    }

    String residentUserId() {
        return residentUserId;
    }

    CallStatus status() {
        return status.get();
    }

    /** @return true if this call was still ringing and is now answered — false if someone got there first. */
    boolean markInCallIfRinging() {
        return status.compareAndSet(CallStatus.RINGING, CallStatus.IN_CALL);
    }

    /** @return true if this call was still ringing and has now timed out — false if it was answered. */
    boolean markExpiredIfRinging() {
        return status.compareAndSet(CallStatus.RINGING, CallStatus.EXPIRED);
    }

    void setTimeout(ScheduledFuture<?> timeout) {
        this.timeout = timeout;
    }

    void cancelTimeout() {
        ScheduledFuture<?> pending = timeout;
        if (pending != null) {
            pending.cancel(false);
        }
    }
}
