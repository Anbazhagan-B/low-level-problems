package parkinglot.exception;

/** Base type for parking-domain failures. */
public class ParkingException extends RuntimeException {
    public ParkingException(String msg) {
        super(msg);
    }
}
