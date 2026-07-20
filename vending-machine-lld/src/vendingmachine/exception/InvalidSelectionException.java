package vendingmachine.exception;

public class InvalidSelectionException extends VendingMachineException {
    public InvalidSelectionException(String code) {
        super("No product at code: " + code);
    }
}
