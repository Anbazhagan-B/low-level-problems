package vendingmachine.exception;

public class InsufficientChangeException extends VendingMachineException {
    public InsufficientChangeException(int amount) {
        super("Cannot dispense exact change of " + amount + "; transaction refunded");
    }
}
