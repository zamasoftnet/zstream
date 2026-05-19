package net.zamasoft.zstream.resolver.protocol.http;

import java.io.IOException;
import java.net.URI;

import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;

/**
 * A {@link SourceResolver} implementation that handles {@code http:} and
 * {@code https:} URIs with the JDK HTTP client.
 */
public class HTTPSourceResolver implements SourceResolver {
	@Override
	public Source resolve(final URI uri) throws IOException {
		return new HTTPSource(uri);
	}

	@Override
	public void release(final Source source) {
		if (source instanceof HTTPSource) {
			try {
				((HTTPSource) source).close();
			} catch (IOException e) {
				// ignore
			}
		}
	}
}
