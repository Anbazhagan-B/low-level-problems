package parkinglot.model;

import parkinglot.enums.PaymentStatus;

/** Settles a fee. Replace process() with a real gateway call. */
public class Payment {
    private final double amount;
    private PaymentStatus status;

    public Payment(double amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    /** Replace the body with a real gateway call; returns true on success. */
    public boolean process() {
        this.status = PaymentStatus.COMPLETED; // simulated
        return true;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public double getAmount() {
        return amount;
    }
}
