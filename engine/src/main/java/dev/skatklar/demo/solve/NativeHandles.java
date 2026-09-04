package dev.skatklar.demo.solve;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frees the native tables of reusable solvers nobody closed.
 *
 * <p>A {@link DoubleDummySolver} built by {@code reusableFor} may hold a
 * megabyte of native transposition table behind a Java object of a few dozen
 * bytes. Nothing about that object tells a garbage collector it is expensive,
 * so a caller that drops thirty-two of them — which is exactly what
 * {@code AlphaMu} does per decision — can leave tens of megabytes outstanding
 * for as long as the heap happens not to be under pressure.
 *
 * <p>{@code close()} is the intended path and the one the callers take. This is
 * the net underneath it: a phantom reference per handle, drained whenever
 * another is registered or released, so a forgotten solver is freed the next
 * time one is created rather than never.
 *
 * <p>Deliberately not {@code java.lang.ref.Cleaner}, which is the same thing
 * with a thread attached and needs API 33; this app supports 26. And
 * deliberately not a finalizer, which would put the free on a thread with no
 * ordering guarantees at all.
 */
final class NativeHandles {

    private static final ReferenceQueue<DoubleDummySolver> QUEUE = new ReferenceQueue<>();
    private static final Set<Handle> LIVE = ConcurrentHashMap.newKeySet();

    private NativeHandles() {}

    private static final class Handle extends PhantomReference<DoubleDummySolver> {
        final long pointer;

        Handle(DoubleDummySolver owner, long pointer) {
            super(owner, QUEUE);
            this.pointer = pointer;
        }
    }

    /** Takes ownership of a native solver on behalf of a Java one. */
    static long register(DoubleDummySolver owner, long pointer) {
        drain();
        LIVE.add(new Handle(owner, pointer));
        return pointer;
    }

    /** Frees a handle now. Safe to call twice; the second call does nothing. */
    static void release(long pointer) {
        Handle found = null;
        for (Handle handle : LIVE) {
            if (handle.pointer == pointer) {
                found = handle;
                break;
            }
        }
        // The set's remove is what decides the winner. Two threads closing the
        // same solver, or a close racing the queue drain, must not both free.
        if (found != null && LIVE.remove(found)) {
            found.clear();
            NativeSolver.destroySolver(pointer);
        }
        drain();
    }

    private static void drain() {
        for (Reference<?> reference; (reference = QUEUE.poll()) != null; ) {
            Handle handle = (Handle) reference;
            if (LIVE.remove(handle)) NativeSolver.destroySolver(handle.pointer);
        }
    }

    /** How many native solvers are outstanding. For the tests, which check it falls. */
    static int liveHandles() {
        drain();
        return LIVE.size();
    }
}
