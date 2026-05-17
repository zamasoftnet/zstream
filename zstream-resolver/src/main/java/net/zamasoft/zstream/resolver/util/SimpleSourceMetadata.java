package net.zamasoft.zstream.resolver.util;

import java.net.URI;

import net.zamasoft.zstream.resolver.SourceMetadata;

/**
 * Simple implementation of SourceMetadata.
 *
 * @author MIYABE Tatsuhiko
 * @since 1.0
 */
public class SimpleSourceMetadata implements SourceMetadata {
    private final URI uri;
    private final String mimeType;
    private final String encoding;
    private final long length;

    public SimpleSourceMetadata(final URI uri, final String mimeType, final String encoding, final long length) {
        this.uri = uri;
        this.mimeType = mimeType;
        this.encoding = encoding;
        this.length = length;
    }

    public SimpleSourceMetadata(final URI uri) {
        this(uri, null, null, -1);
    }

    @Override
    public URI getURI() {
        return this.uri;
    }

    @Override
    public String getMimeType() {
        return this.mimeType;
    }

    @Override
    public String getEncoding() {
        return this.encoding;
    }

    @Override
    public long getLength() {
        return this.length;
    }
}
