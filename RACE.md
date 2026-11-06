# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA may ask about it.
Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Everything your review turned up that touches shared state, with a one-line verdict for each. Add or
remove rows as needed.

| #   | What you found (method, field, or class) | Verdict, in one line                                                        |
| --- | ---------------------------------------- | --------------------------------------------------------------------------- |
| 1   | `BookingService.BookingResult book()`    | The booking "confirmed" state should all be updating at the same time       |
| 2   | `InMemoryBookingStore.bookings`          | There is no in-built systematic way to prevent double-booking (list vs map) |
| 3   |                                          |                                                                             |
| 4   |                                          |                                                                             |

**Which one explains the ticket, and how you know.** 1 explains the ticket because the
check-and-insert operation should be atomic and the threads should not be paused by another thread
during the booking confirmation.

### Predicted interleaving (write this before you run your race test)

Two threads, one row per step, in the form from lecture. Describe what each thread does at that step
and leave the other column empty. Add or remove rows as your prediction needs.

| Step | Thread A               | Thread B               |
| ---- | ---------------------- | ---------------------- |
| t1   | bookingA.hasOverlap()  |                        |
| t2   |                        | bookingB.hasOverlap()  |
| t3   | store.insert(bookingA) |                        |
| t4   |                        | store.insert(bookingB) |
|      | **End state:**         |                        |

If the run turned out different from your prediction, add one line on what you missed. Leave the
table as you first wrote it.

### Your race test

**How you aimed `PausingStore`, and the result.** Method name and call index, why that pair pins the
schedule you predicted, and the output of two separate runs (assertion failure and log lines).

**Aim:** `PausingStore(real, "hasOverlap", 1)`, in `RaceTest.twoCustomersCannotHoldTheSameSlot`.

**Why that pair pins the schedule.** The gate delegates first and parks after, so thread A is held
holding `hasOverlap == false` (t1) before it reaches `insert`. Call 1 is A's because the test starts
A and waits on `awaitPaused()` before starting B. B's `hasOverlap` is call 2, which the one-shot
gate lets through. B then checks and inserts (t2, t4 in the prediction) while A is parked. Releasing
A makes it `insert` on its stale answer (t3). The latches fix the order, so it does not depend on
the scheduler.

**Run 1** (separate `mvn test -Dtest=RaceTest` invocation):

```
2026-11-06T15:12:02.324Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T15:12:02.327Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
RaceTest.twoCustomersCannotHoldTheSameSlot:132 a room may have only one CONFIRMED booking for a slot ==> expected: <1> but was: <2>
```

**Run 2** (separate JVM):

```
2026-11-06T15:12:03.873Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T15:12:03.876Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
RaceTest.twoCustomersCannotHoldTheSameSlot:132 a room may have only one CONFIRMED booking for a slot ==> expected: <1> but was: <2>
```

Same failure both times (also 20 of 20 in a separate batch). Two `booking.confirmed` lines for the
same room and range with nothing between them, matching the ticket's log excerpt.

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green.

```
2026-11-06T15:17:37.297Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T15:17:37.301Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

Green here only shows the patch beats this one schedule, where A is parked after check 1. The
re-check adds a second `hasOverlap` call that is still a separate step from `insert`, and this test
never parks between those two.

**The re-aimed run.**

Aim: `PausingStore(real, "hasOverlap", 3)`, in
`RaceTest.twoCustomersCannotHoldTheSameSlotAfterTheRecheck`. Call 1 is a throwaway rejected booking,
call 2 is A's first check, call 3 is A's re-check, so A is parked after the re-check and before
`insert`. This tests the patch because it parks in the exact gap the patch left, and it only fires
on code that has the second check (on the original code A makes only call 2 and the gate never
fires).

```
2026-11-06T15:21:49.490Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
2026-11-06T15:21:49.497Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
2026-11-06T15:21:49.497Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
RaceTest.twoCustomersCannotHoldTheSameSlotAfterTheRecheck:183 a room may have only one CONFIRMED booking for a slot ==> expected: <1> but was: <2>
```

Red: the patched code still issues two CONFIRMED bookings for the same room and slot.

**Your verdict.** Reject the patch. The milestone-1 test is green only because it parks A before the
re-check; parking A after it (`hasOverlap`, call 3) still gives two CONFIRMED bookings, 3 of 3 runs.
The check and `insert` are still separate steps, so the window only moved.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** The store's own lock now covers the overlap check and the
write: `InMemoryBookingStore.insert` is one `synchronized` step that rejects a CONFIRMED booking if
one already overlaps and returns `boolean`, and `book()` calls only `insert`. The diff below is
against the patched code (the teammate's re-check was already in the tree), so it also drops that
re-check.

```diff
 // BookingService.book()
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
-            return reject(roomId, startMinute, endMinute, "slot already held");
-        }
         Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        // Defensive re-check. ...
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        if (!store.insert(booking)) {
             return reject(roomId, startMinute, endMinute, "slot already held");
         }
-        store.insert(booking);
 // InMemoryBookingStore
-    public synchronized void insert(Booking booking) {
+    public synchronized boolean insert(Booking booking) {
+        if (booking.status() == BookingStatus.CONFIRMED
+                && hasOverlap(booking.roomId(), booking.startMinute(), booking.endMinute())) {
+            return false;
+        }
         bookings.add(booking);
+        return true;
     }
```

`BookingStore.insert` and `PausingStore.insert` change to return `boolean` to match. Passing runs
(test re-aimed at `insert`, call 1, since `book()` no longer calls `hasOverlap`):

```
INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0      (RaceTest)
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0     (full suite)
```

**Is your milestone-1 test still testing anything?** Barely. `PausingStore` did park A:
`awaitPaused()` passed, and the log shows B rejected while A was still parked, before A's
confirmation. But the gate fires after `insert` returns and the lock is released, so the test only
shows that B sees A's stored booking. It would also pass if the check and write were two separate
steps inside `insert`; the evidence the fix matters is that the same scenario failed on the unfixed
and patched code.

**What runs one at a time now that ran in parallel before.** Every `book()` queues behind every
other `book()`, `cancelAndPromote`, `bookingsForRoom` and `joinWaitlist` call on the store's single
monitor, across all rooms. Before, each store call was also locked, but the check and write were
separate critical steps, so two `book()` calls for one slot could interleave between them. Now each
`book()` holds the lock for the whole scan and add.

**What you left unfixed.**

- `hasOverlap` is still public, so a caller can still write check-then-insert; left because `insert`
  now enforces the invariant on its own.
- One lock for all rooms, not per room; left because the store is a small in-memory list.
- `BookingStats` counters can read low; documented as approximate and nothing depends on them.
- `BookingService`'s class javadoc still says store-level thread safety makes the service safe; left
  because it is not part of this ticket.
