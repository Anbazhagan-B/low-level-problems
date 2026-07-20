package vendingmachine.exception;

/**
 * Base type for vending-machine domain failures. Unchecked: these are domain
 * outcomes the caller can't "fix" at each call site; a single boundary handler
 * maps them to user messages.
 */
public class VendingMachineException extends RuntimeException {
    public VendingMachineException(String message) {
        super(message);
    }
}
