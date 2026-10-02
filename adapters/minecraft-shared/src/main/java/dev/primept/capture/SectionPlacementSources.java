package dev.primept.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.lang.foreign.MemorySegment;
import dev.primept.abi.PrimeAbi.PrimeMcPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Source declarations only: positional offset/seed evaluation belongs to Rust. */
final class SectionPlacementSources {
    private static final Field OFFSET =
            field(BlockBehaviour.BlockStateBase.class, "offsetFunction");
    private static final Field PROPERTY_OFFSET =
            field(BlockBehaviour.Properties.class, "offsetFunction");
    private static final Class<?> XZ = standardOffset(BlockBehaviour.OffsetType.XZ),
                                  XYZ = standardOffset(BlockBehaviour.OffsetType.XYZ);
    private final Class<?> bedSeedDeclaration;
    private final IdentityHashMap<Class<?>, Methods> methods = new IdentityHashMap<>();

    SectionPlacementSources(Class<?> bedSeedDeclaration) {
        this.bedSeedDeclaration = bedSeedDeclaration;
    }
    record Placement(int offset, float horizontal, float vertical, int seed) {
        void write(MemorySegment out) {
            PrimeMcPlacement.offset(out, offset);
            PrimeMcPlacement.horizontal(out, horizontal);
            PrimeMcPlacement.vertical(out, vertical);
            PrimeMcPlacement.seed(out, seed);
        }
        boolean hasOffset() {
            return offset != 0;
        }
    }
    Placement prepare(BlockState state) {
        var block = state.getBlock();
        var binding = methods.computeIfAbsent(block.getClass(), Methods::new);
        Object offset = get(OFFSET, state);
        int kind = offset == null             ? 0
                   : offset.getClass() == XZ  ? 1
                   : offset.getClass() == XYZ ? 2
                                              : 3;
        float horizontal = 0, vertical = 0;
        if (kind == 1 || kind == 2) {
            var h = binding.horizontal.getDeclaringClass();
            var v = binding.vertical.getDeclaringClass();
            if ((h != BlockBehaviour.class && h != SpeleothemBlock.class) ||
                (kind == 2 && v != BlockBehaviour.class && v != SmallDripleafBlock.class)) {
                kind = 3;
            } else {
                // These exact declarations return immutable vanilla constants. Unknown overrides
                // are never executed to infer a positional rule or a supposedly constant value.
                horizontal = number(binding.horizontal, block);
                if (kind == 2)
                    vertical = number(binding.vertical, block);
            }
        }
        var seedOwner = binding.seed.getDeclaringClass();
        int seed;
        if (seedOwner == BlockBehaviour.class) {
            seed = 0;
        } else if (seedOwner == DoublePlantBlock.class || seedOwner == DoorBlock.class) {
            seed = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                           ? 1
                           : 0;
        } else if (seedOwner == bedSeedDeclaration) {
            seed = state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                           ? 0
                           : switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
                                 case NORTH -> 2;
                                 case SOUTH -> 3;
                                 case WEST -> 4;
                                 case EAST -> 5;
                                 default ->
                                     throw new IllegalStateException(
                                             "Non-horizontal bed source facing");
                             };
        } else {
            seed = 6;
        }
        return new Placement(kind, horizontal, vertical, seed);
    }
    private static final class Methods {
        final Method horizontal, vertical, seed;
        Methods(Class<?> type) {
            horizontal = method(type, "getMaxHorizontalOffset");
            vertical = method(type, "getMaxVerticalOffset");
            seed = method(type, "getSeed", BlockState.class, BlockPos.class);
        }
    }
    private static Class<?> standardOffset(BlockBehaviour.OffsetType type) {
        // The noncapturing lambda's actual class identifies the standard implementation. This
        // does not evaluate any source offset or construct a source BlockState/model.
        return get(PROPERTY_OFFSET, BlockBehaviour.Properties.of().offsetType(type)).getClass();
    }
    private static float number(Method method, Object source) {
        try {
            return (float)method.invoke(source);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot read standard placement source", error);
        }
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                var method = owner.getDeclaredMethod(name, arguments);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException absent) {
                // Continue to the actual inherited declaration, not a type-name guess.
            }
        }
        throw new IllegalStateException("Missing placement source declaration: " + name);
    }
    private static Field field(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
    private static Object get(Field field, Object source) {
        try {
            return field.get(source);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }
}
