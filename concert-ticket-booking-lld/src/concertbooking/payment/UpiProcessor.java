package concertbooking.payment;

/** Second implementation showing OCP: a new payment method needs no engine edits. */
public class UpiProcessor implements PaymentProcessor {
    @Override
    public boolean process(double amount) {
        // UPI gateway integration lives behind this seam; demo always succeeds.
        return true;
    }
}
