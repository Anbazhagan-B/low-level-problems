package vendingmachine.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import vendingmachine.enums.Denomination;
import vendingmachine.exception.InsufficientChangeException;
import vendingmachine.exception.InvalidOperationException;
import vendingmachine.inventory.CashInventory;
import vendingmachine.inventory.ProductInventory;
import vendingmachine.model.Product;
import vendingmachine.state.IdleState;
import vendingmachine.state.ProductSelectedState;
import vendingmachine.state.VendingMachineState;

/**
 * The context/facade: owns the inventories, current state, current-transaction
 * data, and the single lock. Every public entry point takes the lock, so each
 * operation is atomic as a whole (the invariant is cross-field, not per-field).
 */
public class VendingMachine {
    private final ReentrantLock lock = new ReentrantLock();

    private final ProductInventory productInventory = new ProductInventory();
    private final CashInventory cashInventory = new CashInventory();

    // States are stateless w.r.t. the transaction, so one instance each is enough.
    private final VendingMachineState idleState = new IdleState(this);
    private final VendingMachineState selectedState = new ProductSelectedState(this);
    private VendingMachineState state = idleState;

    // ---- current-transaction context (the shared mutable state we protect) ----
    private Product selectedProduct;
    private final List<Denomination> insertedCoins = new ArrayList<>();
    private int insertedAmount;

    // ===================== customer API — every entry takes the lock =========
    public void selectProduct(String code) {
        withLock(() -> state.selectProduct(code));
    }

    public void insertMoney(Denomination d) {
        withLock(() -> state.insertMoney(d));
    }

    public void cancel() {
        withLock(() -> state.cancel());
    }

    // ===================== admin API ==========================================
    public void restock(Product product, int quantity) {
        withLock(() -> productInventory.addProduct(product, quantity));
    }

    public void loadChange(Denomination d, int count) {
        withLock(() -> cashInventory.add(d, count));
    }

    public Map<Denomination, Integer> collectCash() {
        lock.lock();
        try {
            if (state != idleState) {
                throw new InvalidOperationException("Cannot collect cash mid-transaction");
            }
            return cashInventory.collectAll();
        } finally {
            lock.unlock();
        }
    }

    // ===================== state collaboration hooks ==========================
    // Public because the state classes live in a separate package; treat these
    // as internal collaboration methods driven only by the current state.

    public void beginTransaction(Product product) {
        this.selectedProduct = product;
        this.state = selectedState;
    }

    public void recordInsertion(Denomination d) {
        insertedCoins.add(d);
        insertedAmount += d.getValue();
    }

    /**
     * The transactional core. Either: (product dispensed AND correct change returned
     * AND inserted cash banked) — or nothing changes and the customer is refunded.
     */
    public void completePurchase() {
        int changeDue = insertedAmount - selectedProduct.getPrice();

        cashInventory.addAll(insertedCoins);              // 1) tentatively bank coins
        Optional<Map<Denomination, Integer>> change =     // 2) try to make change
                cashInventory.makeChange(changeDue);      //    (inserted coins count!)

        if (change.isEmpty()) {                           // 3a) rollback path
            cashInventory.removeAll(insertedCoins);
            List<Denomination> refund = List.copyOf(insertedCoins);
            resetTransaction();
            System.out.println("Refunding inserted coins: " + describe(refund));
            throw new InsufficientChangeException(changeDue);
        }

        productInventory.reduceStock(selectedProduct.getCode());  // 3b) commit path
        System.out.printf("Dispensing %s. Change %d returned as %s%n",
                selectedProduct.getName(), changeDue, change.get());
        resetTransaction();
    }

    public void refundAndReset() {
        // Cancel refunds the EXACT coins inserted — no change-making needed,
        // because they never entered the cash drawer.
        System.out.println("Cancelled. Refunding: " + describe(insertedCoins));
        resetTransaction();
    }

    private void resetTransaction() {
        selectedProduct = null;
        insertedCoins.clear();
        insertedAmount = 0;
        state = idleState;
    }

    private void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private static String describe(List<Denomination> coins) {
        return coins.isEmpty() ? "(nothing)" : coins.toString();
    }

    // accessors used by states
    public ProductInventory getProductInventory() {
        return productInventory;
    }

    public Product getSelectedProduct() {
        return selectedProduct;
    }

    public int getInsertedAmount() {
        return insertedAmount;
    }
}
