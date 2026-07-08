package net.skds.wpo.river.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.skds.wpo.fluidphysics.FFluidStatic;
import net.skds.wpo.river.RiverCurrentField;

@Mixin(value = FFluidStatic.class, remap = false)
final class FFluidStaticMixin {

    private FFluidStaticMixin() {
    }

    @Inject(method = "getVel", at = @At("RETURN"), cancellable = true)
    private static void wpoRiver$addRiverCurrent(
            BlockGetter level,
            BlockPos pos,
            FluidState state,
            CallbackInfoReturnable<Vec3> callback
    ) {
        Vec3 river = RiverCurrentField.flowVector(level, pos, state);
        if (river.lengthSqr() <= 0.0D) {
            return;
        }

        Vec3 base = callback.getReturnValue();
        if (base.lengthSqr() <= 0.0D) {
            callback.setReturnValue(river);
            return;
        }

        Vec3 combined = base.add(river);
        callback.setReturnValue(combined.lengthSqr() > 1.0D ? combined.normalize() : combined);
    }
}
