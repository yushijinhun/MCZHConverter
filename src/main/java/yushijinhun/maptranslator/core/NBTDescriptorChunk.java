package yushijinhun.maptranslator.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import yushijinhun.maptranslator.nbt.NBTIO;
import yushijinhun.maptranslator.nbt.NBTCompound;
import yushijinhun.maptranslator.nbt.RegionFile;

public class NBTDescriptorChunk implements NBTDescriptor {

	private RegionFile file;
	private int x;
	private int y;

	public NBTDescriptorChunk(RegionFile file, int x, int y) {
		this.file = file;
		this.x = x;
		this.y = y;
	}

	@Override
	public NBTCompound read() {
		try (DataInputStream in = file.getChunkDataInputStream(x, y)) {
			return NBTIO.read(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void write(NBTCompound nbt) {
		try (DataOutputStream out = file.getChunkDataOutputStream(x, y)) {
			NBTIO.write(nbt, out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public String toString() {
		return file.getFile().getPath() + "/chunk[" + x + "," + y + "]";
	}
}
