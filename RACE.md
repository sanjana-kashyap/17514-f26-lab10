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

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

**The re-aimed run.** Method name, call index, the pasted result, and one line on why that aim tests
the patch rather than sidestepping it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and does not show about
the patch.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation and the two operations
it now covers. Paste the diff of your change and the passing run of your milestone-1 test and the
full suite.

**Is your milestone-1 test still testing anything?** After your fix, describe what that test now
exercises. Did `PausingStore` pause a call during the green run? How do you know?

**What runs one at a time now that ran in parallel before.** Name concretely which callers queue
behind which. "Nothing, it is just correct now" does not count as an answer.

**What you left unfixed.** For anything your review flagged that you chose not to fix, one line each
on why.
