package vendingmachine.state;

import vendingmachine.enums.Denomination;
import vendingmachine.exception.InvalidOperationException;
import vendingmachine.machine.VendingMachine;

/** A product is selected: insertMoney accumulates credit; a second select is rejected. */
public class ProductSelectedState implements VendingMachineState {
    private final VendingMachine machine;

    public ProductSelectedState(VendingMachine machine) {
        this.machine = machine;
    }

    @Override
    public void selectProduct(String code) {
        // Design choice: no mid-transaction switching. Cancel first.
        throw new InvalidOperationException(
                "Transaction in progress for " + machine.getSelectedProduct().getCode()
                        + "; cancel to choose a different product");
    }

    @Override
    public void insertMoney(Denomination d) {
        machine.recordInsertion(d);
        int price = machine.getSelectedProduct().getPrice();
        System.out.printf("Inserted %d. Credit: %d / %d%n",
                d.getValue(), machine.getInsertedAmount(), price);
        if (machine.getInsertedAmount() >= price) {
            machine.completePurchase();      // dispense + change happen atomically here
        }
    }

    @Override
    public void cancel() {
        machine.refundAndReset();
    }
}
