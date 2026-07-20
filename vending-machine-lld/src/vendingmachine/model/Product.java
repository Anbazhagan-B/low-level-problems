package vendingmachine.model;

import java.util.Objects;

/** Immutable value-like domain object: code, name, price. Safe to share across threads. */
public final class Product {
    private final String code;               // slot code, e.g. "A1"
    private final String name;
    private final int price;                 // smallest unit

    public Product(String code, String name, int price) {
        if (price <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.price = price;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }
}
