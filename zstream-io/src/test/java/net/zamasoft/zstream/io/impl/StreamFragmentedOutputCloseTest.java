package net.zamasoft.zstream.io.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

/**
 * The target stream is closed even when writing the assembled fragments fails
 * (2026-10-04: a failing finish skipped the close, and the closed flag kept a
 * second close from reaching it).
 */
public class StreamFragmentedOutputCloseTest {
	private static final class FailingStream extends OutputStream {
		boolean closed = false;

		@Override
		public void write(final int b) throws IOException {
			throw new IOException("disk full");
		}

		@Override
		public void write(final byte[] b, final int off, final int len) throws IOException {
			throw new IOException("disk full");
		}

		@Override
		public void close() {
			this.closed = true;
		}
	}

	@Test
	public void testTargetIsClosedWhenFinishFails() throws Exception {
		final FailingStream target = new FailingStream();
		final StreamFragmentedOutput output = new StreamFragmentedOutput(target);
		output.addFragment();
		final byte[] data = { 1, 2, 3 };
		output.write(0, data, 0, data.length);
		assertThrows(IOException.class, output::close);
		assertTrue(target.closed);
	}
}
