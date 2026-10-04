package net.zamasoft.zstream.resolver.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * One suffix table for the file, URL and ZIP sources (2026-10-04: the ZIP copy
 * had lost {@code .md}).
 */
public class MimeTypesTest {
	@Test
	public void testSuffixes() {
		assertEquals("text/html", MimeTypes.fromFileName("a/index.HTML"));
		assertEquals("text/html", MimeTypes.fromFileName("page.htm"));
		assertEquals("text/xml", MimeTypes.fromFileName("chapter.xhtml"));
		assertEquals("text/markdown", MimeTypes.fromFileName("book/README.md"));
		assertEquals("text/markdown", MimeTypes.fromFileName("notes.markdown"));
		assertNull(MimeTypes.fromFileName("image.png"));
		assertNull(MimeTypes.fromFileName("Makefile"));
	}
}
