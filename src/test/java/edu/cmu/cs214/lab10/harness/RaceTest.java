package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.domain.BookingResult;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Where your concurrency tests go.
 *
 * <p>Empty on purpose. The wiring you need is written out below; what to aim it
 * at, and what scenario to drive through it, is yours to work out.
 */
class RaceTest {

    // ------------------------------------------------------------------
    // Wiring example 1: wrap the store
    //
    // PausingStore is a BookingStore that wraps another BookingStore. Build the
    // real store, wrap it, and hand the wrapper to the service. The service
    // cannot tell the difference.
    //
    //   InMemoryBookingStore real = new InMemoryBookingStore();
    //   PausingStore gated = new PausingStore(real, "bookingsForRoom", 1);
    //   BookingService service = new BookingService(gated, ROOMS);
    //
    // (ROOMS is the room list, as in BookingServiceTest.)
    //
    // Assert against `real`, not `gated`: you want the end state of the store,
    // and reading through the wrapper counts as a call.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 2: aim the gate
    //
    // The two constructor arguments after the delegate are a method name and a
    // call index counting from 1 across all threads:
    //
    //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
    //   new PausingStore(real, "insert", 3)            // park call 3 of insert
    //
    // Valid method names are the four on BookingStore: hasOverlap, insert,
    // bookingsForRoom, cancelAndPromote. On the chosen call the wrapper
    // delegates, then parks the calling thread before returning the answer it
    // already computed. The gate fires once and once only.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 3: the two-thread pattern
    //
    // Every wait below is bounded, so this pattern cannot hang no matter what
    // the code under test does. Do not add a sleep to it; a sleep is a guess,
    // and the latches are not. (Your test method needs
    // `throws InterruptedException` for the joins.)
    //
    // The scenario here is deliberately harmless: it parks a reader in
    // bookingsForRoom (the aim from example 1) while a writer books a
    // different room. It exists to show the choreography, not a bug.
    //
    //   Thread reader = new Thread(() -> service.listBookings("WEH-5409"));
    //   reader.start();
    //
    //   gated.awaitPaused();          // returns when a thread is parked, or
    //                                 // false after PausingStore.TIMEOUT_MILLIS
    //                                 // if the gated call never happened
    //
    //   Thread writer = new Thread(() -> service.book("GHC-4301", 720, 780));
    //   writer.start();
    //   writer.join(PausingStore.TIMEOUT_MILLIS);
    //
    //   gated.release();              // the parked reader returns and finishes
    //   reader.join(PausingStore.TIMEOUT_MILLIS);
    //                                 // note the writer needed no release: it
    //                                 // was never blocked by the parked reader
    //
    // Then assert on the end state, never on the order things happened in. For
    // the harmless scenario above, the writer's booking is stored whatever the
    // reader saw:
    //
    //   assertEquals(1, real.bookingsForRoom("GHC-4301").size());
    //
    // An end-state assertion still means something after you change the code
    // underneath it. A schedule assertion does not.
    // ------------------------------------------------------------------

    private static final String WEAN = "WEH-5409";
    private static final List<Room> ROOMS = List.of(new Room(WEAN, "Wean 5409", 12));

    /**
     * RES-4471: two customers both get CONFIRMED for the same room and slot.
     *
     * <p>{@code BookingService.book} calls {@code hasOverlap} and then
     * {@code insert}; each is atomic on its own but nothing makes the pair
     * atomic. The gate parks customer A right after the FIRST {@code hasOverlap}
     * returns false (the "free" answer is already in A's hands). Customer B then
     * runs the whole book() call, sees the slot free, and inserts. When A is
     * released it acts on its stale answer and inserts too.
     *
     * <p>Asserts on the end state of the real store, not on the schedule.
     */
    @Test
    void twoCustomersCannotHoldTheSameSlot() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "hasOverlap", 1);
        BookingService service = new BookingService(gated, ROOMS);

        AtomicReference<BookingResult> resultA = new AtomicReference<>();
        AtomicReference<BookingResult> resultB = new AtomicReference<>();

        Thread a = new Thread(() -> resultA.set(service.book(WEAN, 900, 960)));
        a.start();

        assertTrue(gated.awaitPaused(), "customer A never reached hasOverlap call 1");

        Thread b = new Thread(() -> resultB.set(service.book(WEAN, 900, 960)));
        b.start();
        b.join(PausingStore.TIMEOUT_MILLIS);

        gated.release();
        a.join(PausingStore.TIMEOUT_MILLIS);

        long confirmed = real.bookingsForRoom(WEAN).stream()
                .filter(bk -> bk.status() == BookingStatus.CONFIRMED)
                .count();
        assertEquals(1, confirmed, "a room may have only one CONFIRMED booking for a slot");
        assertEquals(
                1,
                List.of(resultA.get(), resultB.get()).stream().filter(BookingResult::isConfirmed).count(),
                "exactly one customer should be told CONFIRMED");
    }

    /**
     * Re-aimed at the patched {@code book()}, which calls {@code hasOverlap} a
     * second time right before {@code insert}.
     *
     * <p>Call 1 is used up by a throwaway customer who is rejected from a slot
     * that is already held. Then customer A makes call 2 (the check) and call 3
     * (the re-check). The gate parks A after call 3 returns false, which is the
     * gap between the re-check and {@code insert}. Customer B runs the whole
     * {@code book()} (calls 4 and 5), inserts, and is CONFIRMED. Releasing A
     * makes it insert on its stale re-check.
     *
     * <p>Against the original, unpatched {@code book()} A makes only call 2, so
     * the gate never fires and the first assertion fails: this aim only means
     * something against code that has the second check.
     */
    @Test
    void twoCustomersCannotHoldTheSameSlotAfterTheRecheck() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        real.insert(new Booking("seed-1", WEAN, 540, 600, BookingStatus.CONFIRMED));
        PausingStore gated = new PausingStore(real, "hasOverlap", 3);
        BookingService service = new BookingService(gated, ROOMS);

        // hasOverlap call 1: rejected, so it makes exactly one store call.
        assertTrue(service.book(WEAN, 540, 600).isRejected());

        AtomicReference<BookingResult> resultA = new AtomicReference<>();
        AtomicReference<BookingResult> resultB = new AtomicReference<>();

        Thread a = new Thread(() -> resultA.set(service.book(WEAN, 900, 960)));
        a.start();

        assertTrue(gated.awaitPaused(), "customer A never reached hasOverlap call 3");

        Thread b = new Thread(() -> resultB.set(service.book(WEAN, 900, 960)));
        b.start();
        b.join(PausingStore.TIMEOUT_MILLIS);

        gated.release();
        a.join(PausingStore.TIMEOUT_MILLIS);

        long confirmed = real.bookingsForRoom(WEAN).stream()
                .filter(bk -> bk.status() == BookingStatus.CONFIRMED && bk.startMinute() == 900)
                .count();
        assertEquals(1, confirmed, "a room may have only one CONFIRMED booking for a slot");
        assertEquals(
                1,
                List.of(resultA.get(), resultB.get()).stream().filter(BookingResult::isConfirmed).count(),
                "exactly one customer should be told CONFIRMED");
    }
}
