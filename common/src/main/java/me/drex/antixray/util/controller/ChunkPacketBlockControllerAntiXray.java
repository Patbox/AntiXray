package me.drex.antixray.util.controller;

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import me.drex.antixray.AntiXray;
import me.drex.antixray.interfaces.IChunkPacket;
import me.drex.antixray.util.BitStorageReader;
import me.drex.antixray.util.BitStorageWriter;
import me.drex.antixray.util.ChunkPacketInfo;
import me.drex.antixray.util.ChunkPacketInfoAntiXray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

public abstract class ChunkPacketBlockControllerAntiXray implements ChunkPacketBlockController {

    protected static final Palette<BlockState> GLOBAL_BLOCKSTATE_PALETTE = new GlobalPalette<>(Block.BLOCK_STATE_REGISTRY);
    private static final LevelChunkSection EMPTY_SECTION = null;

    protected final int maxBlockHeight;
    private final int maxBlockHeightUpdatePosition;
    private final int updateRadius;
    private final Object2BooleanOpenHashMap<BlockState> solidGlobal = new Object2BooleanOpenHashMap<>(Block.BLOCK_STATE_REGISTRY.size());
    private final Object2BooleanOpenHashMap<BlockState> obfuscateGlobal = new Object2BooleanOpenHashMap<>(Block.BLOCK_STATE_REGISTRY.size());
    private final Executor executor;
    private final LevelChunkSection[] emptyNearbyChunkSections = {EMPTY_SECTION, EMPTY_SECTION, EMPTY_SECTION, EMPTY_SECTION};

    protected ChunkPacketBlockControllerAntiXray(Level level, Executor executor, Set<Block> toObfuscate, int maxBlockHeight, int updateRadius, boolean lavaObscures) {
        this.executor = executor;
        this.maxBlockHeight = maxBlockHeight;
        this.updateRadius = updateRadius;

        for (Block block : toObfuscate) {
            // Don't obfuscate air because air causes unnecessary block updates and causes block updates to fail in the void
            if (!block.defaultBlockState().isAir()) {
                // Replace all block states of a specified block
                for (BlockState blockState : block.getStateDefinition().getPossibleStates()) {
                    obfuscateGlobal.put(blockState, true);
                }
            }
        }

        EmptyLevelChunk emptyChunk = new EmptyLevelChunk(level, new ChunkPos(0, 0), level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));

        Block.BLOCK_STATE_REGISTRY.iterator().forEachRemaining((blockState) -> {
                    solidGlobal.put(blockState, blockState.isRedstoneConductor(emptyChunk, BlockPos.ZERO)
                            && !blockState.canOcclude()
                            && blockState.getBlock() != Blocks.SPAWNER && blockState.getBlock() != Blocks.BARRIER
                            && blockState.getBlock() != Blocks.SHULKER_BOX && blockState.getBlock() != Blocks.SLIME_BLOCK
                            && blockState.getBlock() != Blocks.MANGROVE_ROOTS

                            || lavaObscures && blockState == Blocks.LAVA.defaultBlockState());
                    // Comparing blockState == Blocks.LAVA.defaultBlockState() instead of blockState.getBlock() == Blocks.LAVA ensures that only "stationary lava" is used
                    // shulker box checks TE.
                }
        );

        this.maxBlockHeightUpdatePosition = maxBlockHeight + updateRadius - 1;

    }

    @Override
    public ChunkPacketInfoAntiXray getChunkPacketInfo(ClientboundLevelChunkWithLightPacket chunkPacket, LevelChunk chunk) {
        // Return a new instance to collect data and objects in the right state while creating the chunk packet for thread safe access later
        return new ChunkPacketInfoAntiXray(chunkPacket, chunk, this);
    }

    @Override
    public void onBlockChange(Level level, BlockPos blockPos, BlockState newBlockState, BlockState oldBlockState, int flags, int maxUpdateDepth) {
        if (oldBlockState != null && solidGlobal.getOrDefault(oldBlockState, false) && !solidGlobal.getOrDefault(newBlockState, false) && blockPos.getY() <= maxBlockHeightUpdatePosition) {
            updateNearbyBlocks(level, blockPos);
        }
    }

    @Override
    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket, ChunkPacketInfo<BlockState> chunkPacketInfo) {
        if (!(chunkPacketInfo instanceof ChunkPacketInfoAntiXray antiXrayInfo)) {
            ((IChunkPacket) chunkPacket).setReady(true);
            return;
        }

        if (chunkPacketInfo.getChunk().getLevel() instanceof ServerLevel serverLevel) {
            if (!serverLevel.getServer().isSameThread()) {
                serverLevel.getServer().execute(() -> modifyBlocks(chunkPacket, chunkPacketInfo));
                return;
            }
        }

        LevelChunk chunk = chunkPacketInfo.getChunk();
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        ServerChunkCache chunkCache = ((ServerLevel) chunk.getLevel()).getChunkSource();
        antiXrayInfo.setNearbyChunks(
                getChunkAccess(chunkCache, x - 1, z),
                getChunkAccess(chunkCache, x + 1, z),
                getChunkAccess(chunkCache, x, z - 1),
                getChunkAccess(chunkCache, x, z + 1)
        );
        executor.execute((Runnable) chunkPacketInfo);
    }

    private ChunkAccess getChunkAccess(ServerChunkCache chunkCache, int chunkX, int chunkZ) {
        ChunkHolder chunkHolder = chunkCache.getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        if (chunkHolder != null) {
            ChunkAccess chunkAccess = chunkHolder.getLastAvailable();
            if (chunkAccess != null) {
                return chunkAccess;
            } else {
                AntiXray.LOGGER.warn("Chunk at [{}, {}] not available, falling back to getChunk", chunkX, chunkZ);
            }
        } else {
            AntiXray.LOGGER.warn("Chunk at [{}, {}] not visible, falling back to getChunk", chunkX, chunkZ);
        }
        // Slow fallback
        return chunkCache.getChunk(chunkX, chunkZ, ChunkStatus.LIGHT, true);
    }

    @Override
    public void onPlayerLeftClickBlock(ServerPlayerGameMode serverPlayerGameMode, BlockPos blockPos, ServerboundPlayerActionPacket.Action action, Direction direction, int worldHeight) {
        if (blockPos.getY() <= maxBlockHeightUpdatePosition) {
            updateNearbyBlocks(serverPlayerGameMode.level, blockPos);
        }
    }

    private void updateNearbyBlocks(Level level, BlockPos blockPos) {
        if (updateRadius >= 2) {
            BlockPos temp = blockPos.west();
            updateBlock(level, temp);
            updateBlock(level, temp.west());
            updateBlock(level, temp.below());
            updateBlock(level, temp.above());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.east());
            updateBlock(level, temp.east());
            updateBlock(level, temp.below());
            updateBlock(level, temp.above());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.below());
            updateBlock(level, temp.below());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.above());
            updateBlock(level, temp.above());
            updateBlock(level, temp.north());
            updateBlock(level, temp.south());
            updateBlock(level, temp = blockPos.north());
            updateBlock(level, temp.north());
            updateBlock(level, temp = blockPos.south());
            updateBlock(level, temp.south());
        } else if (updateRadius == 1) {
            updateBlock(level, blockPos.west());
            updateBlock(level, blockPos.east());
            updateBlock(level, blockPos.below());
            updateBlock(level, blockPos.above());
            updateBlock(level, blockPos.north());
            updateBlock(level, blockPos.south());
        } else {
            // Do nothing if updateRadius <= 0 (test mode)
        }
    }

    protected abstract int getPresetBlockStatesLength();

    protected abstract int[] getPresetBlockStateBits(Level level, int bottomBlockY);

    private void updateBlock(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        if (chunk != null && obfuscateGlobal.getOrDefault(chunk.getBlockState(pos), false)) {
            ((ServerLevel) level).getChunkSource().blockChanged(pos);
        }
    }

    // Actually these fields should be variables inside the obfuscate method but in sync mode or with SingleThreadExecutor in async mode it's okay (even without ThreadLocal)
    // If an ExecutorService with multiple threads is used, ThreadLocal must be used here
    private final ThreadLocal<int[]> presetBlockStateBits = ThreadLocal.withInitial(() -> new int[getPresetBlockStatesLength()]);
    private static final ThreadLocal<boolean[]> SOLID = ThreadLocal.withInitial(() -> new boolean[Block.BLOCK_STATE_REGISTRY.size()]);
    private static final ThreadLocal<boolean[]> OBFUSCATE = ThreadLocal.withInitial(() -> new boolean[Block.BLOCK_STATE_REGISTRY.size()]);
    // These boolean arrays represent chunk layers, true means don't obfuscate, false means obfuscate
    private static final ThreadLocal<boolean[][]> CURRENT = ThreadLocal.withInitial(() -> new boolean[16][16]);
    private static final ThreadLocal<boolean[][]> NEXT = ThreadLocal.withInitial(() -> new boolean[16][16]);
    private static final ThreadLocal<boolean[][]> NEXT_NEXT = ThreadLocal.withInitial(() -> new boolean[16][16]);

    public void obfuscate(ChunkPacketInfoAntiXray chunkPacketInfoAntiXray) {
        int[] presetBlockStateBits = this.presetBlockStateBits.get();
        boolean[] solid = SOLID.get();
        boolean[] obfuscate = OBFUSCATE.get();
        boolean[][] current = CURRENT.get();
        boolean[][] next = NEXT.get();
        boolean[][] nextNext = NEXT_NEXT.get();
        // bitStorageReader, bitStorageWriter and nearbyChunkSections could also be reused (with ThreadLocal if necessary) but it's not worth it
        BitStorageReader bitStorageReader = new BitStorageReader();
        BitStorageWriter bitStorageWriter = new BitStorageWriter();
        LevelChunkSection[] nearbyChunkSections = new LevelChunkSection[4];
        LevelChunk chunk = chunkPacketInfoAntiXray.getChunk();
        Level level = chunk.getLevel();
        int maxChunkSectionIndex = Math.min((maxBlockHeight >> 4) - chunk.getMinSection(), chunk.getSectionsCount() - 1);
        bitStorageReader.setBuffer(chunkPacketInfoAntiXray.getBuffer());
        bitStorageWriter.setBuffer(chunkPacketInfoAntiXray.getBuffer());
        int numberOfBlocks = presetBlockStateBits.length;

        for (int chunkSectionIndex = 0; chunkSectionIndex <= maxChunkSectionIndex; chunkSectionIndex++) {
            if (chunkPacketInfoAntiXray.isWritten(chunkSectionIndex) && chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex) != null) {
                if (chunkPacketInfoAntiXray.getPalette(chunkSectionIndex) instanceof GlobalPalette) {
                    presetBlockStateBits = getPresetBlockStateBits(level, chunkSectionIndex + chunk.getMinSection());
                } else {
                    BlockState[] presetBlockStates = chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex);

                    for (int i = 0; i < presetBlockStateBits.length; i++) {
                        // This is thread safe because we only request IDs that are guaranteed to be in the palette and are visible
                        // For more details see the comments in the readPalette method
                        presetBlockStateBits[i] = chunkPacketInfoAntiXray.getPalette(chunkSectionIndex).idFor(presetBlockStates[i]);
                    }
                }

                bitStorageWriter.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex));

                // Check if the chunk section below was not obfuscated
                if (chunkSectionIndex == 0 || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex - 1) || chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex - 1) == null) {
                    // If so, initialize some stuff
                    bitStorageReader.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex));
                    bitStorageReader.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex));
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex), solid, solidGlobal);
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex), obfuscate, obfuscateGlobal);
                    // Read the blocks of the upper layer of the chunk section below if it exists
                    LevelChunkSection belowChunkSection = null;
                    boolean skipFirstLayer = chunkSectionIndex == 0 || (belowChunkSection = chunk.getSections()[chunkSectionIndex - 1]) == EMPTY_SECTION;

                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            current[z][x] = true;
                            next[z][x] = skipFirstLayer || isTransparent(belowChunkSection, x, 15, z);
                        }
                    }

                    // Abuse the obfuscateLayer method to read the blocks of the first layer of the current chunk section
                    bitStorageWriter.setBits(0);
                    obfuscateLayer(-1, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBits, current, next, nextNext, emptyNearbyChunkSections, layerIntSupplier(numberOfBlocks));
                }

                bitStorageWriter.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex));
                nearbyChunkSections[0] = chunkPacketInfoAntiXray.getNearbyChunks()[0] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[0].getSections()[chunkSectionIndex];
                nearbyChunkSections[1] = chunkPacketInfoAntiXray.getNearbyChunks()[1] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[1].getSections()[chunkSectionIndex];
                nearbyChunkSections[2] = chunkPacketInfoAntiXray.getNearbyChunks()[2] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[2].getSections()[chunkSectionIndex];
                nearbyChunkSections[3] = chunkPacketInfoAntiXray.getNearbyChunks()[3] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[3].getSections()[chunkSectionIndex];

                // Obfuscate all layers of the current chunk section except the upper one
                for (int y = 0; y < 15; y++) {
                    boolean[][] temp = current;
                    current = next;
                    next = nextNext;
                    nextNext = temp;
                    obfuscateLayer(y, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBits, current, next, nextNext, nearbyChunkSections, layerIntSupplier(numberOfBlocks));
                }

                // Check if the chunk section above doesn't need obfuscation
                if (chunkSectionIndex == maxChunkSectionIndex || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex + 1) || chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex + 1) == null) {
                    // If so, obfuscate the upper layer of the current chunk section by reading blocks of the first layer from the chunk section above if it exists
                    LevelChunkSection aboveChunkSection;

                    if (chunkSectionIndex != chunk.getSectionsCount() - 1 && (aboveChunkSection = chunk.getSections()[chunkSectionIndex + 1]) != EMPTY_SECTION) {
                        boolean[][] temp = current;
                        current = next;
                        next = nextNext;
                        nextNext = temp;

                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (isTransparent(aboveChunkSection, x, 0, z)) {
                                    current[z][x] = true;
                                }
                            }
                        }

                        // There is nothing to read anymore
                        bitStorageReader.setBits(0);
                        solid[0] = true;
                        obfuscateLayer(15, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBits, current, next, nextNext, nearbyChunkSections, layerIntSupplier(numberOfBlocks));
                    }
                } else {
                    // If not, initialize the reader and other stuff for the chunk section above to obfuscate the upper layer of the current chunk section
                    bitStorageReader.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex + 1));
                    bitStorageReader.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex + 1));
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex + 1), solid, solidGlobal);
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex + 1), obfuscate, obfuscateGlobal);
                    boolean[][] temp = current;
                    current = next;
                    next = nextNext;
                    nextNext = temp;
                    obfuscateLayer(15, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBits, current, next, nextNext, nearbyChunkSections, layerIntSupplier(numberOfBlocks));
                }

                bitStorageWriter.flush();
            }
        }

        ((IChunkPacket) chunkPacketInfoAntiXray.getChunkPacket()).setReady(true);
    }

    private void obfuscateLayer(int y, BitStorageReader bitStorageReader, BitStorageWriter bitStorageWriter, boolean[] solid, boolean[] obfuscate, int[] presetBlockStateBits, boolean[][] current, boolean[][] next, boolean[][] nextNext, LevelChunkSection[] nearbyChunkSections, IntSupplier random) {
        int bits;
        for (int z = 0; z <= 15; z++) {
            for (int x = 0; x <= 15; x++) {
                bits = bitStorageReader.read();
                if (nextNext[z][x] = !solid[bits]) {
                    bitStorageWriter.skip();
                    if (x > 0) next[z][x - 1] = true;
                    if (x < 15) next[z][x + 1] = true;
                    if (z > 0) next[z - 1][x] = true;
                    if (z < 15) next[z + 1][x] = true;
                } else {
                    // Check neighbouring chunks
                    boolean negX = x == 0 && isTransparent(nearbyChunkSections[0], 15, y, z);
                    boolean posX = x == 15 && isTransparent(nearbyChunkSections[1], 0, y, z);
                    boolean negZ = z == 0 && isTransparent(nearbyChunkSections[2], x, y, 15);
                    boolean posZ = z == 15 && isTransparent(nearbyChunkSections[3], x, y, 0);
                    if (current[z][x] || negX || posX || negZ || posZ) {
                        bitStorageWriter.skip();
                    } else {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]);
                    }
                }
                if (!obfuscate[bits]) {
                    next[z][x] = true;
                }
            }
        }
    }

    private boolean isTransparent(LevelChunkSection chunkSection, int x, int y, int z) {
        if (chunkSection == EMPTY_SECTION) {
            return true;
        }

        try {
            return !solidGlobal.getOrDefault(chunkSection.getBlockState(x, y, z), false);
        } catch (MissingPaletteEntryException e) {
            // Race condition / visibility issue / no happens-before relationship
            // We don't care and treat the block as transparent
            // Internal implementation details of PalettedContainer, LinearPalette, HashMapPalette, CrudeIncrementalIntIdentityHashBiMap, ... guarantee us that no (other) exceptions will occur
            return true;
        }
    }

    private void readPalette(Palette<BlockState> palette, boolean[] temp, Object2BooleanOpenHashMap<BlockState> global) {
        try {
            for (int i = 0; i < palette.getSize(); i++) {
                temp[i] = global.getOrDefault(palette.valueFor(i), false);
            }
        } catch (MissingPaletteEntryException e) {
            // Race condition / visibility issue / no happens-before relationship
            // We don't care because we at least see the state as it was when the chunk packet was created
            // Internal implementation details of PalettedContainer, LinearPalette, HashMapPalette, CrudeIncrementalIntIdentityHashBiMap, ... guarantee us that no (other) exceptions will occur until we have all the data that we need here
            // Since all palettes have a fixed initial maximum size and there is no internal restructuring and no values are removed from palettes, we are also guaranteed to see the data
        }

    }

    public IntSupplier layerIntSupplier(int numberOfBlocks) {
        return numberOfBlocks == 1 ? (() -> 0) : new IntSupplier() {
            private int state;

            {
                while ((state = ThreadLocalRandom.current().nextInt()) == 0) ;
            }

            @Override
            public int getAsInt() {
                // https://en.wikipedia.org/wiki/Xorshift
                state ^= state << 13;
                state ^= state >>> 17;
                state ^= state << 5;
                // https://www.pcg-random.org/posts/bounded-rands.html
                return (int) ((Integer.toUnsignedLong(state) * numberOfBlocks) >>> 32);
            }
        };
    }

}