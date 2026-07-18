package net.skds.wpo.river;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

// References client-only classes - must only be loaded behind an FMLEnvironment.dist
// check (see RiverCurrentField.publishColumn). setBlocksDirty over a single block marks
// the containing render section plus adjacent ones, which also covers neighbor cells
// whose smoothed vector changed because of this column.
final class RiverClientRefresh {

    private RiverClientRefresh() {
    }

    static void markDirty(Collection<BlockPos> positions) {
        Set<Long> sections = new HashSet<>();
        for (BlockPos pos : positions) {
            sections.add(SectionPos.asLong(pos));
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level != null) {
                for (long key : sections) {
                    SectionPos section = SectionPos.of(key);
                    mc.levelRenderer.setBlocksDirty(
                            section.minBlockX(), section.minBlockY(), section.minBlockZ(),
                            section.maxBlockX(), section.maxBlockY(), section.maxBlockZ());
                }
            }
        });
    }
}
