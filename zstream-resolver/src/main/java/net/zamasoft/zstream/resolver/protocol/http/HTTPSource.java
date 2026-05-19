package net.zamasoft.zstream.resolver.protocol.http;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import net.zamasoft.zstream.resolver.SourceValidity;
import net.zamasoft.zstream.resolver.util.AbstractSource;

/**
 * HTTP/HTTPS source backed by the JDK HTTP client.
 */
public class HTTPSource extends AbstractSource {
	private URLConnection connection;
	private InputStream in;
	private String mimeType;
	private String encoding;
	private boolean exists;
	private long lastModified = -1;
	private long contentLength = -1;

	public HTTPSource(final URI uri) {
		super(uri);
	}

	@Override
	public String getMimeType() throws IOException {
		this.tryConnect();
		return this.mimeType;
	}

	@Override
	public String getEncoding() throws IOException {
		this.tryConnect();
		return this.encoding;
	}

	@Override
	public boolean exists() throws IOException {
		this.tryConnect();
		return this.exists;
	}

	@Override
	public boolean isInputStream() throws IOException {
		return true;
	}

	@Override
	public synchronized InputStream getInputStream() throws IOException {
		this.close();
		this.tryConnect();
		this.in = this.connection.getInputStream();
		if (this.in == null) {
			throw new FileNotFoundException();
		}
		return this.in;
	}

	@Override
	public boolean isReader() throws IOException {
		this.tryConnect();
		return this.encoding != null;
	}

	@Override
	public Reader getReader() throws IOException {
		this.tryConnect();
		if (this.encoding == null) {
			throw new UnsupportedOperationException("Encoding not set");
		}
		return new InputStreamReader(this.getInputStream(), this.encoding);
	}

	@Override
	public File getFile() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getLength() throws IOException {
		this.tryConnect();
		return this.contentLength;
	}

	@Override
	public SourceValidity getValidity() {
		return new HTTPSourceValidity(this.lastModified);
	}

	@Override
	public synchronized void close() throws IOException {
		if (this.in != null) {
			this.in.close();
		}
		this.in = null;
		if (this.connection instanceof HttpURLConnection) {
			((HttpURLConnection) this.connection).disconnect();
		}
		this.connection = null;
	}

	protected void tryConnect() throws IOException {
		if (this.connection != null) {
			return;
		}
		this.connection = this.uri.toURL().openConnection();
		if (this.connection instanceof HttpURLConnection) {
			HttpURLConnection http = (HttpURLConnection) this.connection;
			http.setRequestMethod("GET");
			this.exists = http.getResponseCode() != HttpURLConnection.HTTP_NOT_FOUND;
		} else {
			this.exists = true;
		}
		this.mimeType = this.connection.getContentType();
		this.encoding = parseCharset(this.mimeType);
		this.contentLength = this.connection.getContentLengthLong();
		this.lastModified = parseLastModified(this.connection.getHeaderField("Last-Modified"));
	}

	private static String parseCharset(String contentType) {
		if (contentType == null) {
			return null;
		}
		String[] parts = contentType.split(";");
		for (int i = 1; i < parts.length; ++i) {
			String part = parts[i].trim();
			int eq = part.indexOf('=');
			if (eq != -1 && part.substring(0, eq).trim().equalsIgnoreCase("charset")) {
				String charset = part.substring(eq + 1).trim();
				if (charset.length() >= 2 && charset.startsWith("\"") && charset.endsWith("\"")) {
					charset = charset.substring(1, charset.length() - 1);
				}
				try {
					if (!charset.equalsIgnoreCase("ISO-8859-1") && Charset.isSupported(charset)) {
						return charset;
					}
				} catch (Exception e) {
					return null;
				}
			}
		}
		return null;
	}

	private static long parseLastModified(String lastModified) {
		if (lastModified == null) {
			return -1;
		}
		try {
			return Date.from(ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
					.getTime();
		} catch (Exception e) {
			return -1;
		}
	}
}
