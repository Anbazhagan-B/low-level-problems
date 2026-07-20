package vendingmachine.exception;

public class SoldOutException extends VendingMachineException {
    public SoldOutException(String code) {
        super("Product sold out: " + code);
    }
}
