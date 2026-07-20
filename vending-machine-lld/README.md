# Vending Machine — Reference Implementation

Runnable Java scaffold for the design in [vending-machine-lld.md](vending-machine-lld.md).
No build tool or external dependencies — plain `javac` on JDK 11+.

## Run

```bash
# from this folder
javac -d out $(find src -name '*.java')
java -cp out vendingmachine.demo.Demo
```

Or use the helper: `./run.sh` (bash) / `run.ps1` (PowerShell).

## Layout (`src/vendingmachine/`)

Files are grouped into subpackages by responsibility:

| Package | Files | Role | Pattern |
| --- | --- | --- | --- |
| `enums` | `Denomination` | accepted coins/notes | — |
| `model` | `Product` | immutable catalog item | — |
| `exception` | `VendingMachineException` + 4 subtypes | domain failures | — |
| `inventory` | `ProductInventory` | stock-keeping | — |
| `inventory` | `CashInventory` | drawer + greedy change-making | Strategy (latent) |
| `state` | `VendingMachineState` + `IdleState` / `ProductSelectedState` | per-mode behavior | State |
| `machine` | `VendingMachine` | context/facade, lock, transaction core | Facade |
| `demo` | `Demo` | runnable demo | — |

## What the demo shows

1. **Happy path** — pay 25 as 20+10, get 5 change from a scarce loaded coin.
2. **Insufficient change** — pay 25 as a 50; the drawer can't compose 25 change,
   so the transaction rolls back and refunds the exact coins.
3. **Cancel** — refunds the literal coins inserted (never banked).
4. **Admin collect** — empties the drawer.

## Design notes

- **State pattern** — behavior of `selectProduct`/`insertMoney`/`cancel` depends
  on the current mode. Each mode is a class; the machine delegates to the current
  state. Adding a state (e.g. `MaintenanceState`) adds a class, not conditionals.
- **Transactional core** — `completePurchase()` tentatively banks the inserted
  coins (they are legal change material), attempts change atomically, and rolls
  back + refunds on failure. Either everything commits or nothing does.
- **Concurrency** — one `ReentrantLock` at the facade boundary guards every entry
  point. The invariant is cross-field, so each operation must be atomic as a
  whole; a single lock also makes deadlock impossible by construction.
- **Package split note** — the state classes and `VendingMachine` are in separate
  packages, so the machine's state-collaboration hooks (`beginTransaction`,
  `recordInsertion`, `completePurchase`, `refundAndReset`, and the accessors) are
  `public` rather than package-private. Treat them as state-internal.
