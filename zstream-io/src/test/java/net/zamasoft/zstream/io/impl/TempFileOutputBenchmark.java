package net.zamasoft.zstream.io.impl;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Measurement harness for the {@link AbstractTempFileOutput.Config} tuning
 * constants ({@code chunkSize} / {@code maxMemory}).
 * <p>
 * This is <em>not</em> a unit test. It is a plain {@code main} so it can be run
 * on a JVM of our choosing with a controlled heap; the project has no JMH
 * dependency and adding one was not warranted for a harness that measures
 * throughput, retained heap and spill counts of a single class.
 * </p>
 * <p>
 * Run it with {@code gradlew :zstream-io:bench -Pbench.phase=...}. See
 * {@code zstream-io/build.gradle}.
 * </p>
 * <p>
 * Phases:
 * </p>
 * <ul>
 * <li>{@code sweep} - full chunkSize x maxMemory grid at a fixed payload size,
 * for every access pattern.</li>
 * <li>{@code sizes} - selected configurations across 64KB..256MB payloads.</li>
 * <li>{@code envelope} - can a conversion complete inside the ~128MB per
 * conversion budget?</li>
 * <li>{@code concurrency} - what does N concurrent conversions actually
 * retain?</li>
 * </ul>
 */
public final class TempFileOutputBenchmark {

	/** Block size used by callers such as pdfg2d's FragmentOutputAdapter. */
	private static final int WRITE_BLOCK = 8 * 1024;

	/** Bytes written to a "backpatch" fragment (a PDF /Length value). */
	private static final int SMALL_FRAGMENT_BYTES = 16;

	/** Content bytes per PDF-object-like unit in the FORK pattern. */
	private static final int FORK_OBJECT_BYTES = 8 * 1024;

	/** Upper bound on fragments created by FORK, to keep runtimes tractable. */
	private static final int FORK_MAX_OBJECTS = 8192;

	/** Fragment count for the INTERLEAVE pattern. */
	private static final int INTERLEAVE_FRAGMENTS = 1024;

	/** Number of random patches applied by the PATCH pattern. */
	private static final int PATCH_COUNT = 1024;

	private static final byte[] SRC = newSource(64 * 1024);

	private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

	private TempFileOutputBenchmark() {
	}

	// ------------------------------------------------------------------
	// Access patterns
	// ------------------------------------------------------------------

	enum Pattern {
		/** One fragment, pure sequential append. The streaming happy path. */
		SEQ,
		/**
		 * pdfg2d's real shape: a main flow that grows, plus two tiny fragments
		 * inserted before it per object (stream /Length backpatching via
		 * {@code forkFragment()}).
		 */
		FORK,
		/** Many long-lived fragments written round-robin. */
		INTERLEAVE,
		/** Sequential append followed by random {@code patch()} calls. */
		PATCH
	}

	// ------------------------------------------------------------------
	// Instrumented subclass
	// ------------------------------------------------------------------

	static final class BenchOutput extends AbstractTempFileOutput {
		BenchOutput(final Config config) {
			super(config);
		}

		void finishTo(final OutputStream out) throws IOException {
			this.finish(out);
		}

		long accountedMemory() {
			return this.currentMemoryUsage;
		}

		long tempFileLength() {
			return this.tempFile == null ? 0L : this.tempFile.length();
		}

		int fragmentCount() {
			return this.fragments.size();
		}
	}

	/** Discards everything but counts, so finish() cost is measured, not the sink. */
	static final class CountingSink extends OutputStream {
		long count;

		@Override
		public void write(final int b) {
			++this.count;
		}

		@Override
		public void write(final byte[] b, final int off, final int len) {
			this.count += len;
		}
	}

	// ------------------------------------------------------------------
	// One run
	// ------------------------------------------------------------------

	static final class Run {
		long writeNanos;
		long finishNanos;
		long retainedHeap = -1;
		long accountedMemory;
		long spillCount;
		long spilledBytes;
		long tempFileLength;
		int fragments;
		long bytesOut;

		long totalNanos() {
			return this.writeNanos + this.finishNanos;
		}
	}

	static Run run(final Pattern pattern, final long total, final int chunkSize, final long maxMemory,
			final boolean measureHeap) throws Exception {
		final Run result = new Run();
		final AbstractTempFileOutput.Config config = new AbstractTempFileOutput.Config(chunkSize, maxMemory);
		final CountingSink sink = new CountingSink();
		final BenchOutput out = new BenchOutput(config);

		final long t0 = System.nanoTime();
		switch (pattern) {
		case SEQ:
			writeSequential(out, total);
			break;
		case FORK:
			writeFork(out, total);
			break;
		case INTERLEAVE:
			writeInterleave(out, total);
			break;
		case PATCH:
			writeSequential(out, total);
			patchRandomly(out, total);
			break;
		default:
			throw new IllegalStateException();
		}
		final long t1 = System.nanoTime();
		result.writeNanos = t1 - t0;

		// Everything is still live here: this is the peak of the retained set.
		result.accountedMemory = out.accountedMemory();
		result.fragments = out.fragmentCount();
		result.tempFileLength = out.tempFileLength();
		result.spillCount = out.getSpillCount();
		result.spilledBytes = out.getSpilledBytes();
		if (measureHeap) {
			// Outside both timers on purpose.
			result.retainedHeap = retainedHeap();
		}

		final long t2 = System.nanoTime();
		out.finishTo(sink);
		final long t3 = System.nanoTime();
		result.finishNanos = t3 - t2;
		result.bytesOut = sink.count;
		out.close();
		if (result.bytesOut != total) {
			throw new IllegalStateException(
					pattern + ": expected " + total + " bytes out, got " + result.bytesOut);
		}
		return result;
	}

	private static void writeSequential(final BenchOutput out, final long total) throws IOException {
		out.addFragment();
		long written = 0;
		while (written < total) {
			final int n = (int) Math.min(WRITE_BLOCK, total - written);
			out.write(0, SRC, 0, n);
			written += n;
		}
	}

	private static void writeFork(final BenchOutput out, final long total) throws IOException {
		int objects = (int) Math.max(1, total / FORK_OBJECT_BYTES);
		if (objects > FORK_MAX_OBJECTS) {
			objects = FORK_MAX_OBJECTS;
		}
		final long smallTotal = (long) objects * 2L * SMALL_FRAGMENT_BYTES;
		final long contentTotal = total - Math.min(total / 2, smallTotal);
		final long perObject = contentTotal / objects;

		out.addFragment(); // id 0: the main flow, always logically last.
		int nextId = 1;
		final int tail = 0;
		long written = 0;
		for (int i = 0; i < objects; ++i) {
			// forkFragment() creates two fragments inserted before the anchor.
			out.insertFragmentBefore(tail);
			final int a = nextId++;
			out.insertFragmentBefore(tail);
			final int b = nextId++;
			out.write(a, SRC, 0, SMALL_FRAGMENT_BYTES);
			out.write(b, SRC, 0, SMALL_FRAGMENT_BYTES);
			written += 2L * SMALL_FRAGMENT_BYTES;

			long remaining = Math.min(perObject, total - written);
			while (remaining > 0) {
				final int n = (int) Math.min(WRITE_BLOCK, remaining);
				out.write(tail, SRC, 0, n);
				remaining -= n;
				written += n;
			}
		}
		while (written < total) {
			final int n = (int) Math.min(WRITE_BLOCK, total - written);
			out.write(tail, SRC, 0, n);
			written += n;
		}
	}

	private static void writeInterleave(final BenchOutput out, final long total) throws IOException {
		final int n = (int) Math.min(INTERLEAVE_FRAGMENTS, Math.max(1, total / WRITE_BLOCK));
		for (int i = 0; i < n; ++i) {
			out.addFragment();
		}
		long written = 0;
		int cursor = 0;
		while (written < total) {
			final int len = (int) Math.min(WRITE_BLOCK, total - written);
			out.write(cursor, SRC, 0, len);
			written += len;
			cursor = (cursor + 1) % n;
		}
	}

	private static void patchRandomly(final BenchOutput out, final long total) throws IOException {
		final Random random = new Random(12345L);
		for (int i = 0; i < PATCH_COUNT; ++i) {
			final long offset = (long) (random.nextDouble() * (total - 8));
			out.patch(0, offset, SRC, 0, 8);
		}
	}

	// ------------------------------------------------------------------
	// Heap measurement
	// ------------------------------------------------------------------

	private static long baseline = 0;

	private static long retainedHeap() throws InterruptedException {
		for (int i = 0; i < 4; ++i) {
			System.gc();
			Thread.sleep(15);
		}
		return MEMORY.getHeapMemoryUsage().getUsed() - baseline;
	}

	private static void calibrateBaseline() throws InterruptedException {
		baseline = 0;
		baseline = retainedHeap();
	}

	// ------------------------------------------------------------------
	// Statistics
	// ------------------------------------------------------------------

	/** Student t, two-sided 95%, indexed by degrees of freedom (1..30). */
	private static final double[] T95 = { Double.NaN, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262,
			2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064,
			2.060, 2.056, 2.052, 2.048, 2.045, 2.042 };

	static final class Stats {
		final double mean;
		final double sd;
		final double ci95;
		final double min;
		final double max;
		final int n;

		Stats(final double[] xs) {
			this.n = xs.length;
			double sum = 0;
			double lo = Double.MAX_VALUE;
			double hi = -Double.MAX_VALUE;
			for (final double x : xs) {
				sum += x;
				lo = Math.min(lo, x);
				hi = Math.max(hi, x);
			}
			this.mean = sum / this.n;
			double sq = 0;
			for (final double x : xs) {
				sq += (x - this.mean) * (x - this.mean);
			}
			this.sd = this.n > 1 ? Math.sqrt(sq / (this.n - 1)) : 0;
			final int df = this.n - 1;
			final double t = (df >= 1 && df < T95.length) ? T95[df] : 1.96;
			this.ci95 = this.n > 1 ? t * this.sd / Math.sqrt(this.n) : 0;
			this.min = lo;
			this.max = hi;
		}

		/** Half-width of the 95% CI as a percentage of the mean. */
		double relCi95() {
			return this.mean == 0 ? 0 : 100.0 * this.ci95 / this.mean;
		}
	}

	// ------------------------------------------------------------------
	// Reporting
	// ------------------------------------------------------------------

	private static PrintWriter csv;

	private static void emit(final Pattern pattern, final long total, final int chunkSize, final long maxMemory,
			final Stats mbps, final Run probe) {
		csv.printf("%s,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%d%n", pattern, total, chunkSize, maxMemory,
				mbps.n, mbps.mean, mbps.ci95, mbps.relCi95(), mbps.min, mbps.max, probe.retainedHeap,
				probe.accountedMemory, probe.spillCount, probe.spilledBytes, probe.tempFileLength, probe.fragments);
		csv.flush();
		System.out.printf("%-10s %7s %7s %7s | %8.1f +/- %5.1f MB/s (+/-%4.1f%%) | heap %7s acct %7s | spills %6d %8s%n",
				pattern, human(total), human(chunkSize), human(maxMemory), mbps.mean, mbps.ci95, mbps.relCi95(),
				human(probe.retainedHeap), human(probe.accountedMemory), probe.spillCount, human(probe.spilledBytes));
	}

	static String human(final long bytes) {
		if (bytes < 0) {
			return "-";
		}
		if (bytes == Long.MAX_VALUE) {
			return "MAX";
		}
		if (bytes >= 1024L * 1024 * 1024) {
			return (bytes / (1024L * 1024 * 1024)) + "G";
		}
		if (bytes >= 1024 * 1024) {
			return (bytes / (1024 * 1024)) + "M";
		}
		if (bytes >= 1024) {
			return (bytes / 1024) + "K";
		}
		return bytes + "B";
	}

	// ------------------------------------------------------------------
	// Phases
	// ------------------------------------------------------------------

	/**
	 * Work per timed sample. A single 16MB SEQ run takes well under a
	 * millisecond, which is far too short to time against a ~100ns clock and a
	 * GC that fires whenever it likes; each sample therefore repeats the
	 * workload until at least this many bytes have gone through it.
	 */
	private static final long SAMPLE_TARGET_BYTES = 64L << 20;

	private static int innerIterations(final long total) {
		final long n = Math.max(1L, SAMPLE_TARGET_BYTES / Math.max(1L, total));
		return (int) Math.min(2048L, n);
	}

	private static Stats measure(final Pattern pattern, final long total, final int chunkSize, final long maxMemory,
			final int warmups, final int reps) throws Exception {
		final int inner = innerIterations(total);
		for (int i = 0; i < warmups; ++i) {
			for (int j = 0; j < inner; ++j) {
				run(pattern, total, chunkSize, maxMemory, false);
			}
		}
		final double[] mbps = new double[reps];
		for (int i = 0; i < reps; ++i) {
			long nanos = 0;
			for (int j = 0; j < inner; ++j) {
				nanos += run(pattern, total, chunkSize, maxMemory, false).totalNanos();
			}
			mbps[i] = ((double) total * inner / (1024.0 * 1024.0)) / (nanos / 1e9);
		}
		return new Stats(mbps);
	}

	private static void warmUpJit() throws Exception {
		System.out.println("# JIT warmup...");
		for (int i = 0; i < 12; ++i) {
			for (final Pattern p : Pattern.values()) {
				run(p, 2L * 1024 * 1024, 64 * 1024, 1L * 1024 * 1024, false);
				run(p, 2L * 1024 * 1024, 8 * 1024, 64L * 1024 * 1024, false);
			}
		}
		System.out.println("# warmup done");
	}

	private static void phaseSweep(final long payload, final int reps) throws Exception {
		final int[] chunks = { 4 * 1024, 8 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024 };
		final long[] mems = { 1L << 20, 4L << 20, 16L << 20, 64L << 20, 256L << 20 };
		for (final Pattern pattern : Pattern.values()) {
			for (final int chunk : chunks) {
				for (final long mem : mems) {
					final Run probe = run(pattern, payload, chunk, mem, true);
					final Stats s = measure(pattern, payload, chunk, mem, 1, reps);
					emit(pattern, payload, chunk, mem, s, probe);
				}
			}
		}
	}

	private static void phaseSizes(final int reps) throws Exception {
		final long[] sizes = { 64L << 10, 1L << 20, 16L << 20, 256L << 20 };
		// Head-to-head: the incumbent default against the candidates.
		final int[][] configs = { { 64 * 1024, 64 }, { 8 * 1024, 16 }, { 4 * 1024, 16 }, { 16 * 1024, 16 } };
		for (final Pattern pattern : Pattern.values()) {
			for (final long size : sizes) {
				for (final int[] c : configs) {
					final long mem = (long) c[1] << 20;
					final int localReps = size >= (256L << 20) ? Math.max(3, reps / 2) : reps;
					final Run probe = run(pattern, size, c[0], mem, true);
					final Stats s = measure(pattern, size, c[0], mem, 1, localReps);
					emit(pattern, size, c[0], mem, s, probe);
				}
			}
		}
	}

	/**
	 * Locates the payload size at which the new default stops winning.
	 *
	 * <p>
	 * {@code sizes} left a gap between 16MB (where 8K/16M is faster) and 256MB
	 * (where it is ~5x slower). Accepting that trade-off requires knowing where
	 * the crossover actually is, so this compares only the incumbent against the
	 * chosen default, on the pattern pdfg2d produces, across the gap.
	 * </p>
	 */
	private static void phaseCliff(final int reps) throws Exception {
		final long[] sizes = { 16L << 20, 32L << 20, 64L << 20, 128L << 20, 256L << 20 };
		final int[][] configs = { { 64 * 1024, 64 }, { 8 * 1024, 16 } };
		for (final Pattern pattern : new Pattern[] { Pattern.FORK, Pattern.SEQ }) {
			for (final long size : sizes) {
				for (final int[] c : configs) {
					final long mem = (long) c[1] << 20;
					final int localReps = size >= (128L << 20) ? Math.max(3, reps / 2) : reps;
					final Run probe = run(pattern, size, c[0], mem, true);
					final Stats st = measure(pattern, size, c[0], mem, 1, localReps);
					emit(pattern, size, c[0], mem, st, probe);
				}
			}
		}
	}

	/**
	 * Zooms in on the candidate region found by {@code sweep}: the chunk sizes
	 * that are plausible defaults, crossed with memory caps that are a defensible
	 * fraction of the ~128MB per-conversion budget, across the payload range.
	 */
	private static void phaseFocus() throws Exception {
		final int[] chunks = { 4 * 1024, 8 * 1024, 16 * 1024, 32 * 1024, 64 * 1024 };
		final long[] mems = { 8L << 20, 16L << 20, 32L << 20, 64L << 20 };
		final long[] sizes = { 1L << 20, 16L << 20, 256L << 20 };
		for (final Pattern pattern : new Pattern[] { Pattern.FORK, Pattern.INTERLEAVE, Pattern.SEQ, Pattern.PATCH }) {
			for (final long size : sizes) {
				final boolean big = size > (16L << 20);
				for (final int chunk : chunks) {
					for (final long mem : mems) {
						final Run probe = run(pattern, size, chunk, mem, true);
						final Stats s = measure(pattern, size, chunk, mem, big ? 0 : 1, big ? 3 : 5);
						emit(pattern, size, chunk, mem, s, probe);
					}
				}
			}
		}
	}

	private static void phaseEnvelope() throws Exception {
		System.out.println("# envelope: Runtime.maxMemory()=" + human(Runtime.getRuntime().maxMemory()));
		final long payload = 64L << 20;
		final int[][] configs = { { 64 * 1024, 64 }, { 64 * 1024, 32 }, { 64 * 1024, 16 }, { 64 * 1024, 8 },
				{ 16 * 1024, 16 }, { 8 * 1024, 16 }, { 8 * 1024, 8 }, { 4 * 1024, 8 } };
		for (final Pattern pattern : new Pattern[] { Pattern.FORK, Pattern.INTERLEAVE, Pattern.SEQ }) {
			for (final int[] c : configs) {
				final long mem = (long) c[1] << 20;
				try {
					final Run probe = run(pattern, payload, c[0], mem, true);
					System.out.printf("%-10s chunk=%-6s max=%-6s -> OK   retained=%-7s accounted=%-7s spills=%d%n",
							pattern, human(c[0]), human(mem), human(probe.retainedHeap),
							human(probe.accountedMemory), probe.spillCount);
					csv.printf("ENVELOPE,%s,%d,%d,%d,OK,%d,%d,%d%n", pattern, payload, c[0], mem, probe.retainedHeap,
							probe.accountedMemory, probe.spillCount);
				} catch (final OutOfMemoryError e) {
					System.out.printf("%-10s chunk=%-6s max=%-6s -> OOM (%s)%n", pattern, human(c[0]), human(mem),
							e.getMessage());
					csv.printf("ENVELOPE,%s,%d,%d,%d,OOM,,,%n", pattern, payload, c[0], mem);
				}
				csv.flush();
			}
		}
	}

	/**
	 * Measures what N simultaneous conversions actually retain. Every worker
	 * builds its whole payload, then parks at a barrier while still holding it,
	 * so the forced-GC measurement sees the true retained set rather than the
	 * allocation high-water mark.
	 */
	private static void phaseConcurrency(final int threads) throws Exception {
		System.out.println("# concurrency: Runtime.maxMemory()=" + human(Runtime.getRuntime().maxMemory())
				+ " threads=" + threads);
		final long payload = 32L << 20;
		final int[][] configs = { { 64 * 1024, 64 }, { 64 * 1024, 16 }, { 16 * 1024, 16 }, { 8 * 1024, 16 },
				{ 8 * 1024, 8 } };
		for (final int[] c : configs) {
			final long mem = (long) c[1] << 20;
			final ExecutorService pool = Executors.newFixedThreadPool(threads);
			final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
			final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
			final List<Future<Long>> futures = new ArrayList<Future<Long>>();
			try {
				for (int i = 0; i < threads; ++i) {
					futures.add(pool.submit(new Callable<Long>() {
						@Override
						public Long call() throws Exception {
							final BenchOutput out = new BenchOutput(
									new AbstractTempFileOutput.Config(c[0], mem));
							writeFork(out, payload);
							final long accounted = out.accountedMemory();
							ready.countDown();
							go.await();
							out.finishTo(new CountingSink());
							out.close();
							return accounted;
						}
					}));
				}
				ready.await();
				final long retained = retainedHeap();
				go.countDown();
				long accountedTotal = 0;
				for (final Future<Long> f : futures) {
					accountedTotal += f.get();
				}
				System.out.printf("chunk=%-6s max=%-6s threads=%d -> retained %-7s (accounted %-7s, cap N*max %s)%n",
						human(c[0]), human(mem), threads, human(retained), human(accountedTotal),
						human((long) threads * mem));
				csv.printf("CONCURRENCY,%d,%d,%d,%d,%d%n", c[0], mem, threads, retained, accountedTotal);
				csv.flush();
			} finally {
				pool.shutdownNow();
			}
		}
	}

	// ------------------------------------------------------------------

	public static void main(final String[] args) throws Exception {
		final Map<String, String> opts = new LinkedHashMap<String, String>();
		for (final String arg : args) {
			final int eq = arg.indexOf('=');
			if (eq > 0) {
				opts.put(arg.substring(0, eq), arg.substring(eq + 1));
			} else {
				opts.put(arg, "true");
			}
		}
		final String phase = opts.containsKey("phase") ? opts.get("phase") : "sweep";
		final int reps = opts.containsKey("reps") ? Integer.parseInt(opts.get("reps")) : 7;
		final long payload = opts.containsKey("payload") ? Long.parseLong(opts.get("payload")) : (16L << 20);
		final File out = new File(opts.containsKey("out") ? opts.get("out") : ("bench-" + phase + ".csv"));

		System.out.println("# " + new java.util.Date());
		System.out.println("# java " + System.getProperty("java.version") + " " + System.getProperty("java.vm.name"));
		System.out.println("# os " + System.getProperty("os.name") + " cpus " + Runtime.getRuntime().availableProcessors());
		System.out.println("# maxMemory " + human(Runtime.getRuntime().maxMemory()));
		System.out.println("# tmpdir " + System.getProperty("java.io.tmpdir"));
		System.out.println("# phase=" + phase + " reps=" + reps + " payload=" + human(payload) + " out=" + out);

		csv = new PrintWriter(new FileWriter(out));
		try {
			csv.println("pattern,totalBytes,chunkSize,maxMemory,n,mbpsMean,mbpsCi95,mbpsCi95Pct,mbpsMin,mbpsMax,"
					+ "retainedHeap,accountedMemory,spillCount,spilledBytes,tempFileLength,fragments");
			calibrateBaseline();
			if (!"envelope".equals(phase)) {
				warmUpJit();
			}
			calibrateBaseline();
			if ("sweep".equals(phase)) {
				phaseSweep(payload, reps);
			} else if ("sizes".equals(phase)) {
				phaseSizes(reps);
			} else if ("cliff".equals(phase)) {
				phaseCliff(reps);
			} else if ("focus".equals(phase)) {
				phaseFocus();
			} else if ("envelope".equals(phase)) {
				phaseEnvelope();
			} else if ("concurrency".equals(phase)) {
				phaseConcurrency(opts.containsKey("threads") ? Integer.parseInt(opts.get("threads")) : 4);
			} else {
				throw new IllegalArgumentException("unknown phase: " + phase);
			}
		} finally {
			csv.close();
		}
		System.out.println("# wrote " + out.getAbsolutePath());
	}

	private static byte[] newSource(final int size) {
		final byte[] b = new byte[size];
		final Random random = new Random(42L);
		random.nextBytes(b);
		// A PDF content stream is mostly ASCII; keep it in range so nothing
		// downstream can behave differently on byte values.
		for (int i = 0; i < b.length; ++i) {
			b[i] = (byte) (32 + Math.abs(b[i] % 95));
		}
		return b;
	}
}
