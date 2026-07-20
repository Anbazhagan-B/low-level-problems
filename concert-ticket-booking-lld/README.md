# Concert Ticket Booking — Reference Implementation

Runnable Java scaffold for the design in [concert-ticket-booking-lld.md](concert-ticket-booking-lld.md).
No build tool or external dependencies — plain `javac` on JDK 11+.

## Run

```bash
# from this folder
javac -d out $(find src -name '*.java')
java -cp out concertbooking.demo.Demo
```

Or use the helper: `./run.sh` (bash) / `run.ps1` (PowerShell).

## Layout (`src/concertbooking/`)

Files are grouped into subpackages by responsibility:

| Package | Files | Role | Pattern |
| --- | --- | --- | --- |
| `enums` | `SeatType`, `SeatStatus`, `BookingStatus` | value sets / lifecycles | — |
| `exception` | `SeatNotAvailableException`, `PaymentFailedException` | domain failures | — |
| `model` | `Seat` | bookable unit + guarded state machine | State (lightweight) |
| `model` | `Concert` | catalog entry, owns its seats | Composition |
| `model` | `User`, `Booking` | identity, purchase record | — |
| `payment` | `PaymentProcessor` + `CreditCard` / `Upi` | pluggable payment | Strategy |
| `notification` | `NotificationService` + `Email` / `Sms` | pluggable channel | Strategy |
| `system` | `ConcertTicketBookingSystem` | singleton facade + booking workflow | Singleton + Facade |
| `demo` | `Demo` | runnable demo | — |

## What the demo shows

1. **Contention** — two threads race for the same seat; exactly one wins, the
   other fails cleanly (`SeatNotAvailableException`).
2. **Multi-seat booking + cancel** — an all-or-nothing booking, then a
   cancellation that returns the seats to `AVAILABLE`.

## Design notes

- **Concurrency** — `Seat.book()`/`release()` are `synchronized` on the seat
  instance, fusing check-and-set into one atomic step (no double-booking).
  Multi-seat atomicity uses **capture-with-compensation**: no thread ever holds
  two seat locks, so deadlock is impossible by construction.
- **`Booking.confirm()/cancel()`** are package-private in the doc so only the
  facade drives them. Split across packages here they are `public` with a
  Javadoc note — Java can't express cross-package "friend" access without
  modules. Treat them as facade-driven.
