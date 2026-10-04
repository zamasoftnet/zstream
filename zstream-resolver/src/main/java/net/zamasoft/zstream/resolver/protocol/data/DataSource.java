package net.zamasoft.zstream.resolver.protocol.data;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringTokenizer;


import net.zamasoft.zstream.resolver.SourceValidity;
import net.zamasoft.zstream.resolver.util.AbstractSource;
import net.zamasoft.zstream.resolver.util.ValidSourceValidity;

/**
 * A {@link Source} implementation that decodes inline content embedded in a
 * <a href="https://datatracker.ietf.org/doc/html/rfc2397">RFC 2397</a>
 * {@code data:} URI.
 *
 * <p>The URI is parsed lazily on the first access.  Both plain URL-encoded data
 * and Base64-encoded data are supported.  Example URIs:
 * <pre>
 *   data:text/plain;charset=UTF-8,Hello%20World
 *   data:image/png;base64,iVBORw0KGgo...
 * </pre>
 *
 * <p>Because the data is fully held in memory this source is always valid
 * ({@link ValidSourceValidity#SHARED_INSTANCE}).
 *
 * @author MIYABE Tatsuhiko
 * @since 1.0
 */
public class DataSource extends AbstractSource {
	private String mimeType = null;
	private byte[] data = null;
	private String encoding = null;
	private boolean parsed = false;

	/**
	 * Constructs a {@code DataSource} for the given {@code data:} URI.
	 *
	 * @param uri a {@code data:} scheme URI; must not be {@code null}.
	 */
	public DataSource(URI uri) {
		super(uri);
	}

	/**
	 * Parses the URI once. The fields are set only when the whole URI has been
	 * decoded: setting {@code parsed} first (as before 2026-10-04) made a second
	 * call after a failure skip the parse and fail with a NullPointerException.
	 */
	private void parse() throws IOException {
		if (this.parsed) {
			return;
		}
		final String spec = this.uri.getRawSchemeSpecificPart();
		final int comma = spec.indexOf(',');
		if (comma == -1) {
			throw new IOException("No data in data: scheme");
		}
		String mimeType = null;
		String encoding = null;
		boolean base64 = false;
		for (StringTokenizer st = new StringTokenizer(spec.substring(0, comma), ";"); st.hasMoreElements();) {
			final String token = st.nextToken();
			if (mimeType == null) {
				if (token.indexOf('/') != -1) {
					mimeType = token;
					continue;
				}
				mimeType = "text/plain";
				encoding = "US-ASCII";
			}
			final int equal = token.indexOf('=');
			if (equal != -1) {
				if (token.substring(0, equal).equalsIgnoreCase("charset")) {
					encoding = token.substring(equal + 1);
				}
			} else if (token.equalsIgnoreCase("base64")) {
				base64 = true;
			}
		}
		final String dataStr = spec.substring(comma + 1);
		final byte[] data;
		if (base64) {
			// If base64 is further URL encoded, prevent + from being replaced by space
			final byte[] bytes = dataStr.indexOf('%') != -1
					? decodeUrl(dataStr.replace("+", "%2B").getBytes(StandardCharsets.ISO_8859_1))
					: dataStr.getBytes(StandardCharsets.ISO_8859_1);
			try {
				data = Base64.getMimeDecoder().decode(bytes);
			} catch (final IllegalArgumentException e) {
				throw new IOException("Invalid base64 in data: scheme", e);
			}
		} else {
			data = decodeUrl(dataStr.getBytes(StandardCharsets.ISO_8859_1));
		}
		this.mimeType = mimeType;
		this.encoding = encoding;
		this.data = data;
		this.parsed = true;
	}

	/**
	 * Returns the MIME type parsed from the {@code data:} URI header.
	 *
	 * @return the MIME type string, or {@code null} if the URI does not specify
	 *         one (defaults to {@code "text/plain"} per RFC 2397).
	 * @throws IOException if the URI cannot be parsed.
	 */
	@Override
	public String getMimeType() throws IOException {
		this.parse();
		return this.mimeType;
	}

	/**
	 * Returns the character encoding parsed from the {@code charset} parameter of
	 * the {@code data:} URI, or {@code null} if the content is binary or the
	 * encoding is not specified.
	 *
	 * @return the encoding name, or {@code null}.
	 * @throws IOException if the URI cannot be parsed.
	 */
	@Override
	public String getEncoding() throws IOException {
		this.parse();
		return this.encoding;
	}

	/**
	 * Returns {@code true} if the URI was successfully parsed and contains data.
	 *
	 * @return {@code true} if data is present.
	 * @throws IOException if the URI cannot be parsed.
	 */
	@Override
	public boolean exists() throws IOException {
		this.parse();
		return this.data != null;
	}

	/**
	 * Always returns {@code false} — {@code data:} sources are not file-backed.
	 *
	 * @return {@code false}.
	 * @throws IOException never thrown.
	 */
	@Override
	public boolean isFile() throws IOException {
		return false;
	}

	/**
	 * Always returns {@code true} — the decoded bytes are available as a stream.
	 *
	 * @return {@code true}.
	 * @throws IOException never thrown.
	 */
	@Override
	public boolean isInputStream() throws IOException {
		return true;
	}

	/**
	 * Returns {@code true} if a {@code charset} encoding was parsed from the URI,
	 * meaning the content can also be read as a character stream.
	 *
	 * @return {@code true} if a character encoding is known.
	 * @throws IOException if the URI cannot be parsed.
	 */
	@Override
	public boolean isReader() throws IOException {
		this.parse();
		return this.encoding != null;
	}

	/**
	 * Returns the decoded content as a {@link ByteArrayInputStream}. Each call
	 * returns a fresh stream positioned at the beginning.
	 *
	 * @return a new {@link ByteArrayInputStream} over the decoded data.
	 * @throws IOException if the URI cannot be parsed.
	 */
	@Override
	public synchronized InputStream getInputStream() throws IOException {
		this.parse();
		return new ByteArrayInputStream(this.data);
	}

	/**
	 * Returns the decoded content as a character {@link Reader} using the encoding
	 * parsed from the URI.
	 *
	 * @return a {@link Reader} over the decoded data.
	 * @throws UnsupportedOperationException if no character encoding is available
	 *                                        for this source.
	 * @throws IOException                    if the URI cannot be parsed.
	 */
	@Override
	public Reader getReader() throws IOException {
		if (!this.isReader()) {
			throw new UnsupportedOperationException();
		}
		return new InputStreamReader(this.getInputStream(), this.encoding);
	}

	/**
	 * Always throws {@link UnsupportedOperationException} — {@code data:} sources
	 * are never backed by a file.
	 *
	 * @throws UnsupportedOperationException always.
	 */
	@Override
	public File getFile() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the byte length of the decoded data.
	 *
	 * @return the number of decoded bytes.
	 * @throws IOException if the URI cannot be parsed.
	 */
	@Override
	public long getLength() throws IOException {
		this.parse();
		return this.data.length;
	}

	/**
	 * Returns {@link ValidSourceValidity#SHARED_INSTANCE} because in-memory data
	 * never changes.
	 *
	 * @return the shared always-valid validity instance.
	 * @throws IOException never thrown.
	 */
	@Override
	public SourceValidity getValidity() throws IOException {
		return ValidSourceValidity.SHARED_INSTANCE;
	}
	/**
	 * {@code application/x-www-form-urlencoded}系のデコードです
	 * (2026-08-01にcommons-codecの{@code URLCodec.decodeUrl}を置換——
	 * 同一意味論: {@code %XX}展開+{@code '+'}→空白。base64枝は呼び出し側が
	 * {@code '+'}を{@code %2B}へ事前エスケープして空白化を防ぐ従来の
	 * 挙動のまま)。不正なエスケープは{@link IOException}。
	 */
	private static byte[] decodeUrl(final byte[] bytes) throws IOException {
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(bytes.length);
		for (int i = 0; i < bytes.length; ++i) {
			final int b = bytes[i];
			if (b == '+') {
				out.write(' ');
			} else if (b == '%') {
				if (i + 2 >= bytes.length) {
					throw new IOException("Invalid URL encoding: incomplete escape sequence");
				}
				final int hi = Character.digit(bytes[i + 1], 16);
				final int lo = Character.digit(bytes[i + 2], 16);
				if (hi < 0 || lo < 0) {
					throw new IOException("Invalid URL encoding: not a hex digit");
				}
				out.write((hi << 4) + lo);
				i += 2;
			} else {
				out.write(b);
			}
		}
		return out.toByteArray();
	}

}
