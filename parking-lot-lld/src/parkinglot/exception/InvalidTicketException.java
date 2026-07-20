package parkinglot.exception;

/** Unknown or already-closed ticket presented at exit. */
public class InvalidTicketException extends ParkingException {
    public InvalidTicketException(String msg) {
        super(msg);
    }
}
