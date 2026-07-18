package net.skds.wpo.river;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

// direction/target drive transport and algorithm steering; vecX/vecZ is the true flow
// vector, while speed is the physical target velocity in blocks per tick. Resolvers
// without channel-scale geometry leave speed as NaN for the local estimator.
record FlowDirective(Direction direction, BlockPos target, String source, double vecX, double vecZ, double speed) {

    FlowDirective(Direction direction, BlockPos target, String source) {
        this(direction, target, source, direction.getStepX(), direction.getStepZ(), Double.NaN);
    }

    FlowDirective(Direction direction, BlockPos target, String source, double vecX, double vecZ) {
        this(direction, target, source, vecX, vecZ, Double.NaN);
    }
}
