package vendingmachine.inventory;

import java.util.HashMap;
import java.util.Map;

import vendingmachine.exception.InvalidSelectionException;
import vendingmachine.exception.SoldOutException;
import vendingmachine.model.Product;

/** Which products exist + how many remain. Not internally synchronized — all access is under the machine's lock. */
public class ProductInventory {
    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Integer> stock = new HashMap<>();

    public void addProduct(Product product, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
        products.put(product.getCode(), product);
        stock.merge(product.getCode(), quantity, Integer::sum);
    }

    public Product getProduct(String code) {
        Product p = products.get(code);
        if (p == null) {
            throw new InvalidSelectionException(code);
        }
        return p;
    }

    public boolean isAvailable(String code) {
        return stock.getOrDefault(code, 0) > 0;
    }

    public void reduceStock(String code) {
        int current = stock.getOrDefault(code, 0);
        if (current <= 0) {
            throw new SoldOutException(code);   // defense in depth
        }
        stock.put(code, current - 1);
    }
}
