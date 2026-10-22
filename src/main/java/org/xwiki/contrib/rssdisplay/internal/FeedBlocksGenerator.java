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

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.bridge.SkinAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.rssdisplay.RssMacroParameters;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.GroupBlock;
import org.xwiki.rendering.block.ImageBlock;
import org.xwiki.rendering.block.LinkBlock;
import org.xwiki.rendering.block.ParagraphBlock;
import org.xwiki.rendering.listener.reference.ResourceReference;
import org.xwiki.rendering.listener.reference.ResourceType;
import org.xwiki.rendering.parser.ParseException;
import org.xwiki.rendering.parser.Parser;

import com.sun.syndication.feed.synd.SyndContent;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;

/**
 * Generates the blocks displaying a feed: its title and its entries.
 *
 * @version $Id$
 * @since 15.7
 */
@Component(roles = FeedBlocksGenerator.class)
@Singleton
public class FeedBlocksGenerator
{
    /**
     * The name of the CSS class attribute.
     */
    private static final String CLASS_ATTRIBUTE = "class";

    /**
     * The relative skin path of the feed icon to be displayed in the channel title.
     */
    private static final String FEED_ICON_RESOURCE_PATH = "icons/silk/feed.png";

    /**
     * Used to get the RSS icon.
     */
    @Inject
    private SkinAccessBridge skinAccessBridge;

    /**
     * Needed to parse the ordinary text.
     */
    @Inject
    @Named("plain/1.0")
    private Parser plainTextParser;

    /**
     * Needed to clean the content.
     */
    @Inject
    private FeedItemContentCleaner contentCleaner;

    /**
     * Renders the RSS's title.
     * 
     * @param cssClass the CSS sheet
     * @param feed the RSS feed data
     * @return the list of blocks making the RSS Box title
     */
    public List<Block> generateBoxTitle(String cssClass, SyndFeed feed)
    {
        List<Block> titleBlocks;
        String title = getTitle(feed.getTitleEx(), feed.getTitle());

        if (feed.getLink() == null) {
            titleBlocks = parsePlainText(title);
        } else {
            // Title link.
            ResourceReference titleResourceReference = new ResourceReference(feed.getLink(), ResourceType.URL);

            // Title text link.
            Block titleTextLinkBlock = new LinkBlock(parsePlainText(title), titleResourceReference, true);

            // Rss icon.
            String imagePath = this.skinAccessBridge.getSkinFile(FEED_ICON_RESOURCE_PATH);
            ImageBlock imageBlock = new ImageBlock(new ResourceReference(imagePath, ResourceType.URL), false);

            // Title rss icon link.
            Block titleImageLinkBlock = new LinkBlock(Arrays.asList(imageBlock), titleResourceReference, true);

            titleBlocks = Arrays.asList(titleTextLinkBlock, titleImageLinkBlock);
        }
        ParagraphBlock titleBlock = new ParagraphBlock(titleBlocks);
        titleBlock.setParameter(CLASS_ATTRIBUTE, cssClass);

        return Collections.singletonList(titleBlock);
    }

    /**
     * Renders the given RSS's entries.
     * 
     * @param parentBlock the parent Block to which the output is going to be added
     * @param feed the RSS Channel we retrieved via the Feed URL
     * @param parameters our parameter helper object
     */
    public void generateEntries(Block parentBlock, SyndFeed feed, RssMacroParameters parameters)
    {
        int maxElements = parameters.getCount();
        int count = 0;

        for (Object item : feed.getEntries()) {
            ++count;
            if (count > maxElements) {
                break;
            }
            SyndEntry entry = (SyndEntry) item;

            ResourceReference titleResourceReference = new ResourceReference(entry.getLink(), ResourceType.URL);
            Block titleBlock = new LinkBlock(parsePlainText(getTitle(entry.getTitleEx(), entry.getTitle())),
                titleResourceReference, true);
            ParagraphBlock paragraphTitleBlock = new ParagraphBlock(Collections.singletonList(titleBlock));
            paragraphTitleBlock.setParameter(CLASS_ATTRIBUTE, "rssitemtitle");
            parentBlock.addChild(paragraphTitleBlock);

            String content = parameters.isContent() ? getEntryContent(entry) : null;
            if (content != null) {
                // We are wrapping the feed entry content in a HTML macro, not considering what the declared content
                // is, because some feed will declare text while they actually contain HTML.
                // See http://stuffthathappens.com/blog/2007/10/29/i-hate-rss/
                // A case where doing this might hurt is if a feed declares "text" and has any XML inside it does
                // not want to be interpreted as such, but displayed as is instead. But this certainly is too rare
                // compared to mis-formed feeds that say text while they want to say HTML.
                parentBlock.addChild(
                    new GroupBlock(
                        Collections.singletonList(this.contentCleaner.cleanContent(content)),
                        Collections.singletonMap(CLASS_ATTRIBUTE, "rssitemdescription")
                    )
                );
            }
        }
    }

    /**
     * Atom titles can be HTML (type {@code html} or {@code xhtml}), in which case their markup must not be displayed.
     *
     * @param titleEx the title, with its type, or {@code null} if the feed doesn't provide it
     * @param title the title
     * @return the text of the title to display
     */
    private String getTitle(SyndContent titleEx, String title)
    {
        if (titleEx != null && titleEx.getValue() != null
            && StringUtils.containsIgnoreCase(titleEx.getType(), "html"))
        {
            return this.contentCleaner.toPlainText(titleEx.getValue());
        }
        return title;
    }

    /**
     * Many feeds only provide the entry content, without description: Atom entries having a {@code content} element
     * but no {@code summary} one, and RSS items having a {@code content:encoded} element but no {@code description}
     * one.
     *
     * @param entry the feed entry
     * @return the entry description if it isn't blank, otherwise its first non-blank content, or {@code null} when
     *     the entry has neither
     */
    private String getEntryContent(SyndEntry entry)
    {
        SyndContent description = entry.getDescription();
        if (description != null && StringUtils.isNotBlank(description.getValue())) {
            return description.getValue();
        }
        for (Object item : entry.getContents()) {
            String value = ((SyndContent) item).getValue();
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Convenience method to not have to handle exceptions in several places.
     *
     * @param content the content to parse as plain text
     * @return the parsed Blocks
     */
    private List<Block> parsePlainText(String content)
    {
        if (StringUtils.isEmpty(content)) {
            return Collections.emptyList();
        }

        try {
            return this.plainTextParser.parse(new StringReader(content)).getChildren().get(0).getChildren();
        } catch (ParseException e) {
            // This shouldn't happen since the parser cannot throw an exception since the source is a memory
            // String.
            throw new RuntimeException("Failed to parse [" + content + "] as plain text", e);
        }
    }
}
