package concertbooking.exception;

/** Thrown when payment fails; all captured seats are released and no booking is recorded. */
public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String msg) {
        super(msg);
    }
}
