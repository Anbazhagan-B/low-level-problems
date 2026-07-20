package vendingmachine.state;

import vendingmachine.enums.Denomination;
import vendingmachine.exception.InvalidOperationException;
import vendingmachine.exception.SoldOutException;
import vendingmachine.machine.VendingMachine;
import vendingmachine.model.Product;

/** No product selected yet: only selectProduct is meaningful. */
public class IdleState implements VendingMachineState {
    private final VendingMachine machine;

    public IdleState(VendingMachine machine) {
        this.machine = machine;
    }

    @Override
    public void selectProduct(String code) {
        Product product = machine.getProductInventory().getProduct(code); // throws if unknown
        if (!machine.getProductInventory().isAvailable(code)) {
            throw new SoldOutException(code);
        }
        machine.beginTransaction(product);
        System.out.printf("Selected %s (%s) — price %d. Insert money.%n",
                product.getName(), code, product.getPrice());
    }

    @Override
    public void insertMoney(Denomination d) {
        // Physical machine would eject the coin; we model it as a rejected operation.
        throw new InvalidOperationException("Select a product before inserting money");
    }

    @Override
    public void cancel() {
        System.out.println("Nothing to cancel.");
    }
}
