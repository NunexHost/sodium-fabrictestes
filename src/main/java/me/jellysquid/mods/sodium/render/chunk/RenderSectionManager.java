package me.jellysquid.mods.sodium.render.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import me.jellysquid.mods.sodium.SodiumClientMod;
import me.jellysquid.mods.sodium.opengl.device.RenderDevice;
import me.jellysquid.mods.sodium.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.render.chunk.compile.tasks.TerrainBuildResult;
import me.jellysquid.mods.sodium.render.chunk.compile.ChunkBuilder;
import me.jellysquid.mods.sodium.render.chunk.state.ChunkRenderData;
import me.jellysquid.mods.sodium.render.chunk.draw.ChunkCameraContext;
import me.jellysquid.mods.sodium.render.chunk.draw.ChunkRenderList;
import me.jellysquid.mods.sodium.render.chunk.draw.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.render.chunk.draw.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.render.chunk.state.ChunkGraphIterationQueue;
import me.jellysquid.mods.sodium.render.terrain.format.TerrainVertexFormats;
import me.jellysquid.mods.sodium.render.chunk.state.ChunkGraphState;
import me.jellysquid.mods.sodium.render.chunk.passes.ChunkRenderPass;
import me.jellysquid.mods.sodium.render.chunk.passes.ChunkRenderPassManager;
import me.jellysquid.mods.sodium.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.render.chunk.compile.tasks.AbstractBuilderTask;
import me.jellysquid.mods.sodium.render.chunk.compile.tasks.EmptyTerrainBuildTask;
import me.jellysquid.mods.sodium.render.chunk.compile.tasks.TerrainBuildTask;
import me.jellysquid.mods.sodium.world.ChunkStatus;
import me.jellysquid.mods.sodium.world.ChunkTracker;
import me.jellysquid.mods.sodium.util.MathUtil;
import me.jellysquid.mods.sodium.interop.vanilla.math.frustum.Frustum;
import me.jellysquid.mods.sodium.world.slice.WorldSliceData;
import me.jellysquid.mods.sodium.world.slice.cloned.ClonedChunkSectionCache;
import me.jellysquid.mods.sodium.util.DirectionUtil;
import me.jellysquid.mods.sodium.util.tasks.WorkStealingFutureDrain;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RenderSectionManager {
    /**
     * The maximum distance a chunk can be from the player's camera in order to be eligible for blocking updates.
     */
    private static final double NEARBY_CHUNK_DISTANCE = Math.pow(32, 2.0);

    /**
     * The minimum distance the culling plane can be from the player's camera. This helps to prevent mathematical
     * errors that occur when the fog distance is less than 8 blocks in width, such as when using a blindness potion.
     */
    private static final float FOG_PLANE_MIN_DISTANCE = (float) Math.pow(8.0f, 2.0);

    /**
     * The distance past the fog's far plane at which to begin culling. Distance calculations use the center of each
     * chunk from the camera's position, and as such, special care is needed to ensure that the culling plane is pushed
     * back far enough. I'm sure there's a mathematical formula that should be used here in place of the constant,
     * but this value works fine in testing.
     */
    private static final float FOG_PLANE_OFFSET = 12.0f;

    private final ChunkBuilder builder;

    private final RenderRegionManager regions;
    private final ClonedChunkSectionCache sectionCache;

    private final Long2ReferenceMap<RenderSection> sections = new Long2ReferenceOpenHashMap<>();

    private final Map<ChunkUpdateType, PriorityQueue<RenderSection>> rebuildQueues = new EnumMap<>(ChunkUpdateType.class);

    private final ChunkRenderList chunkRenderList = new ChunkRenderList();
    private final ChunkGraphIterationQueue iterationQueue = new ChunkGraphIterationQueue();

    private final ObjectList<RenderSection> tickableChunks = new ObjectArrayList<>();
    private final ObjectList<BlockEntity> visibleBlockEntities = new ObjectArrayList<>();

    private final DefaultChunkRenderer chunkRenderer;

    private final SodiumWorldRenderer worldRenderer;
    private final ClientWorld world;

    private final int renderDistance;

    private float cameraX, cameraY, cameraZ;
    private int centerChunkX, centerChunkZ;

    private boolean needsUpdate;

    private boolean useFogCulling;
    private boolean useOcclusionCulling;

    private double fogRenderCutoff;

    private Frustum frustum;

    private int currentFrame = 0;
    private boolean alwaysDeferChunkUpdates;

    private final ChunkTracker tracker;
    private final RenderDevice device;

    public RenderSectionManager(RenderDevice device, SodiumWorldRenderer worldRenderer, ChunkRenderPassManager renderPassManager, ClientWorld world, int renderDistance) {
        this.device = device;
        this.chunkRenderer = new DefaultChunkRenderer(device, TerrainVertexFormats.STANDARD);

        this.worldRenderer = worldRenderer;
        this.world = world;

        this.builder = new ChunkBuilder(TerrainVertexFormats.STANDARD);
        this.builder.init(world, renderPassManager);

        this.needsUpdate = true;
        this.renderDistance = renderDistance;

        this.regions = new RenderRegionManager(device);
        this.sectionCache = new ClonedChunkSectionCache(this.world);

        for (ChunkUpdateType type : ChunkUpdateType.values()) {
            this.rebuildQueues.put(type, new ObjectArrayFIFOQueue<>());
        }

        this.tracker = this.worldRenderer.getChunkTracker();
    }

    public void reloadChunks(ChunkTracker tracker) {
        tracker.getChunks(ChunkStatus.FLAG_HAS_BLOCK_DATA)
                .forEach(pos -> this.onChunkAdded(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos)));
    }

    public void update(Camera camera, Frustum frustum, int frame, boolean spectator) {
        this.resetLists();

        this.regions.updateVisibility(frustum);

        this.setup(camera);
        this.iterateChunks(camera, frustum, frame, spectator);

        this.needsUpdate = false;
    }

    private void setup(Camera camera) {
        Vec3d cameraPos = camera.getPos();

        this.cameraX = (float) cameraPos.x;
        this.cameraY = (float) cameraPos.y;
        this.cameraZ = (float) cameraPos.z;

        var options = SodiumClientMod.options();

        this.useFogCulling = options.performance.useFogOcclusion;
        this.alwaysDeferChunkUpdates = options.performance.alwaysDeferChunkUpdates;

        if (this.useFogCulling) {
            float dist = RenderSystem.getShaderFogEnd() + FOG_PLANE_OFFSET;

            if (dist == 0.0f) {
                this.fogRenderCutoff = Double.POSITIVE_INFINITY;
            } else {
                this.fogRenderCutoff = Math.max(FOG_PLANE_MIN_DISTANCE, dist * dist);
            }
        }
    }

    private void iterateChunks(Camera camera, Frustum frustum, int frame, boolean spectator) {
        this.initSearch(camera, frustum, frame, spectator);

        ChunkGraphIterationQueue queue = this.iterationQueue;

        for (int i = 0; i < queue.size(); i++) {
            RenderSection section = queue.getRender(i);
            Direction flow = queue.getDirection(i);

            this.schedulePendingUpdates(section);

            for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
                if (this.isCulled(section.getGraphInfo(), flow, dir)) {
                    continue;
                }

                RenderSection adj = section.getAdjacent(dir);

                if (adj != null && this.isWithinRenderDistance(adj)) {
                    this.bfsEnqueue(section, adj, DirectionUtil.getOpposite(dir));
                }
            }
        }
    }

    private void schedulePendingUpdates(RenderSection section) {
        if (section.getPendingUpdate() == null || !this.tracker.hasMergedFlags(section.getChunkX(), section.getChunkZ(), ChunkStatus.FLAG_ALL)) {
            return;
        }

        PriorityQueue<RenderSection> queue = this.rebuildQueues.get(section.getPendingUpdate());

        if (queue.size() >= 32) {
            return;
        }

        queue.enqueue(section);
    }

    private void addChunkToVisible(RenderSection render) {
        this.chunkRenderList.add(render);

        if (render.isTickable()) {
            this.tickableChunks.add(render);
        }
    }

    private void addEntitiesToRenderLists(RenderSection render) {
        Collection<BlockEntity> blockEntities = render.getData().getBlockEntities();

        if (!blockEntities.isEmpty()) {
            this.visibleBlockEntities.addAll(blockEntities);
        }
    }

    private void resetLists() {
        for (PriorityQueue<RenderSection> queue : this.rebuildQueues.values()) {
            queue.clear();
        }

        this.visibleBlockEntities.clear();
        this.chunkRenderList.clear();
        this.tickableChunks.clear();
    }

    public Collection<BlockEntity> getVisibleBlockEntities() {
        return this.visibleBlockEntities;
    }

    public void onChunkAdded(int x, int z) {
        for (int y = this.world.getBottomSectionCoord(); y < this.world.getTopSectionCoord(); y++) {
            this.needsUpdate |= this.loadSection(x, y, z);
        }
    }

    public void onChunkRemoved(int x, int z) {
        for (int y = this.world.getBottomSectionCoord(); y < this.world.getTopSectionCoord(); y++) {
            this.needsUpdate |= this.unloadSection(x, y, z);
        }
    }

    private boolean loadSection(int x, int y, int z) {
        RenderRegion region = this.regions.createRegionForChunk(x, y, z);

        RenderSection render = new RenderSection(this.worldRenderer, x, y, z, region);
        region.addChunk(render);

        this.sections.put(ChunkSectionPos.asLong(x, y, z), render);

        Chunk chunk = this.world.getChunk(x, z);
        ChunkSection section = chunk.getSectionArray()[this.world.sectionCoordToIndex(y)];

        if (section.isEmpty()) {
            render.setData(ChunkRenderData.EMPTY);
        } else {
            render.markForUpdate(ChunkUpdateType.INITIAL_BUILD);
        }

        this.connectNeighborNodes(render);

        return true;
    }

    private boolean unloadSection(int x, int y, int z) {
        RenderSection chunk = this.sections.remove(ChunkSectionPos.asLong(x, y, z));

        if (chunk == null) {
            throw new IllegalStateException("Chunk is not loaded: " + ChunkSectionPos.from(x, y, z));
        }

        chunk.delete();

        this.disconnectNeighborNodes(chunk);

        RenderRegion region = chunk.getRegion();
        region.removeChunk(chunk);

        return true;
    }

    public void renderLayer(ChunkRenderMatrices matrices, ChunkRenderPass pass, double x, double y, double z) {
        this.chunkRenderer.render(matrices, this.device, this.chunkRenderList, pass, new ChunkCameraContext(x, y, z));
    }

    public void tickVisibleRenders() {
        for (RenderSection render : this.tickableChunks) {
            render.tick();
        }
    }

    public boolean isSectionVisible(int x, int y, int z) {
        RenderSection render = this.getRenderSection(x, y, z);

        if (render == null) {
            return false;
        }

        return render.getGraphInfo()
                .getLastVisibleFrame() == this.currentFrame;
    }

    public void updateChunks() {
        var blockingFutures = this.submitRebuildTasks(ChunkUpdateType.IMPORTANT_REBUILD);

        this.submitRebuildTasks(ChunkUpdateType.INITIAL_BUILD);
        this.submitRebuildTasks(ChunkUpdateType.REBUILD);

        // Try to complete some other work on the main thread while we wait for rebuilds to complete
        this.needsUpdate |= this.performPendingUploads();

        if (!blockingFutures.isEmpty()) {
            this.needsUpdate = true;
            this.regions.upload(new WorkStealingFutureDrain<>(blockingFutures, this.builder::stealTask));
        }

        this.regions.cleanup();
    }

    private LinkedList<CompletableFuture<TerrainBuildResult>> submitRebuildTasks(ChunkUpdateType filterType) {
        int budget = filterType.isImportant() ? Integer.MAX_VALUE : this.builder.getSchedulingBudget();

        LinkedList<CompletableFuture<TerrainBuildResult>> immediateFutures = new LinkedList<>();
        PriorityQueue<RenderSection> queue = this.rebuildQueues.get(filterType);

        while (budget > 0 && !queue.isEmpty()) {
            RenderSection section = queue.dequeue();

            if (section.isDisposed()) {
                continue;
            }

            // Sections can move between update queues, but they won't be removed from the queue they were
            // previously in to save CPU cycles. We just filter any changed entries here instead.
            if (section.getPendingUpdate() != filterType) {
                continue;
            }

            AbstractBuilderTask task = this.createTerrainBuildTask(section);
            CompletableFuture<?> future;

            if (filterType.isImportant()) {
                CompletableFuture<TerrainBuildResult> immediateFuture = this.builder.schedule(task);
                immediateFutures.add(immediateFuture);

                future = immediateFuture;
            } else {
                future = this.builder.scheduleDeferred(task);
            }

            section.onBuildSubmitted(future);

            budget--;
        }

        return immediateFutures;
    }

    private boolean performPendingUploads() {
        Iterator<TerrainBuildResult> it = this.builder.createDeferredBuildResultDrain();

        if (!it.hasNext()) {
            return false;
        }

        this.regions.upload(it);

        return true;
    }

    public AbstractBuilderTask createTerrainBuildTask(RenderSection render) {
        WorldSliceData data = WorldSliceData.prepare(this.world, render.getChunkPos(), this.sectionCache);
        int frame = this.currentFrame;

        if (data == null) {
            return new EmptyTerrainBuildTask(render, frame);
        }

        return new TerrainBuildTask(render, data, frame);
    }

    public void markGraphDirty() {
        this.needsUpdate = true;
    }

    public boolean isGraphDirty() {
        return this.needsUpdate;
    }

    public ChunkBuilder getBuilder() {
        return this.builder;
    }

    public void destroy() {
        this.resetLists();

        this.regions.delete();
        this.chunkRenderer.delete();
        this.builder.stopWorkers();
    }

    public int getTotalSections() {
        int sum = 0;

        for (RenderRegion region : this.regions.getLoadedRegions()) {
            sum += region.getChunkCount();
        }

        return sum;
    }

    public int getVisibleChunkCount() {
        return this.chunkRenderList.getCount();
    }

    public void scheduleRebuild(int x, int y, int z, boolean important) {
        this.sectionCache.invalidate(x, y, z);

        RenderSection section = this.sections.get(ChunkSectionPos.asLong(x, y, z));

        if (section != null && section.isBuilt()) {
            if (!this.alwaysDeferChunkUpdates && (important || this.isChunkPrioritized(section))) {
                section.markForUpdate(ChunkUpdateType.IMPORTANT_REBUILD);
            } else {
                section.markForUpdate(ChunkUpdateType.REBUILD);
            }
        }

        this.needsUpdate = true;
    }

    public boolean isChunkPrioritized(RenderSection render) {
        return render.getSquaredDistance(this.cameraX, this.cameraY, this.cameraZ) <= NEARBY_CHUNK_DISTANCE;
    }

    public void onChunkRenderUpdates(int x, int y, int z, ChunkRenderData data) {
        RenderSection node = this.getRenderSection(x, y, z);

        if (node != null) {
            node.setOcclusionData(data.getOcclusionData());
        }
    }

    private boolean isWithinRenderDistance(RenderSection adj) {
        int x = Math.abs(adj.getChunkX() - this.centerChunkX);
        int z = Math.abs(adj.getChunkZ() - this.centerChunkZ);

        return x <= this.renderDistance && z <= this.renderDistance;
    }

    private boolean isCulled(ChunkGraphState node, Direction from, Direction to) {
        if (node.canCull(to)) {
            return true;
        }

        return this.useOcclusionCulling && from != null && !node.isVisibleThrough(from, to);
    }

    private void initSearch(Camera camera, Frustum frustum, int frame, boolean spectator) {
        this.currentFrame = frame;
        this.frustum = frustum;
        this.useOcclusionCulling = MinecraftClient.getInstance().chunkCullingEnabled;

        this.iterationQueue.clear();

        BlockPos origin = camera.getBlockPos();

        int chunkX = origin.getX() >> 4;
        int chunkY = origin.getY() >> 4;
        int chunkZ = origin.getZ() >> 4;

        this.centerChunkX = chunkX;
        this.centerChunkZ = chunkZ;

        RenderSection rootRender = this.getRenderSection(chunkX, chunkY, chunkZ);

        if (rootRender != null) {
            ChunkGraphState rootInfo = rootRender.getGraphInfo();
            rootInfo.resetCullingState();
            rootInfo.setLastVisibleFrame(frame);

            if (spectator && this.world.getBlockState(origin).isOpaqueFullCube(this.world, origin)) {
                this.useOcclusionCulling = false;
            }

            this.addVisible(rootRender, null);
        } else {
            chunkY = MathHelper.clamp(origin.getY() >> 4, this.world.getBottomSectionCoord(), this.world.getTopSectionCoord() - 1);

            List<RenderSection> sorted = new ArrayList<>();

            for (int x2 = -this.renderDistance; x2 <= this.renderDistance; ++x2) {
                for (int z2 = -this.renderDistance; z2 <= this.renderDistance; ++z2) {
                    RenderSection render = this.getRenderSection(chunkX + x2, chunkY, chunkZ + z2);

                    if (render == null) {
                        continue;
                    }

                    ChunkGraphState info = render.getGraphInfo();

                    if (info.isCulledByFrustum(frustum)) {
                        continue;
                    }

                    info.resetCullingState();
                    info.setLastVisibleFrame(frame);

                    sorted.add(render);
                }
            }

            sorted.sort(Comparator.comparingDouble(node -> node.getSquaredDistance(origin)));

            for (RenderSection render : sorted) {
                this.addVisible(render, null);
            }
        }
    }


    private void bfsEnqueue(RenderSection parent, RenderSection render, Direction flow) {
        ChunkGraphState info = render.getGraphInfo();

        if (info.getLastVisibleFrame() == this.currentFrame) {
            return;
        }

        Frustum.Visibility parentVisibility = parent.getRegion().getVisibility();

        if (parentVisibility == Frustum.Visibility.OUTSIDE) {
            return;
        } else if (parentVisibility == Frustum.Visibility.INTERSECT && info.isCulledByFrustum(this.frustum)) {
            return;
        }

        info.setLastVisibleFrame(this.currentFrame);
        info.setCullingState(parent.getGraphInfo().getCullingState(), flow);

        this.addVisible(render, flow);
    }

    private void addVisible(RenderSection render, Direction flow) {
        this.iterationQueue.add(render, flow);

        if (this.useFogCulling && render.getSquaredDistanceXZ(this.cameraX, this.cameraZ) >= this.fogRenderCutoff) {
            return;
        }

        if (!render.isEmpty()) {
            this.addChunkToVisible(render);
            this.addEntitiesToRenderLists(render);
        }
    }

    private void connectNeighborNodes(RenderSection render) {
        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            RenderSection adj = this.getRenderSection(render.getChunkX() + dir.getOffsetX(),
                    render.getChunkY() + dir.getOffsetY(),
                    render.getChunkZ() + dir.getOffsetZ());

            if (adj != null) {
                adj.setAdjacentNode(DirectionUtil.getOpposite(dir), render);
                render.setAdjacentNode(dir, adj);
            }
        }
    }

    private void disconnectNeighborNodes(RenderSection render) {
        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            RenderSection adj = render.getAdjacent(dir);

            if (adj != null) {
                adj.setAdjacentNode(DirectionUtil.getOpposite(dir), null);
                render.setAdjacentNode(dir, null);
            }
        }
    }

    private RenderSection getRenderSection(int x, int y, int z) {
        return this.sections.get(ChunkSectionPos.asLong(x, y, z));
    }

    public Collection<String> getDebugStrings() {
        List<String> list = new ArrayList<>();

        Iterator<RenderRegion.RenderRegionArenas> it = this.regions.getLoadedRegions()
                .stream()
                .map(RenderRegion::getArenas)
                .filter(Objects::nonNull)
                .iterator();

        int count = 0;

        long deviceUsed = 0;
        long deviceAllocated = 0;

        while (it.hasNext()) {
            RenderRegion.RenderRegionArenas arena = it.next();
            deviceUsed += arena.getDeviceUsedMemory();
            deviceAllocated += arena.getDeviceAllocatedMemory();

            count++;
        }

        list.add(String.format("Device buffer objects: %d", count));
        list.add(String.format("Device memory: %d/%d MiB", MathUtil.toMib(deviceUsed), MathUtil.toMib(deviceAllocated)));
        list.add(String.format("Staging buffer: %s", this.regions.getStreamingBuffer().toString()));
        return list;
    }
}