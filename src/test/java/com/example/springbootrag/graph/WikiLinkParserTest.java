package com.example.springbootrag.graph;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WikiLinkParserTest {

    private final WikiLinkParser parser = new WikiLinkParser();

    @Test
    void extractsCrossPageLinksAsDocIds() {
        String md = "See [Data](/Data-Migration) and [Arch](/Confluence-Imports/Data-Architecture-Overview).";
        assertThat(parser.outboundDocIds(md))
                .containsExactly("Data-Migration", "Data-Architecture-Overview");
    }

    @Test
    void ignoresAnchorAndAttachmentAndExternalLinks() {
        String md = "[toc](#Section-One) [img](/.attachments/pic.png) [ext](https://example.com/x)";
        assertThat(parser.outboundDocIds(md)).isEmpty();
    }

    @Test
    void deduplicatesRepeatedTargets() {
        String md = "[a](/Same-Page) then [b](/Same-Page)";
        assertThat(parser.outboundDocIds(md)).containsExactly("Same-Page");
    }
}
