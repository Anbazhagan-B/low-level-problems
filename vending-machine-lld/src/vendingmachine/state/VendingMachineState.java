package vendingmachine.state;

import vendingmachine.enums.Denomination;

/** The contract every state implements. The context delegates to the current state. */
public interface VendingMachineState {
    void selectProduct(String code);

    void insertMoney(Denomination denomination);

    void cancel();
}
