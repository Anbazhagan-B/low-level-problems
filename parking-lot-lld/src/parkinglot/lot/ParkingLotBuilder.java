package parkinglot.lot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import parkinglot.enums.SpotType;
import parkinglot.fee.ParkingFeeStrategy;
import parkinglot.model.ParkingLevel;

/** Builder for readable construction of the level/spot layout. */
public class ParkingLotBuilder {
    private final List<ParkingLevel> levels = new ArrayList<>();
    private ParkingFeeStrategy feeStrategy;

    public ParkingLotBuilder addLevel(int floor, Map<SpotType, Integer> layout) {
        levels.add(new ParkingLevel(floor, layout));
        return this;
    }

    public ParkingLotBuilder withFeeStrategy(ParkingFeeStrategy strategy) {
        this.feeStrategy = strategy;
        return this;
    }

    public ParkingLot build() {
        return new ParkingLot(levels, feeStrategy);
    }
}
