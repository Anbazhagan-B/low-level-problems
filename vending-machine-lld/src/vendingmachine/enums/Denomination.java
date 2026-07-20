package vendingmachine.enums;

/** The fixed set of accepted coins/notes, each with an integer value (smallest currency unit). */
public enum Denomination {
    COIN_1(1), COIN_2(2), COIN_5(5), COIN_10(10), NOTE_20(20), NOTE_50(50);

    private final int value;   // smallest currency unit; never double for money

    Denomination(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
