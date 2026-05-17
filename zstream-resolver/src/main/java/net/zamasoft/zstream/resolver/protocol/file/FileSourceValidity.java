package net.zamasoft.zstream.resolver.protocol.file;

import java.io.File;
import net.zamasoft.zstream.resolver.SourceValidity;

class FileSourceValidity implements SourceValidity {
    private final long timestamp;
    private final File file;

    FileSourceValidity(final long timestamp, final File file) {
        this.timestamp = timestamp;
        this.file = file;
    }

    @Override
    public Validity getValid() {
        return this.checkValidity(this.file);
    }

    @Override
    public Validity getValid(final SourceValidity validity) {
        if (validity instanceof FileSourceValidity) {
            return this.checkValidity(((FileSourceValidity) validity).file);
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
