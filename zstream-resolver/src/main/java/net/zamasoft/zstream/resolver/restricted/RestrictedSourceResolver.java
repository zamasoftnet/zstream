package net.zamasoft.zstream.resolver.restricted;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;

/**
 * A {@link SourceResolver} decorator that enforces URI-based access control
 * using wildcard patterns.
 *
 * <p>Patterns are evaluated in the order they were added via {@link #include}
 * and {@link #exclude}.  The first pattern that matches the request URI
 * determines whether access is granted or denied.  If no pattern matches, and
 * the scheme is not {@code data:}, access is denied by default.
 *
 * <p>{@code data:} URIs are always permitted regardless of the ACL because
 * they carry their content inline and cannot be used for server-side request
 * forgery.
 *
 * <p>Patterns and request URIs are compared in a canonical form (see
 * {@link #toKey(URI)}): the case of the scheme and the host, a default port,
 * {@code %xx} sequences, repeated slashes and {@code .}/{@code ..} segments
 * do not make two keys out of one resource.  Names that only the system
 * behind the resolver knows to be the same (another host name for one
 * address, a symbolic link, a file system that ignores case) cannot be told
 * apart from the URI; a rule that must hold against such names is an
 * {@link #include} rule, which permits only what it names.
 *
 * @author MIYABE Tatsuhiko
 * @since 1.0
 */
public class RestrictedSourceResolver implements SourceResolver {
	private SourceResolver enclosedSourceResolver;
	private final List<Pattern> acl = new ArrayList<>();

	/**
	 * Internal entry pairing an access-control decision with the compiled
	 * wildcard pattern it applies to.
	 *
	 * @param permit  {@code true} for an include/allow rule; {@code false} for
	 *                an exclude/deny rule.
	 * @param pattern the compiled wildcard pattern produced by
	 *                {@link WildcardHelper#compilePattern(String)}.
	 */
	protected static class Pattern {
		final boolean permit;
		final int[] pattern;

		Pattern(final boolean permit, final int[] pattern) {
			this.permit = permit;
			this.pattern = pattern;
		}
	}

	/**
	 * Constructs a {@code RestrictedSourceResolver} wrapping the given delegate.
	 *
	 * @param enclosedSourceResolver the delegate resolver to forward permitted
	 *                               requests to, or {@code null} if no delegate
	 *                               has been set yet.
	 */
	public RestrictedSourceResolver(SourceResolver enclosedSourceResolver) {
		this.enclosedSourceResolver = enclosedSourceResolver;
	}

	/**
	 * Constructs a {@code RestrictedSourceResolver} with no delegate.  The
	 * delegate must be set via
	 * {@link #setEnclosedSourceResolver(SourceResolver)} before use.
	 */
	public RestrictedSourceResolver() {
		this(null);
	}

	/** Returns the value of a hexadecimal digit, or -1 if the character is not one. */
	private static int hexValue(char c) {
		if ((c >= '0') && (c <= '9'))
			return c - '0';
		if ((c >= 'a') && (c <= 'f'))
			return c - 'a' + 10;
		if ((c >= 'A') && (c <= 'F'))
			return c - 'A' + 10;
		return -1;
	}

	/**
	 * Converts the given URI to the canonical string key used for pattern
	 * matching.  Patterns and request URIs go through the same conversion, so
	 * that two spellings of one resource have one key and a rule cannot be
	 * stepped around by writing the URI differently:
	 * <ul>
	 * <li>the scheme and the host are lower-cased, the default port of
	 * {@code http:} and {@code https:}, a trailing dot of the host and the user
	 * information are dropped;</li>
	 * <li>characters outside ASCII and their UTF-8 percent-encoded form are the
	 * same;</li>
	 * <li>percent-encoded characters are decoded, for every scheme, until nothing
	 * decodable is left (a resolver may decode more than once), except for
	 * {@code *}, {@code ?}, {@code #} and, in the query string, {@code *},
	 * {@code &}, {@code =}, {@code #}, which keep their encoded form in upper
	 * case; in {@code http:} and {@code https:} URIs {@code +} is a space;</li>
	 * <li>in the decoded path, runs of slashes are one slash and {@code .} and
	 * {@code ..} segments are resolved;</li>
	 * <li>for {@code file:} everything up to the fragment is the path, because
	 * that is how the file resolver reads it ({@code file://tmp/x} is
	 * {@code /tmp/x}), and a backslash is a separator.</li>
	 * </ul>
	 * {@code data:} URIs are only given a lower-case scheme: they locate
	 * nothing.
	 *
	 * @param uri the URI to convert; must not be {@code null}.
	 * @return the normalised key string; never {@code null}.
	 */
	public static String toKey(URI uri) {
		// Characters outside ASCII are compared in their escaped form: the two spellings then decode alike.
		final String text = uri.toASCIIString();
		final String scheme = uri.getScheme();
		final StringBuilder key = new StringBuilder(text.length());
		String rest = text;
		String lower = null;
		if (scheme != null) {
			lower = scheme.toLowerCase(Locale.ROOT);
			rest = text.substring(scheme.length() + 1);
			key.append(lower).append(':');
			if (lower.equals("data")) {
				// The URI is its own content, and it may be megabytes long.
				return key.append(rest).toString();
			}
		}
		final boolean http = "http".equals(lower) || "https".equals(lower);
		final boolean file = "file".equals(lower);
		if (!file && rest.startsWith("//")) {
			int end = 2;
			while (end < rest.length() && "/?#".indexOf(rest.charAt(end)) == -1) {
				++end;
			}
			key.append("//").append(canonicalAuthority(rest.substring(2, end), lower));
			rest = rest.substring(end);
		}
		rest = decode(rest, http, !file);
		// The path ends at the query or the fragment. Neither character is produced by decoding.
		int end = rest.indexOf('#');
		if (!file) {
			final int query = rest.indexOf('?');
			if (query != -1 && (end == -1 || query < end)) {
				end = query;
			}
		}
		if (end == -1) {
			end = rest.length();
		}
		key.append(canonicalPath(rest.substring(0, end), file)).append(rest, end, rest.length());
		return key.toString();
	}

	/**
	 * Returns the host and the port of an authority in their canonical form:
	 * in lower case, without the user information (it selects credentials, not a
	 * resource), without a trailing dot of the host and without the default port
	 * of the scheme.
	 */
	private static String canonicalAuthority(String authority, String scheme) {
		String host = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
		String port = "";
		// A colon inside the brackets of an IPv6 literal is not the port separator.
		final int colon = host.lastIndexOf(':');
		if (colon > host.lastIndexOf(']')) {
			port = host.substring(colon);
			host = host.substring(0, colon);
			if (port.length() == 1 || ("http".equals(scheme) && port.equals(":80"))
					|| ("https".equals(scheme) && port.equals(":443"))) {
				port = "";
			}
		}
		if (host.length() > 1 && host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		return host + port;
	}

	/**
	 * Decodes percent-encoded characters until nothing decodable is left.  One
	 * pass is not enough: a resolver that decodes what it is given once more
	 * (the file resolver does) would see a name the key did not show.
	 *
	 * @param plusIsSpace whether a {@code +} written in the text is a space.
	 * @param query       whether a {@code ?} starts a query string, where the
	 *                    delimiters {@code &} and {@code =} keep their
	 *                    encoded form.
	 */
	private static String decode(String text, boolean plusIsSpace, boolean query) {
		if (text.indexOf('%') == -1 && !(plusIsSpace && text.indexOf('+') != -1)) {
			return text;
		}
		String decoded = decodeOnce(text, plusIsSpace, query);
		// Every pass that changes the text shortens it or puts an encoded form in upper case, so this ends.
		for (;;) {
			final String again = decodeOnce(decoded, false, query);
			if (again.equals(decoded)) {
				return decoded;
			}
			decoded = again;
		}
	}

	private static String decodeOnce(String text, boolean plusIsSpace, boolean query) {
		// Characters that keep their encoded form: decoded, they would read as a wildcard or a delimiter.
		String keep = "*?#";
		final int len = text.length();
		final StringBuilder out = new StringBuilder(len);
		for (int i = 0; i < len; ++i) {
			char c = text.charAt(i);
			if (c == '?' && query) {
				keep = "*&=#";
			} else if (c == '+' && plusIsSpace) {
				c = ' ';
			} else if (c == '%' && i + 2 < len && hexValue(text.charAt(i + 1)) != -1
					&& hexValue(text.charAt(i + 2)) != -1) {
				final char value = (char) ((hexValue(text.charAt(i + 1)) << 4) | hexValue(text.charAt(i + 2)));
				if (keep.indexOf(value) == -1) {
					c = value;
				} else {
					out.append('%').append(Character.toUpperCase(text.charAt(i + 1)));
					c = Character.toUpperCase(text.charAt(i + 2));
				}
				i += 2;
			}
			out.append(c);
		}
		return out.toString();
	}

	/**
	 * Returns a decoded path with runs of slashes reduced to one slash and its
	 * {@code .} and {@code ..} segments resolved.  This is done after decoding:
	 * an encoded dot or slash is a dot or a slash to the resolver, and a
	 * {@code ..} after an empty segment leaves the directory before it, which is
	 * how file systems read the path.
	 *
	 * @param file whether the path names a local file, where a backslash can be
	 *             a separator.
	 */
	private static String canonicalPath(String path, boolean file) {
		if (file) {
			path = path.replace('\\', '/');
		}
		final boolean absolute = path.startsWith("/");
		final String[] parts = path.split("/", -1);
		final List<String> segments = new ArrayList<>(parts.length);
		for (String part : parts) {
			if (part.isEmpty() || part.equals(".")) {
				continue;
			}
			if (part.equals("..")) {
				final int last = segments.size() - 1;
				if (last >= 0 && !segments.get(last).equals("..")) {
					segments.remove(last);
				} else if (!absolute) {
					segments.add(part);
				}
				continue;
			}
			segments.add(part);
		}
		final String tail = parts[parts.length - 1];
		final StringBuilder out = new StringBuilder(path.length());
		if (absolute) {
			out.append('/');
		}
		for (int i = 0; i < segments.size(); ++i) {
			if (i > 0) {
				out.append('/');
			}
			out.append(segments.get(i));
		}
		// A path that ends in a slash, or in a dot segment, names a directory.
		if (!segments.isEmpty() && (tail.isEmpty() || tail.equals(".") || tail.equals(".."))) {
			out.append('/');
		}
		return out.toString();
	}

	/**
	 * Adds a permit (allow) rule for URIs matching the given wildcard pattern.
	 * The pattern is normalised before compilation, and {@code *} is interpreted
	 * as a single-segment wildcard.
	 *
	 * @param uriPattern the URI pattern to allow; must not be {@code null}.
	 */
	public void include(URI uriPattern) {
		String key = toKey(uriPattern.normalize());
		this.acl.add(new Pattern(true, WildcardHelper.compilePattern(key)));
	}

	/**
	 * Adds a deny rule for URIs matching the given wildcard pattern.
	 *
	 * @param uriPattern the URI pattern to deny; must not be {@code null}.
	 */
	public void exclude(URI uriPattern) {
		String key = toKey(uriPattern.normalize());
		this.acl.add(new Pattern(false, WildcardHelper.compilePattern(key)));
	}

	/**
	 * Evaluates the ACL for the given URI without resolving it.  Mirrors the
	 * decision {@link #resolve(URI, boolean) resolve(uri, false)} would make:
	 * the first matching pattern decides; with no match only {@code data:} is
	 * permitted.
	 *
	 * @param uri the URI to check; must not be {@code null}.
	 * @return {@code true} if the ACL permits the URI.
	 */
	public boolean permits(URI uri) {
		uri = uri.normalize();
		String key = toKey(uri);
		key = key.replaceAll("\\*", "%2A");
		for (Pattern pattern : this.acl) {
			if (WildcardHelper.match(key, pattern.pattern)) {
				return pattern.permit;
			}
		}
		return "data".equalsIgnoreCase(uri.getScheme());
	}

	/**
	 * Resolves the given URI after checking the ACL.  Equivalent to calling
	 * {@link #resolve(URI, boolean) resolve(uri, false)}.
	 *
	 * @param uri the URI to resolve; must not be {@code null}.
	 * @return a {@link Source} for the resource.
	 * @throws SecurityException     if the URI is denied by the ACL.
	 * @throws FileNotFoundException if no delegate resolver is configured.
	 * @throws IOException           if an I/O error occurs.
	 */
	@Override
	public Source resolve(URI uri) throws IOException {
		return this.resolve(uri, false);
	}

	/**
	 * Resolves the given URI, optionally bypassing the ACL check.
	 *
	 * @param uri   the URI to resolve; must not be {@code null}.
	 * @param force {@code true} to skip ACL evaluation and delegate directly.
	 * @return a {@link Source} for the resource.
	 * @throws SecurityException     if {@code force} is {@code false} and the URI
	 *                               is denied by the ACL.
	 * @throws FileNotFoundException if no delegate resolver is configured.
	 * @throws IOException           if an I/O error occurs.
	 */
	public Source resolve(URI uri, boolean force) throws IOException {
		uri = uri.normalize();
		String key = toKey(uri);
		key = key.replaceAll("\\*", "%2A");
		if (force) {
			if (this.enclosedSourceResolver == null) {
				throw new FileNotFoundException(key);
			}
			return this.enclosedSourceResolver.resolve(uri);
		}
		for (Pattern pattern : this.acl) {
			if (WildcardHelper.match(key, pattern.pattern)) {
				if (pattern.permit) {
					if (this.enclosedSourceResolver == null) {
						throw new FileNotFoundException(key);
					}
					return this.enclosedSourceResolver.resolve(uri);
				}
				throw new SecurityException("Access denied: " + key);
			}
		}
		if ("data".equalsIgnoreCase(uri.getScheme())) {
			if (this.enclosedSourceResolver == null) {
				throw new FileNotFoundException(key);
			}
			return this.enclosedSourceResolver.resolve(uri);
		}
		throw new SecurityException("Access denied: " + key);
	}

	/**
	 * Delegates release of the source to the enclosed resolver, if one is set.
	 *
	 * @param source the source to release; must not be {@code null}.
	 */
	@Override
	public void release(Source source) {
		if (this.enclosedSourceResolver != null) {
			this.enclosedSourceResolver.release(source);
		}
	}

	/**
	 * Returns the delegate {@link SourceResolver} to which permitted requests are
	 * forwarded.
	 *
	 * @return the enclosed resolver, or {@code null} if none has been set.
	 */
	public SourceResolver getEnclosedSourceResolver() {
		return this.enclosedSourceResolver;
	}

	/**
	 * Sets the delegate {@link SourceResolver} to which permitted requests are
	 * forwarded.
	 *
	 * @param enclosedSourceResolver the delegate resolver, or {@code null} to
	 *                               clear it.
	 */
	public void setEnclosedSourceResolver(SourceResolver enclosedSourceResolver) {
		this.enclosedSourceResolver = enclosedSourceResolver;
	}

	/**
	 * Clears both the enclosed resolver and all ACL patterns, restoring this
	 * instance to its initial state.
	 */
	public void reset() {
		this.enclosedSourceResolver = null;
		this.acl.clear();
	}
}
