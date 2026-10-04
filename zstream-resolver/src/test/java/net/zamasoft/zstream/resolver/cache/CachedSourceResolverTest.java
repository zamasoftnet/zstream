package net.zamasoft.zstream.resolver.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;

/**
 * 登録に失敗しても前の内容が残ることを固定します(2026-10-05)。それまでは、写す前に索引へ載せ、作る前に
 * 前のファイルを消していたので、失敗の後の {@code resolve} が途中までの内容や消えたファイルを返した。
 */
public class CachedSourceResolverTest {
	private static final URI URI_A = URI.create("http://example.com/a.css");

	private static Source source(final InputStream in) throws IOException {
		return new StreamSource(URI_A, in, "text/css", -1L);
	}

	private static byte[] read(final CachedSourceResolver resolver) throws IOException {
		final Source source = resolver.resolve(URI_A);
		try (InputStream in = source.getInputStream()) {
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[256];
			for (int n; (n = in.read(buffer)) != -1;) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		} finally {
			resolver.release(source);
		}
	}

	@Test
	public void aFailedCopyKeepsThePreviousEntry() throws Exception {
		final CachedSourceResolver resolver = new CachedSourceResolver();
		try {
			final byte[] first = "a { color: red }".getBytes(StandardCharsets.UTF_8);
			resolver.putSource(source(new ByteArrayInputStream(first)));
			final InputStream broken = new InputStream() {
				private int count;

				@Override
				public int read() throws IOException {
					if (++this.count > 4) {
						throw new IOException("connection reset");
					}
					return 'x';
				}
			};
			assertThrows(IOException.class, () -> resolver.putSource(source(broken)));
			assertArrayEquals(first, read(resolver));
		} finally {
			resolver.dispose();
		}
	}

	@Test
	public void replacingAnEntryDeletesTheOldFile() throws Exception {
		final CachedSourceResolver resolver = new CachedSourceResolver();
		try {
			final File first = resolver.putFile(source(new ByteArrayInputStream(new byte[0])));
			final File second = resolver.putFile(source(new ByteArrayInputStream(new byte[0])));
			assertFalse(first.exists());
			assertTrue(second.isFile());
			resolver.reset();
			assertFalse(second.exists());
		} finally {
			resolver.dispose();
		}
	}
}
