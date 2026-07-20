package concertbooking.payment;

/** Strategy seam: a family of interchangeable payment algorithms. */
public interface PaymentProcessor {
    boolean process(double amount);
}
