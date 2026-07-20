package parkinglot.exception;

/** Payment did not complete; spot stays claimed and ticket stays ACTIVE (no partial state). */
public class PaymentFailedException extends ParkingException {
    public PaymentFailedException(String msg) {
        super(msg);
    }
}
