package dev.westercraft.creatures;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

// Tracks blocks the dragon's fire has melted down (destroyed) and blocks it has set on fire, so
// both can be manually cleaned up via /dragon revert (DragonCommands#runRevert) on demand. No
// automatic timer any more - per explicit request, melted terrain now stays melted until an op
// runs the command, rather than quietly regrowing on its own after a few hours. Persisted to the
// world save (PersistentState, not an in-memory list) so pending entries survive a server
// restart instead of just vanishing on reload.
public class MeltedBlocksState extends PersistentState {
	private static final String ID = "creaturesofwesteros_melted_blocks";
	// Found in a self-directed review, not reported: with the auto-restore timer removed (revert
	// is manual-only now via /dragon revert), these two lists have no natural drain at all any
	// more - every real explosion/igniteArea call (see ViserionFireballEntity) adds several
	// entries at once, so a long session without ever running /dragon revert would grow these
	// unbounded, bloating the world save and iteration cost of every future revert. Capped instead
	// with FIFO eviction (oldest entries silently dropped, no revert to correspond to them any
	// more) - a soft bound, not a gameplay-visible limit anyone would realistically hit through
	// normal play.
	private static final int MAX_TRACKED_ENTRIES = 5000;

	private final List<TrackedBlock> meltedBlocks = new ArrayList<>();
	private final List<TrackedBlock> ignitedFire = new ArrayList<>();

	public static MeltedBlocksState get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(MeltedBlocksState::fromNbt, MeltedBlocksState::new, ID);
	}

	public void addMeltedBlock(BlockPos pos, BlockState originalState) {
		if (this.meltedBlocks.size() >= MAX_TRACKED_ENTRIES) {
			this.meltedBlocks.remove(0);
		}
		this.meltedBlocks.add(new TrackedBlock(pos.toImmutable(), originalState));
		this.markDirty();
	}

	// Recorded wherever the dragon actually places a fire block (ViserionEntity#tryIgnite for the
	// AI's own flame, ViserionFireballEntity#igniteArea / ViserionFireZoneEntity#igniteArea for
	// the rider's breath) - not every block fire later spreads to on its own, just the dragon's
	// own direct ignitions. originalState is whatever was actually standing at that spot right
	// before the fire got placed there (grass, snow layer, tall grass, etc, or plain air) - real
	// bug, not guessed: this used to only remember the position, so /dragon revert had nothing to
	// put back except air, silently deleting any vegetation/snow the fire had overwritten instead
	// of actually restoring "the block existing before."
	public void addIgnitedFire(BlockPos pos, BlockState originalState) {
		if (this.ignitedFire.size() >= MAX_TRACKED_ENTRIES) {
			this.ignitedFire.remove(0);
		}
		this.ignitedFire.add(new TrackedBlock(pos.toImmutable(), originalState));
		this.markDirty();
	}

	public record RevertResult(int blocksRestored, int fireExtinguished) {
	}

	// Manual /dragon revert - restores every tracked melted block AND every tracked ignited-fire
	// position back to whatever was actually there before the dragon touched it (only onto air, or
	// still-burning-fire for the fire list, so a player build since placed there wins and just
	// drops the entry instead of getting overwritten).
	public RevertResult forceRevertAll(ServerWorld world) {
		int restored = 0;
		for (TrackedBlock melted : this.meltedBlocks) {
			if (world.getBlockState(melted.pos).isAir()) {
				world.setBlockState(melted.pos, melted.state);
				restored++;
			}
		}
		this.meltedBlocks.clear();

		int extinguished = 0;
		for (TrackedBlock fire : this.ignitedFire) {
			// Real bug, not guessed - this is why fire "wasn't reverting": vanilla fire blocks
			// self-extinguish on their own well before anyone necessarily runs /dragon revert, so
			// requiring the position to STILL be an AbstractFireBlock right now silently skipped
			// every entry whose fire had already burned itself out, leaving air behind forever
			// instead of restoring the original block. A burned-out dragon fire and a still-burning
			// one both need the same restore - onto air (the fire already gone) or onto whatever
			// AbstractFireBlock is still there (matches the melted-block list's own "only overwrite
			// if nothing new has been built there" rule).
			BlockState current = world.getBlockState(fire.pos);
			if (current.isAir() || current.getBlock() instanceof AbstractFireBlock) {
				world.setBlockState(fire.pos, fire.state);
				extinguished++;
			}
		}
		this.ignitedFire.clear();

		if (restored > 0 || extinguished > 0) {
			this.markDirty();
		}
		return new RevertResult(restored, extinguished);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.put("melted", writeTrackedList(this.meltedBlocks));
		nbt.put("fire", writeTrackedList(this.ignitedFire));
		return nbt;
	}

	private static NbtList writeTrackedList(List<TrackedBlock> blocks) {
		NbtList list = new NbtList();
		for (TrackedBlock tracked : blocks) {
			NbtCompound entry = new NbtCompound();
			entry.putInt("x", tracked.pos.getX());
			entry.putInt("y", tracked.pos.getY());
			entry.putInt("z", tracked.pos.getZ());
			entry.putString("block", Registries.BLOCK.getId(tracked.state.getBlock()).toString());
			list.add(entry);
		}
		return list;
	}

	private static List<TrackedBlock> readTrackedList(NbtList list) {
		List<TrackedBlock> blocks = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			NbtCompound entry = list.getCompound(i);
			BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
			Block block = Registries.BLOCK.get(new net.minecraft.util.Identifier(entry.getString("block")));
			blocks.add(new TrackedBlock(pos, block.getDefaultState()));
		}
		return blocks;
	}

	private static MeltedBlocksState fromNbt(NbtCompound nbt) {
		MeltedBlocksState state = new MeltedBlocksState();
		state.meltedBlocks.addAll(readTrackedList(nbt.getList("melted", NbtElement.COMPOUND_TYPE)));
		state.ignitedFire.addAll(readTrackedList(nbt.getList("fire", NbtElement.COMPOUND_TYPE)));
		return state;
	}

	// Registry ID string + default state, not the full BlockState.CODEC - simpler, and "grows
	// back as the same block" is what "reverts to normal" actually needs; exact prior
	// orientation/waterlogged/etc. state (melted block or whatever was under a fire) is not worth
	// the extra complexity for something that used to just be terrain the fire touched.
	private record TrackedBlock(BlockPos pos, BlockState state) {
	}
}
