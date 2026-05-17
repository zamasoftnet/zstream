package net.zamasoft.zstream.resolver.protocol.http;

import net.zamasoft.zstream.resolver.SourceValidity;

class HTTPSourceValidity implements SourceValidity {
    private final long lastModified;

    HTTPSourceValidity(final long lastModified) {
        this.lastModified = lastModified;
    }

    @Override
    public Validity getValid() {
        return Validity.UNKNOWN;
    }

    @Override
    public Validity getValid(final SourceValidity validity) {
        if (this.lastModified == -1) {
            return Validity.UNKNOWN;
        }
        if (validity instanceof HTTPSourceValidity) {
            return this.lastModified == ((HTTPSourceValidity) validity).lastModified ? Validity.VALID : Validity.INVALID;
        }
        return Validity.UNKNOWN;
    }
}
