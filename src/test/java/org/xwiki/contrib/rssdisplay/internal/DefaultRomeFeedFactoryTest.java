/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.rssdisplay.internal;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.rssdisplay.RssMacroParameters;
import org.xwiki.rendering.macro.MacroExecutionException;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.syndication.feed.synd.SyndFeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DefaultRomeFeedFactory}.
 *
 * @version $Id$
 */
class DefaultRomeFeedFactoryTest
{
    private static final String FEED_TEMPLATE = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
        + "<title>%s</title><link>http://localhost/</link><description>Test</description>"
        + "<item><title>Item1</title><link>http://localhost/item1</link></item></channel></rss>";

    private final DefaultRomeFeedFactory factory = new DefaultRomeFeedFactory();

    private HttpServer server;

    private HttpHandler handler;

    private String receivedUserAgent;

    @BeforeEach
    void setUp() throws Exception
    {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/feed", exchange -> this.handler.handle(exchange));
        this.server.start();
    }

    @AfterEach
    void tearDown()
    {
        this.server.stop(0);
    }

    private void respondWith(byte[] body)
    {
        this.handler = exchange -> {
            this.receivedUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        };
    }

    private RssMacroParameters parameters() throws Exception
    {
        RssMacroParameters parameters = new RssMacroParameters();
        parameters.setFeed(String.format("http://localhost:%d/feed", this.server.getAddress().getPort()));
        return parameters;
    }

    @Test
    void createFeed() throws Exception
    {
        respondWith(String.format(FEED_TEMPLATE, "Test Feed").getBytes(StandardCharsets.UTF_8));

        SyndFeed feed = this.factory.createFeed(parameters());

        assertEquals("Test Feed", feed.getTitle());
        assertEquals(1, feed.getEntries().size());
        assertNotNull(this.receivedUserAgent);
        assertTrue(this.receivedUserAgent.startsWith("XWiki/"), this.receivedUserAgent);
    }

    @Test
    void createFeedWithEncoding() throws Exception
    {
        // The XML declaration doesn't specify the encoding, so the 'encoding' parameter is used to decode it.
        respondWith(String.format(FEED_TEMPLATE, "Café").getBytes(StandardCharsets.ISO_8859_1));
        RssMacroParameters parameters = parameters();
        parameters.setEncoding("ISO-8859-1");

        assertEquals("Café", this.factory.createFeed(parameters).getTitle());
    }

    @Test
    void createFeedWhenFeedParameterIsMissing()
    {
        Throwable exception =
            assertThrows(MacroExecutionException.class, () -> this.factory.createFeed(new RssMacroParameters()));

        assertEquals("The required 'feed' parameter is missing", exception.getMessage());
    }

    @Test
    void createFeedWhenContentIsNotAFeed() throws Exception
    {
        respondWith("not a feed".getBytes(StandardCharsets.UTF_8));
        RssMacroParameters parameters = parameters();

        Throwable exception =
            assertThrows(MacroExecutionException.class, () -> this.factory.createFeed(parameters));

        assertTrue(exception.getMessage().startsWith("Error processing [" + parameters.getFeedURL() + "] : "),
            exception.getMessage());
        assertNotNull(exception.getCause());
    }
}
