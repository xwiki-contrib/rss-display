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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.rssdisplay.RssMacroParameters;
import org.xwiki.rendering.macro.MacroExecutionException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.syndication.feed.synd.SyndFeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    private static final int TIMEOUT = 500;

    private static final long MAX_DURATION = 1500;

    private static final long MAX_SIZE = 64 * 1024;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private HttpServer server;

    private String baseURL;

    private byte[] customBody;

    private String receivedUserAgent;

    /**
     * Factory accepting the loopback address, where the test server listens, while still forbidding the others.
     */
    private final DefaultRomeFeedFactory factory = new DefaultRomeFeedFactory(TIMEOUT, MAX_DURATION, MAX_SIZE)
    {
        @Override
        protected boolean isForbiddenAddress(InetAddress address)
        {
            return !address.isLoopbackAddress() && super.isForbiddenAddress(address);
        }
    };

    @BeforeEach
    void setUp() throws Exception
    {
        byte[] feed = Files.readAllBytes(Paths.get("src/test/resources/feed1.xml"));

        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.setExecutor(this.executor);
        this.server.createContext("/feed", exchange -> respond(exchange, feed));
        this.server.createContext("/custom", exchange -> {
            this.receivedUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            respond(exchange, this.customBody);
        });
        this.server.createContext("/redirect", exchange -> redirect(exchange, this.baseURL + "/feed"));
        this.server.createContext("/redirectInternal", exchange -> redirect(exchange, "http://10.255.255.1/feed"));
        this.server.createContext("/redirectLoop", exchange -> redirect(exchange, this.baseURL + "/redirectLoop"));
        this.server.createContext("/hang", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            sleep(10000);
        });
        this.server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("<?xml version=\"1.0\"?>".getBytes());
                for (int i = 0; i < 1000; i++) {
                    out.write(' ');
                    out.flush();
                    sleep(TIMEOUT / 5);
                }
            }
        });
        this.server.createContext("/huge", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>".getBytes());
                // A valid feed, so that only the size limit makes reading it fail.
                byte[] title = new byte[(int) MAX_SIZE * 2];
                Arrays.fill(title, (byte) 'a');
                out.write(title);
                out.write("</title></channel></rss>".getBytes());
            }
        });
        this.server.start();

        this.baseURL = "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    @AfterEach
    void tearDown()
    {
        this.server.stop(0);
        this.executor.shutdownNow();
    }

    @Test
    void createFeed() throws Exception
    {
        SyndFeed feed = this.factory.createFeed(parameters(this.baseURL + "/feed"));

        assertEquals("Lift Off News", feed.getTitle());
    }

    @Test
    void createFeedSendsUserAgent() throws Exception
    {
        this.customBody = String.format(FEED_TEMPLATE, "Test Feed").getBytes(StandardCharsets.UTF_8);

        SyndFeed feed = this.factory.createFeed(parameters(this.baseURL + "/custom"));

        assertEquals("Test Feed", feed.getTitle());
        assertEquals(1, feed.getEntries().size());
        assertNotNull(this.receivedUserAgent);
        assertTrue(this.receivedUserAgent.startsWith("XWiki/"), this.receivedUserAgent);
    }

    @Test
    void createFeedWithEncoding() throws Exception
    {
        // The XML declaration doesn't specify the encoding, so the 'encoding' parameter is used to decode it.
        this.customBody = String.format(FEED_TEMPLATE, "Caf\u00e9").getBytes(StandardCharsets.ISO_8859_1);
        RssMacroParameters parameters = parameters(this.baseURL + "/custom");
        parameters.setEncoding("ISO-8859-1");

        assertEquals("Caf\u00e9", this.factory.createFeed(parameters).getTitle());
    }

    @Test
    void createFeedWhenFeedParameterIsMissing()
    {
        Throwable exception =
            assertThrows(MacroExecutionException.class, () -> this.factory.createFeed(new RssMacroParameters()));

        assertEquals("The required 'feed' parameter is missing", exception.getMessage());
    }

    @Test
    void createFeedWhenContentIsNotAFeed()
    {
        this.customBody = "not a feed".getBytes(StandardCharsets.UTF_8);

        MacroExecutionException exception = assertThrows(MacroExecutionException.class,
            () -> this.factory.createFeed(parameters(this.baseURL + "/custom")));

        assertEquals("Failed to retrieve the feed from [" + this.baseURL + "/custom]", exception.getMessage());
    }

    @Test
    void createFeedFollowsRedirect() throws Exception
    {
        SyndFeed feed = this.factory.createFeed(parameters(this.baseURL + "/redirect"));

        assertEquals("Lift Off News", feed.getTitle());
    }

    @Test
    void createFeedWithRedirectToInternalAddress()
    {
        MacroExecutionException exception = assertThrows(MacroExecutionException.class,
            () -> this.factory.createFeed(parameters(this.baseURL + "/redirectInternal")));

        assertEquals("Access to [http://10.255.255.1/feed] is not allowed", exception.getMessage());
    }

    @Test
    void createFeedWithRedirectLoop()
    {
        String url = this.baseURL + "/redirectLoop";
        MacroExecutionException exception =
            assertThrows(MacroExecutionException.class, () -> this.factory.createFeed(parameters(url)));

        assertEquals("Too many redirects when trying to reach [" + url + "]", exception.getMessage());
    }

    @Test
    void createFeedWithInternalAddress()
    {
        DefaultRomeFeedFactory defaultFactory = new DefaultRomeFeedFactory();
        for (String url : new String[] { this.baseURL + "/feed", "http://localhost/", "http://0.0.0.0/",
            "http://169.254.169.254/latest/meta-data/", "http://[::1]/" })
        {
            MacroExecutionException exception =
                assertThrows(MacroExecutionException.class, () -> defaultFactory.createFeed(parameters(url)));

            assertEquals("Access to [" + url + "] is not allowed", exception.getMessage());
        }
    }

    @Test
    void createFeedWithUnsupportedProtocol()
    {
        MacroExecutionException exception = assertThrows(MacroExecutionException.class,
            () -> this.factory.createFeed(parameters("file:///etc/passwd")));

        assertEquals("Unsupported protocol for the feed URL [file:/etc/passwd], only http and https are allowed",
            exception.getMessage());
    }

    @Test
    void isForbiddenAddress() throws Exception
    {
        DefaultRomeFeedFactory defaultFactory = new DefaultRomeFeedFactory();
        for (String address : new String[] { "127.0.0.1", "0.0.0.0", "0.1.2.3", "10.0.0.1", "172.16.0.1",
            "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.254", "224.0.0.1", "::", "::1", "fe80::1",
            "fd00::1", "fc00::1", "ff02::1", "::ffff:127.0.0.1" })
        {
            assertTrue(defaultFactory.isForbiddenAddress(InetAddress.getByName(address)), address);
        }
        for (String address : new String[] { "8.8.8.8", "100.63.255.255", "100.128.0.1", "2001:4860:4860::8888" }) {
            assertFalse(defaultFactory.isForbiddenAddress(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void createFeedWhenConnectionFails() throws Exception
    {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + closedPort + "/";

        MacroExecutionException exception =
            assertThrows(MacroExecutionException.class, () -> this.factory.createFeed(parameters(url)));

        // The cause of the failure (connection refused here) isn't part of the message.
        assertEquals("Failed to retrieve the feed from [" + url + "]", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void createFeedWhenServerHangs()
    {
        MacroExecutionException exception = assertTimeoutPreemptively(Duration.ofMillis(MAX_DURATION * 2),
            () -> assertThrows(MacroExecutionException.class,
                () -> this.factory.createFeed(parameters(this.baseURL + "/hang"))));

        assertEquals("Failed to retrieve the feed from [" + this.baseURL + "/hang]", exception.getMessage());
    }

    @Test
    void createFeedWhenServerTrickles()
    {
        // Each read gets data before the read timeout, so only the overall duration limit stops the fetch.
        MacroExecutionException exception = assertTimeoutPreemptively(
            Duration.ofMillis(MAX_DURATION + TIMEOUT * 2), () -> assertThrows(MacroExecutionException.class,
                () -> this.factory.createFeed(parameters(this.baseURL + "/trickle"))));

        assertEquals("Failed to retrieve the feed from [" + this.baseURL + "/trickle]", exception.getMessage());
    }

    @Test
    void createFeedWhenFeedIsTooLarge()
    {
        MacroExecutionException exception = assertThrows(MacroExecutionException.class,
            () -> this.factory.createFeed(parameters(this.baseURL + "/huge")));

        assertEquals("Failed to retrieve the feed from [" + this.baseURL + "/huge]", exception.getMessage());
    }

    private static RssMacroParameters parameters(String feed) throws Exception
    {
        RssMacroParameters parameters = new RssMacroParameters();
        parameters.setFeed(feed);
        return parameters;
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException
    {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException
    {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void sleep(long millis)
    {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
