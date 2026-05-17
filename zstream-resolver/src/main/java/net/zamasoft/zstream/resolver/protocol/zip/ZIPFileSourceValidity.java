package net.zamasoft.zstream.resolver.protocol.zip;

import java.io.File;
import net.zamasoft.zstream.resolver.SourceValidity;

class ZIPFileSourceValidity implements SourceValidity {
    private final long timestamp;
    private final File file;

    ZIPFileSourceValidity(final long timestamp, final File file) {
        this.timestamp = timestamp;
        this.file = file;
    }

    @Override
    public Validity getValid() {
        return checkValidity(this.file);
    }

    @Override
    public Validity getValid(final SourceValidity validity) {
        if (validity instanceof ZIPFileSourceValidity) {
            return checkValidity(((ZIPFileSourceValidity) validity).file);
        }
        return Validity.UNKNOWN;
    }

    private Validity checkValidity(final File f) {
        if (f.lastModified() != this.timestamp) {
            return Validity.INVALID;
        }
        return Validity.VALID;
    }
}
