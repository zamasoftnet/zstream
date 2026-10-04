package net.zamasoft.zstream.resolver.protocol.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * {@code data:} URIのデコード意味論の固定です(2026-08-01、
 * commons-codec {@code URLCodec.decodeUrl}のローカル置換に伴う
 * 挙動保存の証明)。
 */
public class DataSourceDecodeTest {

	private static byte[] read(final String uri) throws IOException {
		final DataSource source = new DataSource(URI.create(uri));
		try (InputStream in = source.getInputStream()) {
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[256];
			for (int n; (n = in.read(buffer)) != -1;) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	@Test
	public void testPercentDecoding() throws Exception {
		assertArrayEquals("Hello, World!".getBytes(StandardCharsets.US_ASCII),
				read("data:,Hello%2C%20World!"));
	}

	@Test
	public void testPlusBecomesSpaceInTextualData() throws Exception {
		// URLCodecの意味論(www-form): 非base64データでは'+'は空白
		assertArrayEquals("a b".getBytes(StandardCharsets.US_ASCII), read("data:,a+b"));
	}

	@Test
	public void testBase64Plain() throws Exception {
		// "AB+/" のbase64はエスケープなし——'+'はbase64字母として保存される
		assertArrayEquals(new byte[] { 0, 16, (byte) 0xBF }, read("data:application/octet-stream;base64,ABC/"));
	}

	@Test
	public void testBase64WithPercentEscapes() throws Exception {
		// %エスケープを含むbase64: '+'は%2Bへ事前エスケープされ空白化しない
		// (従来のURLCodec時代からの挙動)
		final byte[] viaEscaped = read("data:;base64,AB%2BF");
		final byte[] viaPlain = read("data:;base64,AB+F");
		assertArrayEquals(viaPlain, viaEscaped);
	}

	@Test
	public void testMimeTypeAndCharsetParsing() throws Exception {
		final DataSource source = new DataSource(URI.create("data:text/plain;charset=UTF-8,%E3%81%82"));
		assertArrayEquals("あ".getBytes(StandardCharsets.UTF_8), read("data:text/plain;charset=UTF-8,%E3%81%82"));
		assertEquals("text/plain", source.getMimeType());
	}

	// 不正な%エスケープはjava.net.URI自体が拒否するため、decodeUrlの
	// ガードには正規のURI経由では到達しない(旧URLCodec時代も同様)。
	// ガードは多層防御として残す

	/**
	 * 解析に失敗した URI は、何度問い合わせても同じ IOException になる(2026-10-04。それまで 1 回目の失敗で
	 * 解析済みの印だけが立ち、2 回目は NullPointerException になっていた)。不正な base64 も IOException にする。
	 */
	@Test
	public void testFailedParseFailsAgainTheSameWay() {
		for (final String uri : new String[] { "data:broken", "data:;base64,A" }) {
			final DataSource source = new DataSource(URI.create(uri));
			assertThrows(IOException.class, source::getInputStream, uri);
			assertThrows(IOException.class, source::getLength, uri);
			assertThrows(IOException.class, source::getMimeType, uri);
		}
	}
}
