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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.text.MessageFormat;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.contrib.rssdisplay.RssMacroParameters;
import org.xwiki.rendering.macro.MacroExecutionException;

import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.SyndFeedInput;
import com.sun.syndication.io.XmlReader;

/**
 * Factory implementation using Rome to return the feed's data.
 * <p>
 * Only http and https feeds hosted on public addresses are fetched, redirects included, and fetching a feed is
 * bounded in time and size.
 *
 * @version $Id$
 * @since 1.9
 */
public class DefaultRomeFeedFactory implements RomeFeedFactory
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRomeFeedFactory.class);

    /**
     * The maximum number of milliseconds to wait when connecting to, or waiting for data from, the RSS feed provider.
     */
    private static final int TIMEOUT_MILLISECONDS = 5000;

    /**
     * The maximum number of milliseconds that retrieving a whole feed can take, redirects included.
     */
    private static final long MAX_DURATION_MILLISECONDS = 10000;

    /**
     * The maximum size of a feed, in bytes.
     */
    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private static final int MAX_REDIRECTS = 5;

    private static final String USER_AGENT_HEADER = "User-Agent";

    private static final String VERSION = DefaultRomeFeedFactory.class.getPackage().getImplementationVersion();

    private static final String USER_AGENT = "XWiki/" + VERSION;

    private static final int IPV4_LENGTH = 4;

    private static final int CGNAT_FIRST_BYTE = 100;

    private static final int CGNAT_SECOND_BYTE_MASK = 0xC0;

    private static final int CGNAT_SECOND_BYTE = 0x40;

    private static final int UNIQUE_LOCAL_MASK = 0xFE;

    private static final int UNIQUE_LOCAL_PREFIX = 0xFC;

    private final int timeout;

    private final long maxDuration;

    private final long maxSize;

    /**
     * Uses the default timeouts and maximum feed size.
     */
    public DefaultRomeFeedFactory()
    {
        this(TIMEOUT_MILLISECONDS, MAX_DURATION_MILLISECONDS, MAX_SIZE_BYTES);
    }

    /**
     * @param timeout the maximum number of milliseconds to wait when connecting to, or waiting for data from, the
     *     feed provider
     * @param maxDuration the maximum number of milliseconds that retrieving a whole feed can take
     * @param maxSize the maximum size of a feed, in bytes
     */
    DefaultRomeFeedFactory(int timeout, long maxDuration, long maxSize)
    {
        this.timeout = timeout;
        this.maxDuration = maxDuration;
        this.maxSize = maxSize;
    }

    @Override
    public SyndFeed createFeed(RssMacroParameters parameters) throws MacroExecutionException
    {
        if (StringUtils.isEmpty(parameters.getFeed())) {
            throw new MacroExecutionException("The required 'feed' parameter is missing");
        }

        SyndFeedInput syndFeedInput = new SyndFeedInput();

        SyndFeed feed;
        try (InputStream feedStream = openFeedStream(parameters.getFeedURL())) {
            feed = syndFeedInput.build(new XmlReader(feedStream, true, parameters.getEncoding()));
        } catch (MacroExecutionException e) {
            throw e;
        } catch (Exception e) {
            // The details of the failure are only logged, the displayed error stays generic.
            LOGGER.debug("Failed to retrieve the feed from [{}]", parameters.getFeedURL(), e);
            throw new MacroExecutionException(MessageFormat.format("Failed to retrieve the feed from [{0}]",
                parameters.getFeedURL()));
        }
        if (feed == null) {
            throw new MacroExecutionException(MessageFormat.format("No feed found at [{0}]",
                parameters.getFeedURL()));
        }

        return feed;
    }

    private InputStream openFeedStream(URL feedURL) throws IOException, MacroExecutionException
    {
        long deadline = System.currentTimeMillis() + this.maxDuration;
        URL url = feedURL;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            checkDeadline(deadline);
            checkURL(url);

            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(this.timeout);
            connection.setReadTimeout(this.timeout);
            connection.setRequestProperty(USER_AGENT_HEADER, USER_AGENT);

            URL redirectURL = null;
            if (connection instanceof HttpURLConnection) {
                // Redirects are followed here rather than by the connection, so that their target is checked too.
                redirectURL = getRedirectURL((HttpURLConnection) connection);
            }
            if (redirectURL == null) {
                return new LimitedInputStream(connection.getInputStream(), deadline, this.maxSize);
            }
            url = redirectURL;
        }

        throw new MacroExecutionException(MessageFormat.format("Too many redirects when trying to reach [{0}]",
            feedURL));
    }

    private URL getRedirectURL(HttpURLConnection connection) throws IOException
    {
        connection.setInstanceFollowRedirects(false);
        int status = connection.getResponseCode();
        String location = connection.getHeaderField("Location");
        if (status >= HttpURLConnection.HTTP_MULT_CHOICE && status < HttpURLConnection.HTTP_BAD_REQUEST
            && location != null)
        {
            connection.disconnect();
            return new URL(connection.getURL(), location);
        }

        return null;
    }

    private static void checkDeadline(long deadline) throws IOException
    {
        if (System.currentTimeMillis() > deadline) {
            throw new IOException("Retrieving the feed took too long");
        }
    }

    /**
     * Makes sure the feed (or a redirect) URL can be fetched: only http and https URLs pointing to public addresses
     * are allowed.
     *
     * @param url the URL to check
     * @throws MacroExecutionException if the URL is not allowed
     * @throws IOException if the host of the URL can't be resolved
     */
    protected void checkURL(URL url) throws MacroExecutionException, IOException
    {
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new MacroExecutionException(MessageFormat.format(
                "Unsupported protocol for the feed URL [{0}], only http and https are allowed", url));
        }

        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (isForbiddenAddress(address)) {
                throw new MacroExecutionException(MessageFormat.format("Access to [{0}] is not allowed", url));
            }
        }
    }

    /**
     * @param address a resolved address of a feed (or redirect) URL
     * @return {@code true} if the address isn't a public one (loopback, private network, link-local, etc.)
     */
    protected boolean isForbiddenAddress(InetAddress address)
    {
        boolean forbidden =
            address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress();
        forbidden = forbidden || address.isSiteLocalAddress() || address.isMulticastAddress();

        return forbidden || isOtherNonPublicAddress(address.getAddress());
    }

    private static boolean isOtherNonPublicAddress(byte[] bytes)
    {
        boolean result;
        if (bytes.length == IPV4_LENGTH) {
            // 0.0.0.0/8 ("this network", which reaches the local host on Linux) and 100.64.0.0/10 (carrier-grade
            // NAT).
            result = bytes[0] == 0
                || (bytes[0] == CGNAT_FIRST_BYTE && (bytes[1] & CGNAT_SECOND_BYTE_MASK) == CGNAT_SECOND_BYTE);
        } else {
            // fc00::/7 (unique local addresses), which Inet6Address#isSiteLocalAddress doesn't cover.
            result = (bytes[0] & UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL_PREFIX;
        }

        return result;
    }

    /**
     * Fails the reading once the deadline is passed or more than the maximum number of bytes have been read.
     */
    private static final class LimitedInputStream extends FilterInputStream
    {
        private final long deadline;

        private long remaining;

        LimitedInputStream(InputStream in, long deadline, long maxSize)
        {
            super(in);
            this.deadline = deadline;
            this.remaining = maxSize;
        }

        @Override
        public int read() throws IOException
        {
            checkLimits();
            int result = super.read();
            if (result != -1) {
                this.remaining--;
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            checkLimits();
            // Reading one byte more than the remaining allowance is how exceeding the maximum size is detected.
            int count = super.read(b, off, (int) Math.min(len, this.remaining + 1));
            if (count > 0) {
                this.remaining -= count;
            }
            return count;
        }

        private void checkLimits() throws IOException
        {
            if (this.remaining < 0) {
                throw new IOException("The feed is too large");
            }
            checkDeadline(this.deadline);
        }
    }
}
