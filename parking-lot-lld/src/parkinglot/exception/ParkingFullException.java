package parkinglot.exception;

/** No compatible spot available anywhere in the lot. */
public class ParkingFullException extends ParkingException {
    public ParkingFullException(String msg) {
        super(msg);
    }
}
