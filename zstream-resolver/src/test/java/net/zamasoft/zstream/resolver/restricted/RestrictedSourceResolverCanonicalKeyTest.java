package net.zamasoft.zstream.resolver.restricted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * Ensures that a rule of the access-control list holds for every spelling of a resource.
 *
 * <p>
 * Patterns and request URIs used to be compared as they were written, while the resolvers behind the list read
 * {@code FILE:///tmp/secret/a.css}, {@code file:///tmp/%73ecret/a.css} or {@code http://Host:80/} like the plain
 * form: an excluded resource was permitted under another spelling, and an included directory could be left through
 * an encoded {@code ..}. Both sides are now compared in one canonical form.
 * </p>
 */
public class RestrictedSourceResolverCanonicalKeyTest {
	private static RestrictedSourceResolver policy(final String exclude, final String include) {
		final RestrictedSourceResolver resolver = new RestrictedSourceResolver();
		if (exclude != null) {
			resolver.exclude(URI.create(exclude));
		}
		if (include != null) {
			resolver.include(URI.create(include));
		}
		return resolver;
	}

	private static void assertDenied(final RestrictedSourceResolver resolver, final String... uris) {
		for (final String uri : uris) {
			assertFalse(resolver.permits(URI.create(uri)), "permitted: " + uri);
		}
	}

	private static void assertPermitted(final RestrictedSourceResolver resolver, final String... uris) {
		for (final String uri : uris) {
			assertTrue(resolver.permits(URI.create(uri)), "denied: " + uri);
		}
	}

	@Test
	public void excludedFileIsExcludedInEverySpelling() {
		final RestrictedSourceResolver resolver = policy("file:///tmp/secret/**", "**");
		assertDenied(resolver, "file:///tmp/secret/a.css",
				// the scheme in another case
				"FILE:///tmp/secret/a.css",
				// percent-encoded once, and twice (the file resolver decodes what the URI class has decoded)
				"file:///tmp/%73ecret/a.css", "file:///tmp/%2573ecret/a.css",
				// an encoded slash
				"file:///tmp/secret%2Fa.css",
				// more slashes, fewer slashes, and the first directory in the place of the host
				"file:///tmp//secret/a.css", "file:////tmp/secret/a.css", "file:/tmp/secret/a.css",
				"file://tmp/secret/a.css",
				// dot segments, plain and encoded
				"file:///tmp/other/../secret/a.css", "file:///tmp/other/%2E%2E/secret/a.css",
				"file:///tmp/other/..%2Fsecret/a.css",
				// backslashes, which separate directories on Windows
				"file:///tmp/other%5C..%5Csecret%5Ca.css");
		assertPermitted(resolver, "file:///tmp/public/a.css", "file:///tmp/secrets.css");
	}

	@Test
	public void excludedHostIsExcludedInEverySpelling() {
		final RestrictedSourceResolver resolver = policy("http://intranet.example/**", "**");
		assertDenied(resolver, "http://intranet.example/x", "HTTP://intranet.example/x",
				"http://Intranet.Example/x", "http://intranet.example:80/x", "http://intranet.example./x",
				"http://user@intranet.example/x");
		// Another port is another service; only the default port is the same as none.
		assertPermitted(resolver, "http://intranet.example:8080/x", "http://public.example/x");

		final RestrictedSourceResolver https = policy("https://intranet.example/**", "**");
		assertDenied(https, "https://intranet.example:443/x", "HTTPS://INTRANET.EXAMPLE/x");
		assertPermitted(https, "https://intranet.example:80/x");
	}

	@Test
	public void includedDirectoryIsNotLeftThroughEncodedDotSegments() {
		final RestrictedSourceResolver files = policy(null, "file:///pub/**");
		assertPermitted(files, "file:///pub/a.css", "FILE:///pub/b/../a.css", "file:///pub/%61.css");
		assertDenied(files, "file:///pub/%2E%2E/etc/passwd", "file:///pub/..%2Fetc/passwd",
				"file:///pub/%252E%252E/etc/passwd", "file:///pub/x%5C..%5C..%5Cetc/passwd");

		final RestrictedSourceResolver http = policy(null, "http://example.com/pub/**");
		assertPermitted(http, "http://example.com/pub/a.css", "http://EXAMPLE.com:80/pub/a.css");
		assertDenied(http, "http://example.com/pub/%2E%2E/private/a.css",
				"http://example.com/pub/..%2Fprivate/a.css");
	}

	/** The host is the one the connection goes to, not text before an {@code @} that decodes to look like one. */
	@Test
	public void userInformationIsNotReadAsTheHost() {
		final RestrictedSourceResolver resolver = policy(null, "http://allowed.example/**");
		assertPermitted(resolver, "http://allowed.example/x", "http://user:password@allowed.example/x");
		assertDenied(resolver, "http://allowed.example%2F@evil.example/x",
				"http://allowed.example@evil.example/x");
	}

	@Test
	public void wildcardsAndEscapedStarsKeepTheirMeaning() {
		final RestrictedSourceResolver names = policy(null, "**/image.jpg");
		assertPermitted(names, "file:///x/y/image.jpg", "http://example.com/a/image.jpg");
		assertDenied(names, "file:///x/y/other.jpg");

		final RestrictedSourceResolver segment = policy(null, "https://example.com/*");
		assertPermitted(segment, "https://example.com/a.css");
		assertDenied(segment, "https://example.com/a/b.css");

		// A star written in a request is a character, and a pattern names it as %2A.
		final RestrictedSourceResolver star = policy(null, "http://example.com/a%2Ab");
		assertPermitted(star, "http://example.com/a*b", "http://example.com/a%2ab");
		assertDenied(star, "http://example.com/aXb");
	}

	@Test
	public void keyIsTheCanonicalForm() {
		assertEquals("file:/tmp/a b.css", RestrictedSourceResolver.toKey(URI.create("FILE:///tmp//x/../a%20b.css")));
		assertEquals("http://example.com/a/b?x=1",
				RestrictedSourceResolver.toKey(URI.create("HTTP://User@Example.COM.:80/a/./c/../b?x=1")));
		assertEquals("http://[::1]:8080/x", RestrictedSourceResolver.toKey(URI.create("http://[::1]:8080/x")));
		// Characters outside ASCII and their UTF-8 escapes are one spelling.
		assertEquals(RestrictedSourceResolver.toKey(URI.create("file:///tmp/%E7%A7%98%E5%AF%86/a.css")),
				RestrictedSourceResolver.toKey(URI.create("file:///tmp/秘密/a.css")));
		// A data: URI is content, not a location: only its scheme is put in lower case.
		assertEquals("data:text/plain,a%20b", RestrictedSourceResolver.toKey(URI.create("DATA:text/plain,a%20b")));
	}

	/** The decision of {@code resolve} is the one {@code permits} reports. */
	@Test
	public void resolveRefusesTheOtherSpelling() {
		final RestrictedSourceResolver resolver = policy("file:///tmp/secret/**", "**");
		assertThrows(SecurityException.class, () -> resolver.resolve(URI.create("FILE:///tmp/%73ecret/a.css")));
		// Permitted, and there is no resolver behind the list to fetch it.
		assertThrows(FileNotFoundException.class, () -> resolver.resolve(URI.create("file:///tmp/public/a.css")));
	}
}
