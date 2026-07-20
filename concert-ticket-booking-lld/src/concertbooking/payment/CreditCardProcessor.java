package concertbooking.payment;

public class CreditCardProcessor implements PaymentProcessor {
    @Override
    public boolean process(double amount) {
        // Gateway integration lives behind this seam; demo always succeeds.
        return true;
    }
}
