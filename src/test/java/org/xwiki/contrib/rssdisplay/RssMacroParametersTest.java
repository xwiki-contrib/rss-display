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
package org.xwiki.contrib.rssdisplay;

import java.net.MalformedURLException;

import org.junit.jupiter.api.Test;
import org.xwiki.rendering.macro.parameter.MacroParameterException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RssMacroParameters}.
 *
 * @version $Id$
 */
class RssMacroParametersTest
{
    @Test
    void defaults()
    {
        RssMacroParameters parameters = new RssMacroParameters();

        assertNull(parameters.getFeed());
        assertNull(parameters.getFeedURL());
        assertEquals(10, parameters.getCount());
        assertFalse(parameters.isContent());
        assertFalse(parameters.isImage());
        assertTrue(parameters.isDecoration());
        assertEquals("", parameters.getWidth());
        assertNull(parameters.getEncoding());
    }

    @Test
    void setFeed() throws Exception
    {
        RssMacroParameters parameters = new RssMacroParameters();
        parameters.setFeed("https://www.xwiki.org/feed");

        assertEquals("https://www.xwiki.org/feed", parameters.getFeed());
        assertEquals("https://www.xwiki.org/feed", parameters.getFeedURL().toString());
    }

    @Test
    void setFeedWhenMalformedURL()
    {
        RssMacroParameters parameters = new RssMacroParameters();

        Throwable exception = assertThrows(MacroParameterException.class, () -> parameters.setFeed("notaurl"));

        assertEquals("Malformed feed URL", exception.getMessage());
        assertInstanceOf(MalformedURLException.class, exception.getCause());
    }
}
