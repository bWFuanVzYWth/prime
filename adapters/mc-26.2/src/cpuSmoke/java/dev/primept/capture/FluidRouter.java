package dev.primept.capture;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Fluid appearance and neighborhood only. No tessellator, light query or vertex output. */
final class FluidRouter {
    private static final Direction[] FACES = {Direction.DOWN,  Direction.UP,   Direction.NORTH,
                                              Direction.SOUTH, Direction.WEST, Direction.EAST};
    static boolean write(RouteBuffer out, FluidStateModelSet models, BlockAndTintGetter level,
                         BlockPos pos, BlockState state, FluidState fluid) {
        var neighbors = new BlockState[6];
        int visible = 0, overlay = 0;
        for (int i = 0; i < 6; i++) {
            var neighbor = neighbors[i] = level.getBlockState(pos.relative(FACES[i]));
            if (!neighbor.getFluidState().getType().isSame(fluid.getType()))
                visible |= 1 << i;
            if (FluidRenderingRegistry.isBlockTransparent(neighbor.getBlock()) ||
                neighbor.getBlock() instanceof LeavesBlock)
                overlay |= 1 << i;
        }
        if (visible == 0)
            return false;
        var model = models.get(fluid);
        int color = model.tintSource() == null ? -1
                                               : model.tintSource().colorInWorld(state, level, pos);
        out.i(TerrainRouter.layer(model.layer()))
                .rgba(color)
                .f(SectionPos.sectionRelative(pos.getX()))
                .f(SectionPos.sectionRelative(pos.getY()))
                .f(SectionPos.sectionRelative(pos.getZ()))
                .i(visible)
                .i(model.overlayMaterial() == null ? 0 : overlay)
                .i((visible & 2) != 0 && fluid.shouldRenderBackwardUpFace(level, pos.above()) ? 1
                                                                                              : 0);
        for (int z = -1; z <= 1; ++z)
            for (int x = -1; x <= 1; ++x) {
                var point = pos.offset(x, 0, z);
                var sample = x == 0 && z == 0 ? state : level.getBlockState(point);
                var sampleFluid = sample.getFluidState();
                float height = -1;
                if (fluid.getType().isSame(sampleFluid.getType())) {
                    height = fluid.getType().isSame(
                                     level.getBlockState(point.above()).getFluidState().getType())
                                     ? 1
                                     : sampleFluid.getOwnHeight();
                } else if (!sample.isSolid())
                    height = 0;
                out.f(height);
            }
        var flow =
                (visible & 2) != 0 ? fluid.getFlow(level, pos) : net.minecraft.world.phys.Vec3.ZERO;
        out.d(flow.x).d(flow.z);
        sprite(out, model.stillMaterial().sprite());
        sprite(out, model.flowingMaterial().sprite());
        sprite(out,
               (model.overlayMaterial() == null ? model.flowingMaterial() : model.overlayMaterial())
                       .sprite());
        for (int i = 0; i < 6; ++i)
            shape(out, state.getFaceOcclusionShape(FACES[i]), FACES[i]);
        for (int i = 0; i < 6; ++i)
            shape(out, neighbors[i].getFaceOcclusionShape(FACES[i].getOpposite()), FACES[i]);
        return true;
    }
    private static void sprite(RouteBuffer out, TextureAtlasSprite sprite) {
        out.f(sprite.getU0()).f(sprite.getV0()).f(sprite.getU1()).f(sprite.getV1());
    }
    private static void shape(RouteBuffer out, VoxelShape shape, Direction direction) {
        if (shape.isEmpty()) {
            out.i(0);
            return;
        }
        if (shape == Shapes.block()) {
            out.i(1);
            return;
        }
        var boxes = shape.toAabbs();
        out.i(2).i(boxes.size());
        for (var box : boxes) {
            switch (direction.getAxis()) {
            case Y -> out.d(box.minX).d(box.minZ).d(box.maxX).d(box.maxZ);
            case Z -> out.d(box.minX).d(box.minY).d(box.maxX).d(box.maxY);
            case X -> out.d(box.minZ).d(box.minY).d(box.maxZ).d(box.maxY);
            }
        }
    }
}
