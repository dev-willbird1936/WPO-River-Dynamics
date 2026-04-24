package net.skds.wpo.river;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

record FlowDirective(Direction direction, BlockPos target, String source) {
}
