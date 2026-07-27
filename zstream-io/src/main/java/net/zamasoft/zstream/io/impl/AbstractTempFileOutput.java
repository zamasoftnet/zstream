package net.zamasoft.zstream.io.impl;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.zamasoft.zstream.io.FragmentedOutput;

/**
 * High-performance base implementation of {@link FragmentedOutput}.
 * <p>
 * Data is buffered in fixed-size memory chunks and automatically spilled to a
 * temporary file when the configured global memory limit is reached. The spill
 * strategy evicts the fragment that currently occupies the most memory, keeping
 * working-set memory low while preserving fast random-access writes via
 * {@link FileChannel}.
 * </p>
 * <p>
 * Subclasses must implement the final assembly step by calling
 * {@link #finish(OutputStream)} to stream all fragments in their logical order
 * to an output stream.
 * </p>
 *
 * @author MIYABE Tatsuhiko
 * @since 1.2
 */
public abstract class AbstractTempFileOutput implements FragmentedOutput {
	private static final Logger LOG = Logger.getLogger(AbstractTempFileOutput.class.getName());

	/**
	 * Configuration for buffer management.
	 * <p>
	 * {@code maxMemory} is the amount of heap this instance may retain;
	 * {@code chunkSize} decides how much real data fits inside it, because every
	 * fragment occupies a whole chunk regardless of how few bytes it holds. See
	 * {@link #DEFAULT} for the measurements behind the defaults.
	 * </p>
	 *
	 * @param chunkSize maximum size of each memory chunk (e.g., 8KB).
	 * @param maxMemory maximum total memory to use before spilling to disk.
	 */
	public static class Config {
		/**
		 * Default configuration: 8KB chunks, 16MB max memory.
		 * <p>
		 * Measured 2026-07-27 (JDK 21, Windows 11, NVMe temp dir) with
		 * {@code TempFileOutputBenchmark} in {@code src/test}; 7-11 timed
		 * repetitions per point, 95% confidence intervals typically +/-3..8%.
		 * The grid covered chunkSize 4KB..1MB x maxMemory 1MB..256MB x payload
		 * 64KB..256MB, over four access patterns including the one pdfg2d
		 * actually produces (a main flow plus two tiny fragments per object,
		 * created by {@code PDFFragmentOutputImpl.forkFragment()}).
		 * </p>
		 * <p>
		 * <b>maxMemory is the heap cost, exactly.</b> Retained heap was measured
		 * as {@code min(demand, maxMemory)} in every configuration, and with four
		 * concurrent instances it was 4 x maxMemory (261MB retained at 64MB each).
		 * The old 64MB therefore consumed about half of the ~128MB per-conversion
		 * budget of the HTML-to-PDF engine, per instance; 16MB is ~12%.
		 * </p>
		 * <p>
		 * <b>chunkSize sets how much real data fits in that budget.</b> Every
		 * fragment holds one whole chunk however few bytes it contains, so a
		 * document with F fragments needs {@code payload + F * chunkSize}. For the
		 * pdfg2d pattern F is about payload/4KB, so 64KB chunks needed ~17x the
		 * payload in RAM (1MB of PDF retained 17MB) while 8KB chunks need ~3x
		 * (1MB retained 3MB). 8KB also matches {@code PDFWriterImpl.BUFFER_SIZE},
		 * so a caller's write lands in exactly one chunk.
		 * </p>
		 * <p>
		 * Throughput, 8KB/16MB vs the previous 64KB/64MB (MB/s, mean +/- 95% CI):
		 * </p>
		 * <pre>
		 * payload   pattern      64KB/64MB        8KB/16MB       retained heap
		 * 64KB      fork          851 +/-  49     3251 +/- 189    1MB  -&gt; 554KB
		 * 1MB       fork          724 +/-  39     3815 +/- 199   17MB  -&gt;   3MB
		 * 16MB      fork          172 +/-   6      257 +/-  14   65MB  -&gt;  17MB
		 * 256MB     fork          286 +/-  11       59 +/-  15   67MB  -&gt;  20MB
		 * 16MB      interleave   3444 +/- 306    11943 +/- 1033  66MB  -&gt;  16MB
		 * 16MB      sequential  13684 +/- 558    15412 +/- 1106  16MB  -&gt;  16MB
		 * 256MB     sequential   1033 +/- 269      626 +/-  82   66MB  -&gt;  16MB
		 * </pre>
		 * <p>
		 * <b>Where the trade-off turns.</b> The grid above jumps from 16MB to
		 * 256MB, so that gap was re-measured (phase {@code cliff}, same day).
		 * Any output larger than {@code maxMemory} must spill, so cutting 64MB to
		 * 16MB lowers the spill threshold - and what that costs depends entirely
		 * on the access pattern (MB/s):
		 * </p>
		 * <pre>
		 * payload   SEQ 64K/64M   SEQ 8K/16M    FORK 64K/64M   FORK 8K/16M
		 *  16MB        13508         15478           143            183
		 *  32MB        14712          1270 (!)        72             80
		 *  64MB        14446           915 (!)        64             53
		 * 128MB         1592           886           121             64
		 * 256MB         1255           692           205             56
		 * </pre>
		 * <p>
		 * SEQ collapses by 11-16x at 32-64MB - exactly the range the old 64MB
		 * buffer used to hold entirely in RAM. <b>That pattern is not what the PDF
		 * writer produces</b>: {@code PDFFragmentOutputImpl.forkFragment()} is
		 * called once per stream-length backpatch and three times per page, so
		 * real output is FORK, where the new default wins up to 32MB and loses
		 * 1.2x at 64MB, 1.9x at 128MB, 3.6x at 256MB - a few seconds of I/O
		 * inside a conversion that takes minutes at those sizes. (FORK intervals
		 * are wide, +/-15..28%, so the 32-64MB differences are within noise; only
		 * the 128MB and 256MB losses are outside it.)
		 * </p>
		 * <p>
		 * <b>Real outputs sit below the turn.</b> A 100,000-row Japanese table
		 * (13.8MB of HTML, 35s to convert) produces a <b>10.6MB</b> PDF (measured
		 * 2026-07-27) - inside the range where the new default is both faster and
		 * 4x smaller in heap. A caller that knowingly produces much larger output,
		 * or that appends one fragment sequentially, should pass its own
		 * {@link Config} with a larger {@code maxMemory}.
		 * </p>
		 * <p>
		 * A fixed byte count was chosen over a fraction of
		 * {@link Runtime#maxMemory()} deliberately: the measurement above shows
		 * retention scales with the number of concurrent instances, which this
		 * class cannot see. A "10% of the heap" rule would hand 10% to each of N
		 * concurrent conversions and overshoot the heap by a factor of N.
		 * </p>
		 */
		public static final Config DEFAULT = new Config(8 * 1024, 16 * 1024 * 1024);

		/**
		 * Configuration for purely in-memory processing.
		 * <p>
		 * The chunk size matters more here than in {@link #DEFAULT}, not less:
		 * with no memory cap the per-fragment chunk overhead is never reclaimed
		 * by a spill. Measured 2026-07-27, the same run as above, 1MB of payload
		 * over 257 fragments retained 17MB with 64KB chunks and 3MB with 8KB
		 * chunks, at 724 +/- 39 MB/s and 3815 +/- 199 MB/s respectively - so the
		 * previous 64KB was worse on both axes and was changed to match
		 * {@link #DEFAULT}.
		 * </p>
		 */
		public static final Config ON_MEMORY = new Config(8 * 1024, Long.MAX_VALUE);

		private final int chunkSize;
		private final long maxMemory;

		public Config(final int chunkSize, final long maxMemory) {
			if (chunkSize <= 0) {
				throw new IllegalArgumentException("chunkSize must be positive");
			}
			if (maxMemory <= 0) {
				throw new IllegalArgumentException("maxMemory must be positive");
			}
			this.chunkSize = chunkSize;
			this.maxMemory = maxMemory;
		}

		public int chunkSize() {
			return this.chunkSize;
		}

		public long maxMemory() {
			return this.maxMemory;
		}
	}

	// Configuration
	protected final int chunkSize;
	protected final long maxMemory;

	// Global State
	protected long currentMemoryUsage = 0;
	protected long totalLength = 0;

	// Diagnostics (cumulative, never reset by cleanup)
	private long spillCount = 0;
	private long spilledBytes = 0;

	// Storage
	protected File tempFile;
	protected RandomAccessFile raf;
	protected FileChannel fileChannel;

	// Data Structure
	protected final List<Fragment> fragments = new ArrayList<>();
	protected Fragment first = null;
	protected Fragment last = null;

	// Spill Management
	// We scan for the largest fragment only when spilling is required.
	// This avoids overhead during normal writes.

	/**
	 * A contiguous piece of fragment data stored either in memory or on disk.
	 * <p>
	 * Implementations are {@link MemoryChunk} for in-memory storage and
	 * {@link FileChunk} for disk-backed storage after a spill.
	 * </p>
	 */
	private interface Chunk {
		/**
		 * Returns the number of bytes held by this chunk.
		 *
		 * @return byte count
		 */
		long getLength();

		/**
		 * Writes the entire chunk content to {@code out}.
		 *
		 * @param out     destination stream
		 * @param channel file channel used by {@link FileChunk}; may be {@code null}
		 *                for {@link MemoryChunk}
		 * @param buffer  scratch buffer for disk-to-stream copies
		 * @throws IOException if an I/O error occurs
		 */
		void writeTo(OutputStream out, FileChannel channel, byte[] buffer) throws IOException;
	}

	/**
	 * An in-memory chunk backed by a byte array.
	 * <p>
	 * The array is pre-allocated at construction time with a fixed capacity equal to
	 * {@link #chunkSize}. Only {@link #length} bytes are considered valid.
	 * </p>
	 */
	private final class MemoryChunk implements Chunk {
		final byte[] data;
		int length;

		MemoryChunk(int capacity) {
			this.data = new byte[capacity];
			this.length = 0;
		}

		@Override
		public long getLength() {
			return length;
		}

		@Override
		public void writeTo(OutputStream out, FileChannel channel, byte[] buffer) throws IOException {
			out.write(data, 0, length);
		}
	}

	/**
	 * A disk-backed chunk that references a byte range inside the shared temporary
	 * file.
	 * <p>
	 * Created when a {@link MemoryChunk} is spilled to disk.  The chunk is
	 * immutable after creation; its content is addressed by absolute file position.
	 * </p>
	 */
	private final class FileChunk implements Chunk {
		final long position;
		final long length;

		FileChunk(long position, long length) {
			this.position = position;
			this.length = length;
		}

		@Override
		public long getLength() {
			return length;
		}

		@Override
		public void writeTo(OutputStream out, FileChannel channel, byte[] buffer) throws IOException {
			long remaining = length;
			long currentPos = position;
			while (remaining > 0) {
				int readSize = (int) Math.min(remaining, buffer.length);
				ByteBuffer buf = ByteBuffer.wrap(buffer, 0, readSize);
				int read = channel.read(buf, currentPos);
				if (read == -1)
					break;
				out.write(buffer, 0, read);
				currentPos += read;
				remaining -= read;
			}
		}
	}

	/**
	 * A single logical fragment that accumulates data in a list of {@link Chunk}s.
	 * <p>
	 * Fragments form a doubly-linked list ({@link #prev} / {@link #next}) that
	 * defines their output order, which may differ from their creation order.
	 * </p>
	 */
	protected class Fragment {
		final int id;
		Fragment prev, next;

		final List<Chunk> chunks = new ArrayList<>();
		long totalLength = 0;
		long memoryUsage = 0;

		/** Most recently allocated in-memory chunk; {@code null} when full or spilled. */
		MemoryChunk activeChunk = null;

		Fragment(int id) {
			this.id = id;
		}

		/**
		 * Returns the fragment ID (its index in {@link #fragments}).
		 *
		 * @return fragment ID
		 */
		int getId() {
			return id;
		}

		/**
		 * Returns the total number of bytes written to this fragment.
		 *
		 * @return byte count
		 */
		long getLength() {
			return totalLength;
		}

		/**
		 * Appends data to this fragment, allocating new chunks as needed and
		 * triggering a spill when the global memory limit is exceeded.
		 *
		 * @param b   source byte array
		 * @param off start offset in {@code b}
		 * @param len number of bytes to write
		 * @throws IOException if an I/O error occurs during a spill
		 */
		void write(byte[] b, int off, int len) throws IOException {
			int remaining = len;
			int offset = off;

			while (remaining > 0) {
				ensureActiveChunk();

				int space = activeChunk.data.length - activeChunk.length;
				int toWrite = Math.min(remaining, space);

				System.arraycopy(b, offset, activeChunk.data, activeChunk.length, toWrite);

				activeChunk.length += toWrite;
				this.totalLength += toWrite;
				offset += toWrite;
				remaining -= toWrite;

				if (activeChunk.length == activeChunk.data.length) {
					// Current chunk is full
					activeChunk = null;
				}
			}
		}

		private void ensureActiveChunk() throws IOException {
			if (activeChunk == null) {
				spillIfNeeded(); // Check memory limit before allocation

				activeChunk = new MemoryChunk(chunkSize);
				chunks.add(activeChunk);

				this.memoryUsage += chunkSize;
				updateGlobalMemory(chunkSize);
			}
		}

		/**
		 * Writes all in-memory chunks of this fragment to the shared temporary file,
		 * replacing them with {@link FileChunk}s and releasing the memory budget.
		 * <p>
		 * Contiguous runs of {@link MemoryChunk}s are flushed in a single scatter-write
		 * operation ({@link FileChannel#write(java.nio.ByteBuffer[])}) for efficiency.
		 * After the call, {@link #memoryUsage} is zero and {@link #activeChunk} is
		 * {@code null}.
		 * </p>
		 *
		 * @throws IOException if an I/O error occurs while writing the temp file
		 */
		void spill() throws IOException {
			if (memoryUsage == 0)
				return;

			// Prepare file
			ensureFileOpen();

			// Allocate a buffer for bulk writing if needed, but here we can just write
			// chunks directly
			// FileChannel.write(ByteBuffer[]) is perfect for this.

			List<ByteBuffer> buffersToWrite = new ArrayList<>();
			long bytesToSpill = 0;

			// We only spill MemoryChunks that are currently in the list
			// We replace them with FileChunks.

			int firstMemoryIndex = -1;

			for (int i = 0; i < chunks.size(); i++) {
				Chunk c = chunks.get(i);
				if (c instanceof MemoryChunk) {
					MemoryChunk mc = (MemoryChunk)c;
					if (firstMemoryIndex == -1)
						firstMemoryIndex = i;
					buffersToWrite.add(ByteBuffer.wrap(mc.data, 0, mc.length));
					bytesToSpill += mc.length;
				} else if (firstMemoryIndex != -1) {
					// End of a contiguous block of memory chunks -> write them
					flushMemoryBlock(firstMemoryIndex, i, buffersToWrite, bytesToSpill);
					firstMemoryIndex = -1;
					buffersToWrite.clear();
					bytesToSpill = 0;
				}
			}

			// Handle trailing memory chunks
			if (firstMemoryIndex != -1) {
				flushMemoryBlock(firstMemoryIndex, chunks.size(), buffersToWrite, bytesToSpill);
			}

			// Reset memory usage for this fragment
			updateGlobalMemory(-this.memoryUsage);
			this.memoryUsage = 0;
			this.activeChunk = null; // Forces new chunk on next write
		}

		private void flushMemoryBlock(int startIndex, int endIndex, List<ByteBuffer> buffers, long length)
				throws IOException {
			if (length == 0)
				return;

			long filePos = fileChannel.position(); // Append to end

			// Write all buffers
			ByteBuffer[] bufArray = buffers.toArray(new ByteBuffer[0]);
			long written = 0;
			while (written < length) {
				written += fileChannel.write(bufArray);
			}
			++spillCount;
			spilledBytes += length;

			// Replace chunks in list with a single FileChunk
			chunks.subList(startIndex, endIndex).clear();
			chunks.add(startIndex, new FileChunk(filePos, length));
		}
	}

	public AbstractTempFileOutput(Config config) {
		this.chunkSize = config.chunkSize();
		this.maxMemory = config.maxMemory();
	}

	/**
	 * @deprecated Use {@link #AbstractTempFileOutput(Config)} instead.
	 *             This constructor existed before the {@link Config} class was
	 *             introduced and the {@code threshold} parameter is ignored.
	 */
	@Deprecated
	public AbstractTempFileOutput(int fragmentBufferSize, int totalBufferSize, int threshold) {
		this(new Config(fragmentBufferSize, totalBufferSize));
	}

	/**
	 * Creates a new instance with the default configuration.
	 *
	 * @see Config#DEFAULT
	 */
	public AbstractTempFileOutput() {
		this(Config.DEFAULT);
	}

	// --- FragmentedOutput Implementation ---

	@Override
	public void addFragment() throws IOException {
		Fragment f = new Fragment(fragments.size());
		fragments.add(f);

		if (first == null) {
			first = f;
		} else {
			last.next = f;
			f.prev = last;
		}
		last = f;
	}

	@Override
	public void insertFragmentBefore(int anchorId) throws IOException {
		Fragment anchor = fragments.get(anchorId);
		Fragment f = new Fragment(fragments.size());
		fragments.add(f);

		f.prev = anchor.prev;
		f.next = anchor;
		if (anchor.prev != null) {
			anchor.prev.next = f;
		} else {
			first = f;
		}
		anchor.prev = f;
	}

	@Override
	public void write(int id, byte[] b, int off, int len) throws IOException {
		Fragment f = fragments.get(id);
		f.write(b, off, len);
		this.totalLength += len;
	}

	@Override
	public void patch(int id, long fragmentOffset, byte[] b, int off, int len) throws IOException {
		Fragment f = fragments.get(id);
		long currentPos = 0;
		int remaining = len;
		int currentOff = off;

		for (Chunk chunk : f.chunks) {
			long chunkLen = chunk.getLength();

			// Check if our patch range overlaps with this chunk
			if (currentPos + chunkLen > fragmentOffset) {
				// We have intersection.

				// How far into the chunk do we start?
				long startInChunk = Math.max(0, fragmentOffset - currentPos);

				// How much can we write to this chunk?
				long toWrite = Math.min(remaining, chunkLen - startInChunk);

				if (chunk instanceof MemoryChunk) {
					MemoryChunk mc = (MemoryChunk)chunk;
					System.arraycopy(b, currentOff, mc.data, (int) startInChunk, (int) toWrite);
				} else if (chunk instanceof FileChunk) {
					FileChunk fc = (FileChunk)chunk;
					ByteBuffer buf = ByteBuffer.wrap(b, currentOff, (int) toWrite);
					fileChannel.write(buf, fc.position + startInChunk);
				}

				currentOff += toWrite;
				remaining -= toWrite;
			}

			currentPos += chunkLen;
			if (remaining <= 0)
				break;
		}

		if (remaining > 0) {
			throw new IOException("Patch exceeds fragment bounds. ID=" + id + ", Offset=" + fragmentOffset +
					", Len=" + len + ", Remaining=" + remaining +
					", FragmentLength=" + f.getLength() + ", Chunks=" + f.chunks.size());
		}
	}

	@Override
	public void finishFragment(int id) throws IOException {
		// No-op in this strategy
	}

	@Override
	public PositionInfo getPositionInfo() {
		// Snapshot all fragment start positions ordered by the linked list.
		final long[] positions = new long[fragments.size()];
		long pos = 0;
		for (Fragment curr = first; curr != null; curr = curr.next) {
			positions[curr.getId()] = pos;
			pos += curr.getLength();
		}
		return id -> positions[id];
	}

	@Override
	public boolean supportsPositionInfo() {
		return true;
	}

	/**
	 * Returns the total number of bytes written across all fragments.
	 *
	 * @return total byte count
	 */
	public long getLength() {
		return totalLength;
	}

	/**
	 * Returns how many times a block of in-memory chunks has been written out to
	 * the temporary file. Diagnostics only; the counter is cumulative and is not
	 * reset when the instance is closed.
	 *
	 * @return number of spill writes performed so far
	 * @since 1.2
	 */
	public long getSpillCount() {
		return spillCount;
	}

	/**
	 * Returns the total number of bytes written to the temporary file.
	 * Diagnostics only; the counter is cumulative and is not reset when the
	 * instance is closed.
	 *
	 * @return number of bytes spilled to disk so far
	 * @since 1.2
	 */
	public long getSpilledBytes() {
		return spilledBytes;
	}

	@Override
	public void close() throws IOException {
		cleanup();
	}

	/**
	 * Returns the current logical output as a contiguous byte array without
	 * consuming the fragments.
	 *
	 * @return snapshot of the assembled fragment contents
	 * @throws IOException if an I/O error occurs while copying spilled chunks
	 */
	public byte[] snapshotBytes() throws IOException {
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, this.totalLength));
		this.writeFragments(out);
		return out.toByteArray();
	}

	/**
	 * Replaces the current fragment set with a single fragment containing
	 * {@code bytes}. This is useful when a higher layer needs to rebuild the final
	 * serialized form after using fragments for intermediate layout calculations.
	 *
	 * @param bytes replacement output bytes
	 * @throws IOException if an I/O error occurs while resetting fragment storage
	 */
	public void replaceBytes(final byte[] bytes) throws IOException {
		for (final Fragment fragment : this.fragments) {
			fragment.chunks.clear();
			fragment.totalLength = 0;
			fragment.memoryUsage = 0;
			fragment.activeChunk = null;
		}
		if (this.fragments.isEmpty()) {
			this.addFragment();
		}
		this.first = this.fragments.get(0);
		this.last = this.fragments.get(this.fragments.size() - 1);
		for (int i = 0; i < this.fragments.size(); ++i) {
			final Fragment fragment = this.fragments.get(i);
			fragment.prev = (i == 0) ? null : this.fragments.get(i - 1);
			fragment.next = (i + 1 < this.fragments.size()) ? this.fragments.get(i + 1) : null;
		}
		this.currentMemoryUsage = 0;
		this.totalLength = 0;
		this.write(this.fragments.get(0).id, bytes, 0, bytes.length);
	}

	/**
	 * Writes all fragments to {@code out} in their logical order, then releases
	 * all resources.
	 * <p>
	 * This method is intended to be called by subclass {@code close()} methods.
	 * After it returns, the instance must not be used further.
	 * </p>
	 *
	 * @param out destination stream
	 * @throws IOException if an I/O error occurs
	 */
	protected void finish(OutputStream out) throws IOException {
		if (first == null) {
			cleanup();
			return;
		}

		if (LOG.isLoggable(Level.FINE)) {
			LOG.fine("Finishing output. Total length: " + totalLength + ", Memory usage: " + currentMemoryUsage);
		}

		this.writeFragments(out);

		out.flush();
		cleanup();
	}

	private void writeFragments(final OutputStream out) throws IOException {
		final byte[] copyBuffer = new byte[8192];
		for (Fragment curr = first; curr != null; curr = curr.next) {
			for (final Chunk chunk : curr.chunks) {
				chunk.writeTo(out, fileChannel, copyBuffer);
			}
		}
	}

	// --- Internal Helpers ---

	private void updateGlobalMemory(long delta) {
		this.currentMemoryUsage += delta;
	}

	/**
	 * Spills fragments to disk until {@link #currentMemoryUsage} is below
	 * {@link #maxMemory}.  Always evicts the fragment with the highest in-memory
	 * footprint.
	 *
	 * @throws IOException if an I/O error occurs during the spill
	 */
	private void spillIfNeeded() throws IOException {
		while (currentMemoryUsage >= maxMemory) {
			final Fragment candidate = findFragmentWithMostMemory();
			if (candidate == null || candidate.memoryUsage == 0) {
				break;
			}
			candidate.spill();
		}
	}

	/**
	 * Returns the fragment that currently occupies the most memory, or
	 * {@code null} if the fragment list is empty.
	 *
	 * @return fragment with the highest {@link Fragment#memoryUsage}, or
	 *         {@code null}
	 */
	private Fragment findFragmentWithMostMemory() {
		return fragments.stream()
				.max(java.util.Comparator.comparingLong(f -> f.memoryUsage))
				.orElse(null);
	}

	/**
	 * Opens the shared temporary file if it has not been opened yet.
	 *
	 * @throws IOException if the temp file cannot be created or opened
	 */
	private void ensureFileOpen() throws IOException {
		if (fileChannel == null) {
			tempFile = File.createTempFile("zstream-io-fast-", ".tmp");
			tempFile.deleteOnExit();
			raf = new RandomAccessFile(tempFile, "rw");
			fileChannel = raf.getChannel();
		}
	}

	/**
	 * Closes and deletes the temporary file (if any) and resets all state.
	 * <p>
	 * Errors during resource release are logged at {@code WARNING} level and
	 * swallowed so that they do not mask earlier exceptions.
	 * </p>
	 */
	private void cleanup() {
		try {
			if (fileChannel != null)
				fileChannel.close();
			if (raf != null)
				raf.close();
			if (tempFile != null)
				tempFile.delete();
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Failed to clean up temp resources", e);
		} finally {
			fileChannel = null;
			raf = null;
			tempFile = null;
			fragments.clear();
			first = last = null;
			currentMemoryUsage = 0;
			totalLength = 0;
		}
	}
}
