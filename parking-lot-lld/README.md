# Parking Lot System — Reference Implementation

Runnable Java scaffold for the design in [parking-lot-lld.md](parking-lot-lld.md).
No build tool or external dependencies — plain `javac` on JDK 11+.

## Run

```bash
# from this folder
javac -d out $(find src -name '*.java')
java -cp out parkinglot.demo.Demo
```

Or use the helper: `./run.sh` (bash) / `run.ps1` (PowerShell).

## Layout (`src/parkinglot/`)

Files are grouped into subpackages by responsibility:

| Package | Files | Role | Pattern |
| --- | --- | --- | --- |
| `enums` | `VehicleType`, `SpotType`, `TicketStatus`, `PaymentStatus` | closed value sets | — |
| `vehicle` | `Vehicle` + `Motorcycle` / `Car` / `Truck` | vehicle hierarchy | — |
| `vehicle` | `VehicleFactory` | centralized creation | Factory |
| `model` | `ParkingSpot` | occupancy of one spot | — |
| `model` | `ParkingLevel` | spot assignment + concurrency primitive | — |
| `model` | `Ticket`, `Payment` | stay record, settlement | — |
| `fee` | `ParkingFeeStrategy` + `HourlyRate` / `FlatRate` | pluggable pricing | Strategy |
| `exception` | `ParkingException` + 3 subtypes | domain failures | — |
| `lot` | `ParkingLot` | facade + booking workflow | Facade |
| `lot` | `ParkingLotBuilder` | readable setup | Builder |
| `demo` | `Demo` | concurrent multi-gate demo | — |

## What the demo shows

- **8 gates race to park 40 cars** into 20 compact spots (10 × 2 levels);
  exactly 20 park, the rest fail cleanly with `ParkingFullException`. Proves no
  two vehicles get the same spot.
- **Exit + fee**: one vehicle exits, its fee is computed (min 1 hour × CAR rate),
  and its spot returns to `AVAILABLE`.

## Design notes

- **Concurrency is localized in `ParkingLevel`.** Claiming a spot is a single
  atomic `ConcurrentLinkedQueue.poll()`; returning it is `offer()`. No locks on
  the hot path; two gates can never receive the same spot. Availability counters
  (`AtomicInteger`) are an O(1) display gauge, eventually consistent with the
  queue (the queue is the assignment authority).
- **Exit is idempotent** via `synchronized (ticket)` + status check — a double
  exit on the same ticket fails fast instead of double-freeing/double-charging.
- **`ParkingSpot.assign()/release()`** stay package-private (only `ParkingLevel`
  in the same `model` package drives them). **`Ticket.close()`** is
  package-private in the doc; split across packages here it is `public` with a
  Javadoc note — treat it as facade-driven.
