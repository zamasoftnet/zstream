package net.zamasoft.zstream.resolver.protocol.url;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import net.zamasoft.zstream.resolver.SourceValidity;

class URLSourceValidity implements SourceValidity {
    private final long timestamp;
    private final URL url;

    URLSourceValidity(final long timestamp, final URL url) {
        this.timestamp = timestamp;
        this.url = url;
    }

    @Override
    public Validity getValid() {
        return checkValidity(this.url);
    }

    @Override
    public Validity getValid(final SourceValidity validity) {
        if (validity instanceof URLSourceValidity) {
            return checkValidity(((URLSourceValidity) validity).url);
        }
        return Validity.UNKNOWN;
    }

    private Validity checkValidity(final URL u) {
        try {
            if ("file".equals(u.getProtocol())) {
                if (new java.io.File(u.toURI()).lastModified() != this.timestamp) {
                    return Validity.INVALID;
                }
                return Validity.VALID;
            }
            final URLConnection conn = u.openConnection();
            if (conn.getLastModified() != this.timestamp) {
                return Validity.INVALID;
            }
            return Validity.VALID;
        } catch (IOException e) {
            return Validity.UNKNOWN;
        } catch (java.net.URISyntaxException e) {
            return Validity.UNKNOWN;
        } catch (IllegalArgumentException e) {
            return Validity.UNKNOWN;
        }
    }
}
