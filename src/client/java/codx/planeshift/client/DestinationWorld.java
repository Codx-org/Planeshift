package codx.planeshift.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.netty.buffer.Unpooled;

import codx.planeshift.Planeshift;
import codx.planeshift.client.mixin.LevelRendererAccessor;
import codx.planeshift.network.WatchNet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * A second client-side world: the far side of the plane the player is near, with its
 * own {@link ClientLevel}, {@link LevelRenderer} and {@link LevelExtractor}, filled
 * from the server's stream. Render thread only.
 *
 * <p>Built entirely from what the server said about that dimension, never from the
 * level the player is standing in. At a crossing this level <em>becomes</em> the
 * player's world, so anything guessed here would stay wrong afterwards.
 *
 * <p>It has a renderer because it must: {@code ClientLevel} needs a
 * {@code LevelExtractor}, which needs a {@code LevelRenderer}. That the renderer then
 * meshes chunks as they arrive is the point — a crossing adopts an already-built view
 * rather than starting one.
 */
public final class DestinationWorld {
	private int handle;
	private final ResourceKey<Level> dimension;
	private final ClientLevel level;

	/** Null for a level we kept rather than built: the game owns those. */
	private final @Nullable LevelRenderer renderer;
	private final @Nullable LevelExtractor extractor;

	private int applied;
	private int failed;

	/** Whether the game built this renderer, or we did. See {@link #adopted()}. */
	private boolean adopted;

	/** The size its renderer was last matched to. See {@link #resize}. */
	private int width;
	private int height;

	/** The chunk its streaming is built around, so two openings can tell if they share a view. */
	private int centreX;
	private int centreZ;


	/**
	 * Its own framegraph resources.
	 *
	 * <p>{@code LevelRenderer.render} builds a framegraph and takes its intermediate
	 * targets from the allocator it is handed. A {@code CrossFrameResourcePool} keeps
	 * those between frames on the understanding that there is one framegraph per frame;
	 * handing the game's own pool to a second render in the same frame is the one case it
	 * is not built for. Terrain goes through those intermediates, which is why it is the
	 * half that goes missing.
	 */
	private final com.mojang.blaze3d.resource.CrossFrameResourcePool pool =
			new com.mojang.blaze3d.resource.CrossFrameResourcePool(3);

	/**
	 * Its own camera and fog, because both are placed per level: the frame's belong to
	 * the world the player is standing in and are read again after this one is drawn.
	 */
	private final net.minecraft.client.Camera camera = new net.minecraft.client.Camera();
	private final net.minecraft.client.renderer.fog.FogRenderer fog =
			new net.minecraft.client.renderer.fog.FogRenderer();
	private final @Nullable LevelRenderState state;

	private DestinationWorld(int handle, ResourceKey<Level> dimension, ClientLevel level,
			@Nullable LevelRenderer renderer, @Nullable LevelExtractor extractor,
			@Nullable LevelRenderState state) {
		this.handle = handle;
		this.dimension = dimension;
		this.level = level;
		this.renderer = renderer;
		this.extractor = extractor;
		this.state = state;
	}

	/**
	 * Keeps a level the player is leaving, so walking back does not re-download a world
	 * that was under their feet a moment ago.
	 *
	 * <p>It keeps the renderer and extractor that were drawing it, with every section
	 * they had compiled. Walking back adopts all three again, so the world the player
	 * returns to is the one they left, still built.
	 */
	public static DestinationWorld kept(ClientLevel level, LevelRenderer renderer, LevelExtractor extractor,
			LevelRenderState state) {
		DestinationWorld world = new DestinationWorld(0, level.dimension(), level, renderer, extractor, state);
		world.adopted = true;
		return world;
	}

	public static DestinationWorld create(Minecraft client, WatchNet.Opened opened) {
		// Its own render state: the constructor takes the game renderer's, which the
		// player's own extractor overwrites every frame.
		LevelRenderState state = new LevelRenderState();
		LevelRenderer renderer = new LevelRenderer(client.getEntityRenderDispatcher(),
				client.getBlockEntityRenderDispatcher(), client.getModelManager(), client.getTextureManager(),
				client.getAtlasManager(), client.getShaderManager(), client.gameRenderer,
				client.getWindow().getWidth(), client.getWindow().getHeight());
		((LevelRendererAccessor) renderer).planeshift$setLevelRenderState(state);

		LevelExtractor extractor = new LevelExtractor(client, state, renderer);
		extractor.onResourceManagerReload(client.getResourceManager());

		WatchNet.Settings settings = opened.settings();
		ClientLevel.ClientLevelData here = client.level.getLevelData();
		// Difficulty and hardcore are server-wide, so the level the player is in has the
		// real values already. Everything dimension-specific comes from the server.
		ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(
				here.getDifficulty(), here.isHardcore(), settings.flat());
		data.setDifficultyLocked(here.isDifficultyLocked());
		data.setGameTime(here.getGameTime());

		ClientLevel level = new ClientLevel(client.getConnection(), data, opened.dimension(),
				opened.dimensionType(), opened.radius(), opened.radius(), extractor, settings.debug(),
				settings.seedHash(), settings.seaLevel());

		// Before any column arrives: setLevel builds the section tracker that chunk and
		// light updates mark dirty.
		extractor.setLevel(level);
		DestinationWorld world = new DestinationWorld(opened.handle(), opened.dimension(), level,
				renderer, extractor, state);
		world.recentre(opened.centerX(), opened.centerZ());
		return world;
	}

	/**
	 * Matches the renderer to the size of the frame it is drawn into, when that changes.
	 *
	 * <p>Only when it changes. {@code LevelRenderer.resize} has no guard of its own and
	 * invalidates the section occlusion graph every time it is called; asked every frame,
	 * the graph is thrown away before it has finished working out what is visible, and
	 * the world never gets drawn at all.
	 */
	public void resize(int width, int height) {
		if (renderer == null || (this.width == width && this.height == height)) {
			return;
		}

		this.width = width;
		this.height = height;
		renderer.resize(width, height);
	}

	/**
	 * Empties the level of entities, keeping its blocks.
	 *
	 * <p>A kept level holds every entity that was in it when the player walked out,
	 * including their own. Coming back, the server sends the lot again — and the client
	 * refuses each one as a duplicate UUID, the new player among them. A player entity
	 * that never joins its level cannot move and is not something the camera can follow,
	 * which is the void you end up standing in.
	 *
	 * <p>Blocks are what made keeping the level worth it; the entities were always
	 * going to be re-sent.
	 */
	public void clearEntities() {
		List<Entity> present = new ArrayList<>();
		level.entitiesForRendering().forEach(present::add);

		for (Entity entity : present) {
			level.removeEntity(entity.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
		}

		if (!present.isEmpty()) {
			Planeshift.LOGGER.info("Cleared {} stale entities from the kept {}",
					present.size(), dimension.identifier());
		}
	}

	/**
	 * Applies one streamed column, the way {@code ClientPacketListener} applies a chunk
	 * with its light. The light engine is run here because nothing else runs it for
	 * this level.
	 */
	public void apply(byte[] body) {
		ClientboundLevelChunkWithLightPacket packet;
		LevelChunk chunk;

		try {
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
					Unpooled.wrappedBuffer(body), level.registryAccess());
			packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
			chunk = level.getChunkSource().replaceWithPacketData(packet.x(), packet.z(), packet.chunkData());
		} catch (RuntimeException e) {
			// Caught per column: one bad column leaves one hole rather than stranding
			// everything behind it.
			failed++;
			Planeshift.LOGGER.warn("Destination {}: could not apply a column", dimension.identifier(), e);
			return;
		}

		if (chunk == null) {
			// Outside the chunk cache's range; cannot happen while the centre is the
			// server's, but a hole beats an exception.
			failed++;
			return;
		}

		int x = packet.x();
		int z = packet.z();
		LevelLightEngine light = level.getLightEngine();
		ClientboundLightUpdatePacketData lightData = packet.lightData();
		queueLight(light, LightLayer.SKY, x, z, lightData.skyYMask(), lightData.emptySkyYMask(),
				lightData.skyUpdates().iterator());
		queueLight(light, LightLayer.BLOCK, x, z, lightData.blockYMask(), lightData.emptyBlockYMask(),
				lightData.blockUpdates().iterator());
		light.setLightEnabled(new ChunkPos(x, z), true);

		LevelChunkSection[] sections = chunk.getSections();

		for (int i = 0; i < sections.length; i++) {
			light.updateSectionStatus(SectionPos.of(chunk.getPos(), level.getSectionYFromSectionIndex(i)),
					sections[i].hasOnlyAir());
		}

		level.setSectionRangeDirty(x - 1, level.getMinSectionY(), z - 1, x + 1, level.getMaxSectionY(), z + 1);
		applied++;

		if (applied == 1 || applied % 96 == 0) {
			codx.planeshift.client.DoorwayCrossing.note(
					dimension.identifier() + ": " + applied + " columns applied");
		}
	}

	/**
	 * Applies blocks that changed on the far side since it was streamed.
	 *
	 * <p>The same call {@code ClientPacketListener} makes for a block update packet, and
	 * this level routes its own re-render through its own extractor, so the section is
	 * rebuilt by the destination's renderer rather than the player's.
	 *
	 * <p>The light is asked for rather than sent. Nothing relays light updates for a watch,
	 * and a block appearing or going changes what is lit around it; the client's own engine
	 * works that out from the block, and {@link #tick()} runs it.
	 */
	public void applyBlocks(java.util.List<Long> positions, java.util.List<Integer> states) {
		int count = Math.min(positions.size(), states.size());

		for (int i = 0; i < count; i++) {
			BlockPos pos = BlockPos.of(positions.get(i));
			level.setServerVerifiedBlockState(pos, Block.stateById(states.get(i)), Block.UPDATE_ALL);
			level.getLightEngine().checkBlock(pos);
		}
	}

	private static void queueLight(LevelLightEngine light, LightLayer layer, int x, int z,
			BitSet mask, BitSet empty, Iterator<byte[]> data) {
		for (int i = 0; i < light.getLightSectionCount(); i++) {
			boolean present = mask.get(i);

			if (present || empty.get(i)) {
				SectionPos pos = SectionPos.of(x, light.getMinLightSection() + i, z);
				light.queueSectionData(layer, pos, present ? new DataLayer(data.next().clone()) : new DataLayer());
			}
		}
	}

	/**
	 * Lets go of the renderer.
	 *
	 * <p>Deliberately not {@code extractor.setLevel(null)}: that resets the camera of
	 * the entity render dispatcher, which this shares with the player's own renderer.
	 */
	public void dispose() {
		if (renderer != null) {
			renderer.close();
		}

		fog.close();
		pool.close();
	}

	/** The framegraph resources this world's renderer draws through. */
	public com.mojang.blaze3d.resource.CrossFrameResourcePool pool() {
		return pool;
	}

	/** The camera this world is drawn from, placed by hand where the player would be. */
	public net.minecraft.client.Camera camera() {
		return camera;
	}

	public net.minecraft.client.renderer.fog.FogRenderer fog() {
		return fog;
	}

	/** Its own render state, or null for a level kept from the game's own. */
	public @Nullable LevelRenderState state() {
		return state;
	}

	public int handle() {
		return handle;
	}

	/**
	 * Points this world at a different watch. Two planes leading to the same dimension
	 * are the same far side, so moving the watch between them is no reason to throw
	 * away everything streamed for it.
	 */
	public void rebind(int handle) {
		this.handle = handle;
	}

	/**
	 * Runs the client half of the far side: applying the light the server sent, and
	 * moving its entities.
	 *
	 * <p>Without the entity half they stand exactly where their spawn packet put them,
	 * which is what makes a far side full of entities look like a photograph of one.
	 */
	public void tick() {
		// Time only ever reaches the level the player is standing in: the server's clock
		// packet is applied to that one and nothing forwards it. A far side is therefore
		// stopped at whatever moment it was built in — its sun where it was, its sky the
		// colour it was — and drifts further from the world it belongs to the longer it is
		// held. Kept in step here, since a doorway onto midday should not show dusk.
		ClientLevel here = Minecraft.getInstance().level;

		if (here != null && here != level) {
			level.setTimeFromServer(here.getGameTime());
		}

		try {
			// The server's light arrives queued, not applied: queueSectionData hands it to
			// the light engine and nothing more happens until the engine is run. A level
			// the player is standing in gets that from its chunk source every tick; one
			// held for a watch is never ticked at all, so without this every light read
			// comes back zero and the whole far side is drawn pitch black — terrain as a
			// black surface, entities as the silhouettes that are still just visible.
			LevelLightEngine light = level.getLightEngine();

			if (light.hasLightWork()) {
				light.runLightUpdates();
			}

			level.tickEntities();
		} catch (RuntimeException e) {
			// One broken entity would throw every tick; the view stays, and stays still.
			Planeshift.LOGGER.error("Destination {}: ticking it failed",
					dimension.identifier(), e);
		}
	}

	/** Moves the chunk source's centre, so streamed columns land within its reach. */
	public void recentre(int chunkX, int chunkZ) {
		centreX = chunkX;
		centreZ = chunkZ;
		level.getChunkSource().updateViewCenter(chunkX, chunkZ);
	}

	/** How many chunks this far side's centre is from the given one, at its furthest. */
	public int chunksFrom(int chunkX, int chunkZ) {
		return Math.max(Math.abs(centreX - chunkX), Math.abs(centreZ - chunkZ));
	}

	public ResourceKey<Level> dimension() {
		return dimension;
	}

	public ClientLevel level() {
		return level;
	}

	public @Nullable LevelRenderer renderer() {
		return renderer;
	}

	public @Nullable LevelExtractor extractor() {
		return extractor;
	}

	/**
	 * Whether this is the game's own renderer, kept when the player walked out of the
	 * level, rather than one built here. The game's has drawn that level for real; ours
	 * has never drawn anything, and the difference is worth being able to see.
	 */
	public boolean adopted() {
		return adopted;
	}

	public int applied() {
		return applied;
	}

	public int failed() {
		return failed;
	}
}
