package vendingmachine.demo;

import java.util.Map;

import vendingmachine.enums.Denomination;
import vendingmachine.exception.InsufficientChangeException;
import vendingmachine.machine.VendingMachine;
import vendingmachine.model.Product;

/**
 * Runnable demo:
 *   1. happy path (change made from a scarce loaded coin),
 *   2. insufficient-change rollback + refund,
 *   3. cancel refunds the exact coins,
 *   4. admin collects the banked cash.
 */
public class Demo {
    public static void main(String[] args) {
        VendingMachine vm = new VendingMachine();
        vm.restock(new Product("A1", "Cola", 25), 2);
        vm.restock(new Product("A2", "Chips", 35), 1);
        vm.loadChange(Denomination.COIN_5, 1);     // deliberately scarce change

        // Happy path: 25 paid as 20+10, change 5 — uses the loaded COIN_5
        vm.selectProduct("A1");
        vm.insertMoney(Denomination.NOTE_20);
        vm.insertMoney(Denomination.COIN_10);

        // Insufficient change: 25 paid as 50, change 25 — only a 20 and a 10 in
        // the drawer now (just banked), can't compose 25 -> rollback + refund
        vm.selectProduct("A1");
        try {
            vm.insertMoney(Denomination.NOTE_50);
        } catch (InsufficientChangeException e) {
            System.out.println("Machine says: " + e.getMessage());
        }

        // Cancel path
        vm.selectProduct("A2");
        vm.insertMoney(Denomination.COIN_10);
        vm.cancel();                                // refunds exactly [COIN_10]

        // Admin collects
        Map<Denomination, Integer> cash = vm.collectCash();
        System.out.println("Collected: " + cash);
    }
}
