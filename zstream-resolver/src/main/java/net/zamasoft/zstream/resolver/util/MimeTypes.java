package net.zamasoft.zstream.resolver.util;

import java.util.Locale;

/**
 * Guesses the MIME type of a document from its file name.
 *
 * <p>
 * The file, URL and ZIP sources had their own copies of this table, and the ZIP
 * copy had lost the Markdown suffixes (2026-10-04).
 * </p>
 *
 * @since 1.0
 */
public final class MimeTypes {
	private MimeTypes() {
	}

	/**
	 * Returns the MIME type for the suffix of the given file name:
	 * {@code text/html} for {@code .html}/{@code .htm}, {@code text/xml} for
	 * {@code .xml}/{@code .xhtml}/{@code .xht}, {@code text/markdown} for
	 * {@code .md}/{@code .markdown}, and {@code null} otherwise.
	 *
	 * @param name a file name or path; must not be {@code null}.
	 * @return the MIME type, or {@code null} if the suffix is not known.
	 */
	public static String fromFileName(final String name) {
		final int dot = name.lastIndexOf('.');
		if (dot == -1) {
			return null;
		}
		final String suffix = name.substring(dot).toLowerCase(Locale.ROOT);
		if (".html".equals(suffix) || ".htm".equals(suffix)) {
			return "text/html";
		}
		if (".xml".equals(suffix) || ".xhtml".equals(suffix) || ".xht".equals(suffix)) {
			return "text/xml";
		}
		if (".md".equals(suffix) || ".markdown".equals(suffix)) {
			return "text/markdown";
		}
		return null;
	}
}
