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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.GroupBlock;
import org.xwiki.rendering.listener.reference.ResourceReference;
import org.xwiki.rendering.listener.reference.ResourceType;
import org.xwiki.rendering.macro.MacroExecutionException;
import org.xwiki.rendering.macro.box.AbstractBoxMacro;
import org.xwiki.rendering.macro.descriptor.DefaultContentDescriptor;
import org.xwiki.contrib.rssdisplay.RssMacroParameters;
import org.xwiki.rendering.transformation.MacroTransformationContext;

import com.sun.syndication.feed.synd.SyndFeed;

/**
 * Macro that output latest feed entries from a RSS feed.
 * 
 * @version $Id$
 * @since 1.8RC1
 */
@Component
@Named("rss")
@Singleton
public class RssMacro extends AbstractBoxMacro<RssMacroParameters>
{
    /**
     * The name of the CSS class attribute.
     */
    private static final String CLASS_ATTRIBUTE = "class";

    private static final String FEED_CLASS_VALUE = "rssfeed";

    private static final String FEED_PROPERTY = "RssMacro.feed";

    /**
     * The description of the macro.
     */
    private static final String DESCRIPTION = "Output latest feed entries from a RSS feed.";

    /**
     * Needed to keep the feed information.
     */
    @Inject
    private Execution execution;

    /**
     * Generates the blocks displaying the feed.
     */
    @Inject
    private FeedBlocksGenerator feedBlocksGenerator;

    /**
     * Create a Feed object from a feed specified as a URL.
     */
    private RomeFeedFactory romeFeedFactory = new DefaultRomeFeedFactory();

    /**
     * Create and initialize the descriptor of the macro.
     */
    public RssMacro()
    {
        super("RSS", DESCRIPTION, new DefaultContentDescriptor(DESCRIPTION, false), RssMacroParameters.class);
        setDefaultCategories(Set.of(DEFAULT_CATEGORY_CONTENT));
    }

    @Override
    public boolean supportsInlineMode()
    {
        return false;
    }

    /**
     * Allow to save the current processed feed in the context, to be able to retrieve without concurrency issue.
     * @param feed the feed to save in the current context.
     */
    private void setFeed(SyndFeed feed)
    {
        this.execution.getContext().setProperty(FEED_PROPERTY, feed);
    }

    /**
     * Retrieve the feed of the current context.
     * @return the feed that is being processed in the macro.
     */
    private SyndFeed getFeed()
    {
        return (SyndFeed) this.execution.getContext().getProperty(FEED_PROPERTY);
    }

    /**
     * Remove the feed information from the current context.
     */
    private void removeContextFeed()
    {
        this.execution.getContext().removeProperty(FEED_PROPERTY);
    }

    @Override
    protected ResourceReference getImageReference(RssMacroParameters parameters, String content,
        MacroTransformationContext context)
    {
        if (parameters.isImage() && getFeed().getImage() != null) {
            return new ResourceReference(getFeed().getImage().getUrl(), ResourceType.URL);
        } else {
            return super.getImageReference(parameters, content, context);
        }
    }

    @Override
    protected List<? extends Block> getBlockTitle(RssMacroParameters parameters, String content,
        MacroTransformationContext context)
    {
        List<? extends Block> blockTitle = super.getBlockTitle(parameters, content, context);

        if (blockTitle == null) {
            return this.feedBlocksGenerator.generateBoxTitle("rsschanneltitle", getFeed());
        } else {
            return blockTitle;
        }
    }

    @Override
    protected String getClassProperty()
    {
        return super.getClassProperty() + ' ' + FEED_CLASS_VALUE;
    }

    @Override
    public List<Block> execute(RssMacroParameters parameters, String content, MacroTransformationContext context)
        throws MacroExecutionException
    {
        List<Block> result;
        SyndFeed feed = this.romeFeedFactory.createFeed(parameters);

        // we save the feed in the current context to be able to retrieve it
        // we avoid to put it in a class field, to avoid concurrent exceptions
        this.setFeed(feed);

        try {
            if (parameters.isDecoration()) {
                result = super.execute(parameters, content == null ? StringUtils.EMPTY : content, context);
            } else {
                result =
                    Arrays.<Block>asList(new GroupBlock(Collections.singletonMap(CLASS_ATTRIBUTE, FEED_CLASS_VALUE)));
            }

            this.feedBlocksGenerator.generateEntries(result.get(0), feed, parameters);
        } finally {
            // clean the context, even when the rendering fails
            this.removeContextFeed();
        }
        return result;
    }

    @Override
    protected List<Block> parseContent(RssMacroParameters parameters, String content,
        MacroTransformationContext context) throws MacroExecutionException
    {
        return this.getMacroContentParser().parse(content, context, false, context.isInline()).getChildren();
    }

    /**
     * @param romeFeedFactory a custom implementation to use instead of the default, useful for tests
     */
    protected void setFeedFactory(RomeFeedFactory romeFeedFactory)
    {
        this.romeFeedFactory = romeFeedFactory;
    }
}
