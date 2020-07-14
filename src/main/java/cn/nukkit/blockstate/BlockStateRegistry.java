package cn.nukkit.blockstate;

import cn.nukkit.Server;
import cn.nukkit.api.DeprecationDetails;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.block.BlockUnknown;
import cn.nukkit.blockproperty.BlockProperties;
import cn.nukkit.blockproperty.CommonBlockProperties;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.Tag;
import cn.nukkit.utils.HumanStringComparator;
import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@UtilityClass
@ParametersAreNonnullByDefault
@Log4j2
public class BlockStateRegistry {
    public final int BIG_META_MASK = 0xFFFFFFFF;
    private final Set<String> LEGACY_NAME_SET = Collections.singleton(CommonBlockProperties.LEGACY_PROPERTY_NAME);
    
    private final Registration updateBlockRegistration;

    private final Map<BlockState, Registration> blockStateRegistration = new ConcurrentHashMap<>();
    private final Map<CompoundTag, Registration> originalStateRegistration = new ConcurrentHashMap<>();
    private final Map<String, Registration> stateIdRegistration = new ConcurrentHashMap<>();

    private final AtomicInteger runtimeIdAllocator = new AtomicInteger(0);
    private final Int2ObjectMap<String> blockIdToPersistenceName = new Int2ObjectOpenHashMap<>();
    
    private final byte[] blockPaletteBytes;

    //<editor-fold desc="static initialization" defaultstate="collapsed">
    static {
        Map<CompoundTag, List<CompoundTag>> metaOverrides = new LinkedHashMap<>();
        //<editor-fold desc="Loading runtime_block_states_overrides.dat" defaultstate="collapsed">
        try (InputStream stream = Server.class.getClassLoader().getResourceAsStream("runtime_block_states_overrides.dat")) {
            if (stream == null) {
                throw new AssertionError("Unable to locate block state nbt");
            }

            ListTag<CompoundTag> states;
            try (BufferedInputStream buffered = new BufferedInputStream(stream)) {
                states = NBTIO.read(buffered).getList("Overrides", CompoundTag.class);
            }

            for (CompoundTag override : states.getAll()) {
                if (override.contains("block") && override.contains("LegacyStates")) {
                    metaOverrides.put(override.getCompound("block").remove("version"), override.getList("LegacyStates", CompoundTag.class).getAll());
                }
            }

        } catch (IOException e) {
            throw new AssertionError(e);
        }
        //</editor-fold>

        ListTag<CompoundTag> tag;
        //<editor-fold desc="Loading runtime_block_states.dat" defaultstate="collapsed">
        try (InputStream stream = Server.class.getClassLoader().getResourceAsStream("runtime_block_states.dat")) {
            if (stream == null) {
                throw new AssertionError("Unable to locate block state nbt");
            }

            try (BufferedInputStream buffered = new BufferedInputStream(stream)) {
                //noinspection unchecked
                tag = (ListTag<CompoundTag>) NBTIO.readTag(buffered, ByteOrder.LITTLE_ENDIAN, false);
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        //</editor-fold>

        Registration infoUpdateRuntimeId = null;
        
        for (CompoundTag state : tag.getAll()) {
            int runtimeId = runtimeIdAllocator.getAndIncrement();
            String name = state.getCompound("block").getString("name").toLowerCase();
            
            if (name.equals("minecraft:info_update")) {
                infoUpdateRuntimeId = new Registration(state, BlockState.of(248), runtimeId);
            }
            
            List<CompoundTag> legacyStates = metaOverrides.get(state.getCompound("block").copy().remove("version"));
            if (legacyStates == null) {
                if (!state.contains("LegacyStates")) {
                    registerStateId(state, runtimeId);
                    continue;
                } else {
                    legacyStates = state.getList("LegacyStates", CompoundTag.class).getAll();
                }
            }

            // Resolve to first legacy id
            CompoundTag firstState = legacyStates.get(0);
            int firstId = firstState.getInt("id");
            int firstMeta = firstState.getInt("val");

            // Special condition: minecraft:wood maps 3 blocks, minecraft:wood, minecraft:log and minecraft:log2
            // All other cases, register the name normally
            if (isNameOwnerOfId(name, firstId)) {
                registerPersistenceName(firstId, name);
                registerStateId(state, runtimeId);
                registerState(firstId, firstMeta, state, runtimeId);
            }
            
            registerState(firstId, firstMeta, state, runtimeId);

            for (CompoundTag legacyState : legacyStates) {
                int newBlockId = legacyState.getInt("id");
                int meta = legacyState.getInt("val");
                registerState(newBlockId, meta, state, runtimeId);
                
                if (isNameOwnerOfId(name, newBlockId)) {
                    registerState(newBlockId, meta, state, runtimeId);
                }
            }
            // No point in sending this since the client doesn't use it.
            state.remove("meta");
            state.remove("LegacyStates");
        }

        if (infoUpdateRuntimeId == null) {
            throw new IllegalStateException("Could not find the minecraft:info_update runtime id!");
        }
        
        updateBlockRegistration = infoUpdateRuntimeId;
        
        try {
            blockPaletteBytes = NBTIO.write(tag, ByteOrder.LITTLE_ENDIAN, true);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        
    }
    //</editor-fold>
    
    private boolean isNameOwnerOfId(String name, int blockId) {
        return !name.equals("minecraft:wood") || blockId == BlockID.WOOD_BARK;
    }
    
    @Nonnull
    private String getStateId(CompoundTag block) {
        Map<String, String> propertyMap = new TreeMap<>(HumanStringComparator.getInstance());
        for (Tag tag : block.getCompound("states").getAllTags()) {
            propertyMap.put(tag.getName(), tag.parseValue().toString());
        }

        String blockName = block.getString("name");
        Preconditions.checkArgument(!blockName.isEmpty(), "Couldn't find the block name!");
        StringBuilder stateId = new StringBuilder(blockName);
        propertyMap.forEach((name, value) -> stateId.append(';').append(name).append('=').append(value));
        return stateId.toString();
    }

    public CompoundTag getOriginalState(BlockState state) {
        return getRegistration(state).original.clone();
    }
    
    public int getRuntimeId(BlockState state) {
        return getRegistration(state).runtimeId;
    }
    
    @Nullable
    public BlockState getBlockStateFromOriginal(CompoundTag original) {
        Registration registration = originalStateRegistration.get(original);
        if (registration == null) {
            return null;
        }
        return registration.state;
    }
    
    private Registration getRegistration(BlockState state) {
        return blockStateRegistration.computeIfAbsent(state, BlockStateRegistry::findRegistration);
    }

    public int getRuntimeId(int blockId) {
        return getRuntimeId(BlockState.of(blockId));
    }

    @Deprecated
    @DeprecationDetails(reason = "The meta is limited to 32 bits", replaceWith = "getRuntimeId(BlockState state)", since = "1.3.0.0-PN")
    public int getRuntimeId(int blockId, int meta) {
        return getRuntimeId(BlockState.of(blockId, meta));
    }

    private Registration findRegistration(final BlockState state) {
        // Special case for PN-96 PowerNukkit#210 where the world contains blocks like 0:13, 0:7, etc
        if (state.getBlockId() == BlockID.AIR) {
            Registration airRegistration = blockStateRegistration.get(BlockState.AIR);
            if (airRegistration != null) {
                return new Registration(airRegistration.original, state, airRegistration.runtimeId);
            }
        }
        
        Registration registration;
        Set<String> names = state.getPropertyNames();
        if (names.isEmpty() || names.equals(LEGACY_NAME_SET)) {
            registration = logDiscoveryError(state);
        } else {
            registration = findRegistrationByStateId(state);
        }
        removeStateIdsAsync(registration);
        return registration;
    }

    private Registration findRegistrationByStateId(BlockState state) {
        Registration registration = stateIdRegistration.remove(state.getStateId());
        if (registration != null) {
            registration.state = state;
            return registration;
        }

        registration = stateIdRegistration.remove(state.getLegacyStateId());
        if (registration != null) {
            registration.state = state;
            return registration;
        }

        return logDiscoveryError(state);
    }
    
    private void removeStateIdsAsync(@Nullable Registration registration) {
        if (registration != null && registration != updateBlockRegistration) {
            new Thread(() -> stateIdRegistration.values().removeIf(r -> r.runtimeId == registration.runtimeId)).start();
        }
    }

    private Registration logDiscoveryError(BlockState state) {
        log.error("Found an unknown BlockId:Meta combination: "+state.getBlockId()+":"+state.getDataStorage()+", replacing with an \"UPDATE!\" block.");
        return updateBlockRegistration;
    }

    @Nullable
    public String getPersistenceName(int blockId) {
        return blockIdToPersistenceName.get(blockId);
    }

    public void registerPersistenceName(int blockId, String persistenceName) {
        synchronized (blockIdToPersistenceName) {
            String oldName = blockIdToPersistenceName.putIfAbsent(blockId, persistenceName.toLowerCase());
            if (oldName != null && !persistenceName.equalsIgnoreCase(oldName)) {
                throw new UnsupportedOperationException("The persistence name registration tried to replaced a name. Name:" + persistenceName + ", Old:" + oldName + ", Id:" + blockId);
            }
        }
    }

    private void registerStateId(CompoundTag state, int runtimeId) {
        String stateId = getStateId(state.getCompound("block"));
        Registration registration = new Registration(state, null, runtimeId);
        
        Registration old = stateIdRegistration.putIfAbsent(stateId, registration);
        if (old != null && !old.equals(registration)) {
            throw new UnsupportedOperationException("The persistence NBT registration tried to replaced a runtime id. Old:"+old+", New:"+runtimeId+", State:"+stateId);
        }
    }
    
    private void registerState(int blockId, int meta, CompoundTag originalState, int runtimeId) {
        BlockState state = BlockState.of(blockId, meta);
        Registration registration = new Registration(originalState, state, runtimeId);

        Registration old = blockStateRegistration.putIfAbsent(state, registration);
        if (old != null && !registration.equals(old)) {
            throw new UnsupportedOperationException("The persistence NBT registration tried to replaced a runtime id. Old:"+old+", New:"+runtimeId+", State:"+state);
        }

        CompoundTag block = originalState.getCompound("block");
        originalStateRegistration.put(block, registration);
        stateIdRegistration.remove(getStateId(block));
        stateIdRegistration.remove(state.getLegacyStateId());
    }
    
    public int getBlockPaletteDataVersion() {
        @SuppressWarnings("UnnecessaryLocalVariable")
        Object obj = blockPaletteBytes;
        return obj.hashCode();
    }

    @Nonnull
    public byte[] getBlockPaletteBytes() {
        return blockPaletteBytes.clone();
    }
    
    public int getBlockPaletteLength() {
        return blockPaletteBytes.length;
    }
    
    public void copyBlockPaletteBytes(byte[] target, int targetIndex) {
        System.arraycopy(blockPaletteBytes, 0, target, targetIndex, blockPaletteBytes.length);
    }

    @SuppressWarnings({"deprecation", "squid:CallToDepreca"})
    @Nonnull
    public BlockProperties getProperties(int blockId) {
        int fullId = blockId << Block.DATA_BITS;
        Block block;
        if (fullId >= Block.fullList.length || (block = Block.fullList[fullId]) == null) {
            return BlockUnknown.PROPERTIES;
        }
        return block.getProperties();
    }

    @Nonnull
    public MutableBlockState createMutableState(int blockId) {
        return getProperties(blockId).createMutableState(blockId);
    }
    
    @Nonnull
    public MutableBlockState createMutableState(int blockId, int bigMeta) {
        MutableBlockState blockState = createMutableState(blockId);
        blockState.setDataStorageFromInt(bigMeta);
        return blockState;
    }

    @Nonnull
    public MutableBlockState createMutableState(int blockId, Number storage) {
        MutableBlockState blockState = createMutableState(blockId);
        blockState.setDataStorage(storage);
        return blockState;
    }

    public int getUpdateBlockRegistration() {
        return updateBlockRegistration.runtimeId;
    }

    @AllArgsConstructor
    @ToString
    @EqualsAndHashCode
    private class Registration {
        @Nonnull
        private final CompoundTag original;
        @Nullable
        private BlockState state;
        
        private final int runtimeId;
    }
}
