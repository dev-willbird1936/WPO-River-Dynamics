package net.skds.wpo.river;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// Client-only. Must only be referenced behind an FMLEnvironment.dist == Dist.CLIENT check
// (see RiverDynamics' constructor) - references Minecraft/rendering classes that do not
// exist on a dedicated server. Draws one flat, camera-unlit textured quad per marker from
// RiverFlowArrows' latest snapshot: an arrow rotated to match flow direction, or a cross
// for confirmed-idle water. AFTER_TRANSLUCENT_BLOCKS is documented as unreliable for
// translucency (sorting runs against the chunk buffers, not this pass), so this hooks
// AFTER_TRIPWIRE_BLOCKS instead - the last block layer, right after water renders.
final class RiverFlowOverlayRenderer {

    private static final ResourceLocation ARROW_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(RiverDynamics.MOD_ID, "textures/debug/arrow.png");
    private static final ResourceLocation CROSS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(RiverDynamics.MOD_ID, "textures/debug/cross.png");
    private static final RenderType ARROW_TYPE = RenderType.entityTranslucentEmissive(ARROW_TEXTURE);
    private static final RenderType CROSS_TYPE = RenderType.entityTranslucentEmissive(CROSS_TEXTURE);

    private static final double HALF_LENGTH = 0.47D;
    private static final double HALF_WIDTH = 0.47D;
    private static final double SURFACE_Y_OFFSET = 1.02D;

    private RiverFlowOverlayRenderer() {
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            return;
        }
        List<RiverFlowArrows.Marker> markers = RiverFlowArrows.snapshot();
        if (markers.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        for (RiverFlowArrows.Marker marker : markers) {
            BlockPos pos = marker.pos();
            double centerX = pos.getX() + 0.5D;
            double centerY = pos.getY() + SURFACE_Y_OFFSET;
            double centerZ = pos.getZ() + 0.5D;

            double forwardX;
            double forwardZ;
            double halfLength;
            float red;
            float green;
            float blue;
            RenderType type;
            if (marker.idle()) {
                forwardX = 1.0D;
                forwardZ = 0.0D;
                halfLength = HALF_LENGTH;
                red = 0.65F;
                green = 0.65F;
                blue = 0.65F;
                type = CROSS_TYPE;
            } else {
                double length = Math.sqrt(marker.dirX() * marker.dirX() + marker.dirZ() * marker.dirZ());
                if (length < 1.0E-6D) {
                    continue;
                }
                forwardX = marker.dirX() / length;
                forwardZ = marker.dirZ() / length;
                double speedRatio = Math.max(0.0D, Math.min(1.0D,
                        (marker.speed() - RiverHydraulics.MIN_SPEED)
                                / (RiverHydraulics.MAX_SPEED - RiverHydraulics.MIN_SPEED)));
                halfLength = 0.32D + speedRatio * 0.15D;
                red = (float) (0.15D + speedRatio * 0.85D);
                green = (float) (0.85D - speedRatio * 0.55D);
                blue = (float) (1.0D - speedRatio * 0.85D);
                type = ARROW_TYPE;
            }
            double rightX = -forwardZ;
            double rightZ = forwardX;

            VertexConsumer consumer = bufferSource.getBuffer(type);
            emitQuad(consumer, matrix, cam, centerX, centerY, centerZ,
                    forwardX, forwardZ, rightX, rightZ, halfLength, red, green, blue);
        }

        bufferSource.endBatch(ARROW_TYPE);
        bufferSource.endBatch(CROSS_TYPE);
    }

    private static void emitQuad(
            VertexConsumer consumer,
            Matrix4f matrix,
            Vec3 cam,
            double centerX, double centerY, double centerZ,
            double forwardX, double forwardZ,
            double rightX, double rightZ,
            double halfLength,
            float red, float green, float blue
    ) {
        // Tail-right, tail-left, tip-left, tip-right - consistent winding; NO_CULL means
        // draw order only needs to be consistent, not front-facing.
        vertex(consumer, matrix, cam,
                centerX - forwardX * halfLength + rightX * HALF_WIDTH, centerY,
                centerZ - forwardZ * halfLength + rightZ * HALF_WIDTH, 0.0F, 0.0F, red, green, blue);
        vertex(consumer, matrix, cam,
                centerX - forwardX * halfLength - rightX * HALF_WIDTH, centerY,
                centerZ - forwardZ * halfLength - rightZ * HALF_WIDTH, 0.0F, 1.0F, red, green, blue);
        vertex(consumer, matrix, cam,
                centerX + forwardX * halfLength - rightX * HALF_WIDTH, centerY,
                centerZ + forwardZ * halfLength - rightZ * HALF_WIDTH, 1.0F, 1.0F, red, green, blue);
        vertex(consumer, matrix, cam,
                centerX + forwardX * halfLength + rightX * HALF_WIDTH, centerY,
                centerZ + forwardZ * halfLength + rightZ * HALF_WIDTH, 1.0F, 0.0F, red, green, blue);
    }

    private static void vertex(
            VertexConsumer consumer, Matrix4f matrix, Vec3 cam,
            double worldX, double worldY, double worldZ, float u, float v,
            float red, float green, float blue
    ) {
        consumer.addVertex(matrix,
                        (float) (worldX - cam.x()),
                        (float) (worldY - cam.y()),
                        (float) (worldZ - cam.z()))
                .setColor(red, green, blue, 1.0F)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }
}
