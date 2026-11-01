package net.zamasoft.zstream.io.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Which fragment goes to the temporary file when memory runs out, and that the output does not depend on it. The
 * largest fragment is the one to spill and the oldest among equals (2026-10-31: the choice moved from a scan over all
 * fragments to a sorted set, which must follow every change of a fragment's memory).
 */
public class TempFileOutputSpillTest {
	private static final int CHUNK = 8;

	private static byte[] fill(final int length, final int value) {
		final byte[] b = new byte[length];
		java.util.Arrays.fill(b, (byte) value);
		return b;
	}

	private static StreamFragmentedOutput output(final ByteArrayOutputStream out, final int fragments, final int chunks)
			throws Exception {
		final StreamFragmentedOutput output = new StreamFragmentedOutput(out,
				new AbstractTempFileOutput.Config(CHUNK, CHUNK * chunks));
		for (int i = 0; i < fragments; ++i) {
			output.addFragment();
		}
		return output;
	}

	@Test
	public void testTheLargestFragmentSpillsFirst() throws Exception {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (StreamFragmentedOutput output = output(out, 3, 4)) {
			output.write(0, fill(CHUNK, 'a'), 0, CHUNK);
			output.write(1, fill(CHUNK * 2, 'b'), 0, CHUNK * 2);
			output.write(2, fill(CHUNK, 'c'), 0, CHUNK);
			assertEquals(0, output.getSpillCount());
			output.write(0, fill(1, 'a'), 0, 1);
			assertEquals(CHUNK * 2, output.getSpilledBytes());
		}
		assertEquals("aaaaaaaaabbbbbbbbbbbbbbbbcccccccc", out.toString("ISO-8859-1"));
	}

	@Test
	public void testTheOldestSpillsFirstAmongEquals() throws Exception {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (StreamFragmentedOutput output = output(out, 4, 4)) {
			for (int i = 0; i < 4; ++i) {
				output.write(i, fill(CHUNK, '0' + i), 0, CHUNK);
			}
			output.addFragment();
			output.write(4, fill(1, '4'), 0, 1);
			assertEquals(CHUNK, output.getSpilledBytes());
			// Fragment 0 went to the file, so patching it must reach the file
			output.patch(0, 3, fill(1, 'x'), 0, 1);
		}
		assertEquals("000x000011111111222222223333333 34".replace(" ", ""), out.toString("ISO-8859-1"));
	}

	@Test
	public void testASpilledFragmentCountsAgainWhenItGrows() throws Exception {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (StreamFragmentedOutput output = output(out, 2, 3)) {
			output.write(0, fill(CHUNK * 3, 'a'), 0, CHUNK * 3);
			output.write(1, fill(1, 'b'), 0, 1);
			assertEquals(CHUNK * 3, output.getSpilledBytes());
			output.write(0, fill(1, 'a'), 0, 1);
			output.write(1, fill(CHUNK, 'b'), 0, CHUNK);
			output.write(0, fill(CHUNK, 'a'), 0, CHUNK);
			// Fragment 1 is the largest now, 2 chunks against 1 of the spilled fragment 0, and holds 9 bytes
			assertEquals(CHUNK * 3 + 9, output.getSpilledBytes());
		}
		assertEquals(new String(fill(CHUNK * 4 + 1, 'a'), "ISO-8859-1") + new String(fill(CHUNK + 1, 'b'), "ISO-8859-1"),
				out.toString("ISO-8859-1"));
	}

	@Test
	public void testReplaceBytesForgetsTheMemoryOfEveryFragment() throws Exception {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (StreamFragmentedOutput output = output(out, 3, 2)) {
			output.write(0, fill(CHUNK, 'a'), 0, CHUNK);
			output.write(1, fill(CHUNK, 'b'), 0, CHUNK);
			output.replaceBytes(fill(CHUNK * 2, 'r'));
			assertEquals(0, output.getSpillCount());
			output.write(2, fill(1, 's'), 0, 1);
			assertEquals(CHUNK * 2, output.getSpilledBytes());
		}
		assertEquals("rrrrrrrrrrrrrrrrs", out.toString("ISO-8859-1"));
	}

	@Test
	public void testTheOutputDoesNotDependOnTheBudget() throws Exception {
		final Random random = new Random(20261031);
		for (int run = 0; run < 20; ++run) {
			final int fragments = 1 + random.nextInt(200), writes = 1 + random.nextInt(4000);
			final List<ByteArrayOutputStream> model = new ArrayList<>();
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final long spilled;
			try (StreamFragmentedOutput output = output(out, fragments, 1 + random.nextInt(32))) {
				for (int i = 0; i < fragments; ++i) {
					model.add(new ByteArrayOutputStream());
				}
				for (int i = 0; i < writes; ++i) {
					final int id = random.nextInt(fragments);
					final byte[] b = new byte[random.nextInt(CHUNK * 3)];
					random.nextBytes(b);
					output.write(id, b, 0, b.length);
					model.get(id).write(b);
				}
				spilled = output.getSpilledBytes();
			}
			final ByteArrayOutputStream expected = new ByteArrayOutputStream();
			for (final ByteArrayOutputStream fragment : model) {
				fragment.writeTo(expected);
			}
			assertArrayEquals(expected.toByteArray(), out.toByteArray());
			assertTrue(spilled <= expected.size());
		}
	}
}
