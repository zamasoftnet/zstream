package net.zamasoft.zstream.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.zamasoft.zstream.resolver.composite.CompositeSourceResolver;
import net.zamasoft.zstream.resolver.protocol.data.DataSource;
import net.zamasoft.zstream.resolver.protocol.data.DataSourceResolver;
import net.zamasoft.zstream.resolver.protocol.file.FileSource;
import net.zamasoft.zstream.resolver.protocol.file.FileSourceResolver;
import net.zamasoft.zstream.resolver.cache.CachedSource;
import net.zamasoft.zstream.resolver.cache.CachedSourceResolver;
import net.zamasoft.zstream.resolver.restricted.RestrictedSourceResolver;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;
import net.zamasoft.zstream.resolver.util.SourceWrapper;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;
import net.zamasoft.zstream.resolver.util.UnknownSourceValidity;
import net.zamasoft.zstream.resolver.util.ValidSourceValidity;
import net.zamasoft.zstream.resolver.protocol.url.URLSource;

class ResolverApiTest {
    @TempDir
    File tempDir;

    @Test
    void testDataSourceResolverDecodesTextAndBase64Uris() throws Exception {
        final DataSourceResolver resolver = new DataSourceResolver();

        try (final Source textSource = resolver.resolve(new java.net.URI("data:text/plain;charset=UTF-8,Hello%20World"))) {
            assertTrue(textSource instanceof DataSource);
            assertTrue(textSource.exists());
            assertFalse(textSource.isFile());
            assertTrue(textSource.isInputStream());
            assertTrue(textSource.isReader());
            assertEquals("text/plain", textSource.getMimeType());
            assertEquals("UTF-8", textSource.getEncoding());
            assertEquals("Hello World", readString(textSource.getInputStream()));
            assertEquals("Hello World", readAll(textSource.getReader()));
        }

        try (final Source base64Source = resolver.resolve(new java.net.URI("data:text/plain;base64,SGVsbG8rV29ybGQ="))) {
            assertEquals("Hello+World", readString(base64Source.getInputStream()));
            assertEquals(11L, base64Source.getLength());
        }
    }

    @Test
    void testRestrictedSourceResolverHonorsAclAndAllowsDataUris() throws Exception {
        final DataSourceResolver delegate = new DataSourceResolver();
        final RestrictedSourceResolver resolver = new RestrictedSourceResolver(delegate);
        resolver.exclude(new java.net.URI("https://example.com/private/*"));
        resolver.include(new java.net.URI("https://example.com/*"));

        try (final Source source = resolver.resolve(new java.net.URI("data:text/plain,inline"))) {
            assertEquals("inline", readString(source.getInputStream()));
        }

        assertThrows(SecurityException.class,
                () -> resolver.resolve(new java.net.URI("https://example.com/private/file.txt")));
        assertThrows(SecurityException.class,
                () -> resolver.resolve(new java.net.URI("https://other.example.com/open.txt")));
    }

    @Test
    void testRestrictedSourceResolverToKeyNormalizesHttpEncoding() throws Exception {
        final java.net.URI uri = new java.net.URI("https://example.com/a%20b?q=x+y%2Bz");
        final String key = RestrictedSourceResolver.toKey(uri);

        assertEquals("https://example.com/a b?q=x y+z", key);
    }

    @Test
    void testCompositeSourceResolverUsesDefaultSchemeAndDelegatesRelease() throws Exception {
        final AtomicBoolean released = new AtomicBoolean(false);
        final CompositeSourceResolver resolver = new CompositeSourceResolver();
        resolver.setDefaultScheme("file");
        resolver.setDefaultSourceResolver(new FileSourceResolver());
        resolver.addSourceResolver("memo", new SourceResolver() {
            @Override
            public Source resolve(final java.net.URI uri) {
                return new Source() {
                    @Override
                    public boolean exists() {
                        return true;
                    }

                    @Override
                    public boolean isInputStream() {
                        return true;
                    }

                    @Override
                    public java.io.InputStream getInputStream() {
                        return new java.io.ByteArrayInputStream("memo".getBytes(StandardCharsets.UTF_8));
                    }

                    @Override
                    public boolean isReader() {
                        return false;
                    }

                    @Override
                    public java.io.Reader getReader() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean isFile() {
                        return false;
                    }

                    @Override
                    public File getFile() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public SourceValidity getValidity() {
                        return ValidSourceValidity.SHARED_INSTANCE;
                    }

                    @Override
                    public java.net.URI getURI() {
                        return uri;
                    }

                    @Override
                    public String getMimeType() {
                        return "text/plain";
                    }

                    @Override
                    public String getEncoding() {
                        return "UTF-8";
                    }

                    @Override
                    public long getLength() {
                        return 4;
                    }

                    @Override
                    public void close() {
                        // No state.
                    }
                };
            }

            @Override
            public void release(final Source source) {
                released.set(true);
            }
        });

        final File file = new File(tempDir, "sample.xml");
        writeString(file, "<a/>");

        try (final Source fileSource = resolver.resolve(new java.net.URI(file.getName()))) {
            assertTrue(fileSource.isFile());
            assertEquals("text/xml", fileSource.getMimeType());
        }

        final Source memoSource = resolver.resolve(new java.net.URI("memo:anything"));
        resolver.release(memoSource);
        assertTrue(released.get(), "Release should be delegated to the scheme-specific resolver");
    }

    @Test
    void testFileSourceAndSourceWrapperDelegateMetadataAndContent() throws Exception {
        final File file = new File(tempDir, "hello.html");
        writeString(file, "<p>hello</p>");

        final FileSource source = new FileSource(file, "text/html", "UTF-8");
        final SourceWrapper wrapped = new SourceWrapper(source);

        assertTrue(wrapped.exists());
        assertTrue(wrapped.isFile());
        assertTrue(wrapped.isInputStream());
        assertTrue(wrapped.isReader());
        assertEquals(file, wrapped.getFile());
        assertEquals("text/html", wrapped.getMimeType());
        assertEquals("UTF-8", wrapped.getEncoding());
        assertEquals("<p>hello</p>", readString(wrapped.getInputStream()));
        assertEquals("<p>hello</p>", readAll(wrapped.getReader()));
        assertEquals(ValidSourceValidity.SHARED_INSTANCE.getValid(), wrapped.getValidity().getValid());
        wrapped.close();

        try (final Source resolved = new FileSourceResolver().resolve(file.toURI())) {
            assertEquals("text/html", resolved.getMimeType());
            assertEquals(file.length(), resolved.getLength());
        }
    }

    @Test
    void testCachedSourceResolverStoresResolvesAndResetsCachedFiles() throws Exception {
        final File cacheDir = new File(tempDir, "cache");
        assertTrue(cacheDir.mkdir());
        final CachedSourceResolver resolver = new CachedSourceResolver(cacheDir);
        final File file = new File(tempDir, "cached.txt");
        writeString(file, "cached-body");
        final FileSource source = new FileSource(file, "text/plain", "UTF-8");

        resolver.putSource(source);

        final Source resolved = resolver.resolve(file.toURI());
        assertTrue(resolved instanceof CachedSource);
        assertEquals("text/plain", resolved.getMimeType());
        assertEquals("UTF-8", resolved.getEncoding());
        assertEquals("cached-body", readString(resolved.getInputStream()));
        assertEquals(UnknownSourceValidity.SHARED_INSTANCE.getValid(), resolved.getValidity().getValid());

        resolver.release(resolved);
        resolver.reset();
        assertThrows(java.io.FileNotFoundException.class, () -> resolver.resolve(file.toURI()));
    }

    @Test
    void testCachedSourceResolverToKeyNormalizesHttpUris() throws Exception {
        final java.net.URI uri = new java.net.URI("https://example.com/a%20b?q=x+y%2Bz");
        assertEquals("https://example.com/a b?q=x y+z", CachedSourceResolver.toKey(uri));
    }

    @Test
    void testUrlSourceForFileUrisExposesDetectedMetadataAndCanReopenStreams() throws Exception {
        final File file = new File(tempDir, "page.html");
        writeString(file, "<html>hello</html>");
        final URLSource source = new URLSource(file.toURI(), null, "UTF-8");

        assertTrue(source.exists());
        assertTrue(source.isFile());
        assertTrue(source.isInputStream());
        assertTrue(source.isReader());
        assertEquals(file, source.getFile());
        assertEquals("text/html", source.getMimeType());
        assertEquals("UTF-8", source.getEncoding());
        assertEquals(file.length(), source.getLength());
        assertEquals("<html>hello</html>", readString(source.getInputStream()));
        assertEquals("<html>hello</html>", readString(source.getInputStream()));
        assertEquals("<html>hello</html>", readAll(source.getReader()));
        assertEquals(SourceValidity.Validity.VALID, source.getValidity().getValid(source.getValidity()));
        source.close();
    }

    @Test
    void testUrlSourceWithoutEncodingRejectsReaderAccess() throws Exception {
        final File file = new File(tempDir, "plain.txt");
        writeString(file, "body");
        final URLSource source = new URLSource(file.toURI());

        assertFalse(source.isReader());
        assertThrows(UnsupportedOperationException.class, source::getReader);
    }

    @Test
    void testStreamSourceBackedByInputStreamCanBeReadRepeatedly() throws Exception {
        final StreamSource source = new StreamSource(new java.net.URI("memo:stream"),
                new java.io.ByteArrayInputStream("stream-body".getBytes(StandardCharsets.UTF_8)), "text/plain",
                "UTF-8", 11);

        assertEquals("memo:stream", source.getURI().toString());
        assertEquals("text/plain", source.getMimeType());
        assertEquals("UTF-8", source.getEncoding());
        assertTrue(source.exists());
        assertTrue(source.isInputStream());
        assertTrue(source.isReader());
        assertEquals("stream-body", readString(source.getInputStream()));
        assertEquals("stream-body", readAll(source.getReader()));
        assertEquals(11L, source.getLength());
        assertEquals(SourceValidity.Validity.UNKNOWN, source.getValidity().getValid());
        assertThrows(UnsupportedOperationException.class, source::getFile);
    }

    @Test
    void testStreamSourceBackedByReaderCanBeResetAndDoesNotExposeInputStream() throws Exception {
        final StreamSource source = new StreamSource(new java.net.URI("memo:reader"), new StringReader("reader-body"),
                "text/plain", "UTF-8", 11);

        assertFalse(source.isInputStream());
        assertTrue(source.isReader());
        assertEquals("reader-body", readAll(source.getReader()));
        assertEquals("reader-body", readAll(source.getReader()));
        assertThrows(UnsupportedOperationException.class, source::getInputStream);
    }

    @Test
    void testSimpleSourceMetadataAndUnknownValidityExposeStableValues() throws Exception {
        final java.net.URI uri = new java.net.URI("memo:meta");
        final SimpleSourceMetadata metadata = new SimpleSourceMetadata(uri, "text/plain", "UTF-8", 123);
        final SimpleSourceMetadata uriOnlyMetadata = new SimpleSourceMetadata(uri);

        assertEquals(uri, metadata.getURI());
        assertEquals("text/plain", metadata.getMimeType());
        assertEquals("UTF-8", metadata.getEncoding());
        assertEquals(123L, metadata.getLength());

        assertEquals(uri, uriOnlyMetadata.getURI());
        assertEquals(null, uriOnlyMetadata.getMimeType());
        assertEquals(null, uriOnlyMetadata.getEncoding());
        assertEquals(-1L, uriOnlyMetadata.getLength());

        assertEquals(SourceValidity.Validity.UNKNOWN, UnknownSourceValidity.SHARED_INSTANCE.getValid());
        assertEquals(SourceValidity.Validity.UNKNOWN,
                UnknownSourceValidity.SHARED_INSTANCE.getValid(ValidSourceValidity.SHARED_INSTANCE));
    }

    private static String readAll(final java.io.Reader reader) throws Exception {
        try {
            final StringBuilder buffer = new StringBuilder();
            final char[] chars = new char[64];
            int read;
            while ((read = reader.read(chars)) != -1) {
                buffer.append(chars, 0, read);
            }
            return buffer.toString();
        } finally {
            reader.close();
        }
    }

    private static void writeString(final File file, final String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(final java.io.InputStream input) throws Exception {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }
}
