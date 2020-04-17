// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.distiller;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Node;
import com.google.gwt.dom.client.NodeList;
import org.chromium.distiller.document.TextDocument;
import org.chromium.distiller.document.TextDocumentStatistics;
import org.chromium.distiller.extractors.ArticleExtractor;
import org.chromium.distiller.proto.DomDistillerProtos.StatisticsInfo;
import org.chromium.distiller.proto.DomDistillerProtos.TimingEntry;
import org.chromium.distiller.proto.DomDistillerProtos.TimingInfo;
import protobuf.*;
import org.chromium.distiller.webdocument.DomConverter;
import org.chromium.distiller.webdocument.WebDocument;
import org.chromium.distiller.webdocument.WebDocumentBuilder;
import org.chromium.distiller.webdocument.filters.LeadImageFinder;
import org.chromium.distiller.webdocument.filters.NestedElementRetainer;
import org.chromium.distiller.webdocument.filters.RelevantElements;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class ContentExtractor {
    private final Element documentElement;
    private final List<String> candidateTitles;
    private final TimingInfo mTimingInfo;
    private final StatisticsInfo mStatisticsInfo;
    private final MarkupParser parser;
    private List<String> imageUrls;
    private String textDirection;

    private class WebDocumentInfo {
        WebDocument document;
        Set<Node> hiddenElements;
    }

    public ContentExtractor(Element root) {
        documentElement = root;
        candidateTitles = new LinkedList<>();
        mTimingInfo = TimingInfo.create();
        mStatisticsInfo = StatisticsInfo.create();

        double startTime = DomUtil.getTime();
        parser = new MarkupParser(root, mTimingInfo);
        mTimingInfo.setMarkupParsingTime(DomUtil.getTime() - startTime);
        textDirection = "";
    }

    // Grabs a list of candidate titles in descending priority order:
    // 1) meta-information
    // 2) The document's title element, modified based on some readability heuristics
    // 3) The document's title element, if it's a string
    private void ensureTitleInitialized() {
        if (candidateTitles.size() > 0) return;

        String title = parser.getTitle();
        if (!title.isEmpty()) {
            candidateTitles.add(title);
        }
        candidateTitles.add(DocumentTitleGetter.getDocumentTitle(
                    Document.get().getTitle(), Document.get().getDocumentElement()));
        if (Document.get().getTitle().getClass() == String.class) {
            candidateTitles.add(Document.get().getTitle());
        }
    }

    public MarkupParser getMarkupParser() { return parser; }

    public String extractTitle() {
        ensureTitleInitialized();
        assert candidateTitles.size() > 0;
        return candidateTitles.get(0);
    }

    public String extractContent() {
        return extractContent(false);
    }

    public String extractContent(boolean textOnly) {
        double now = DomUtil.getTime();
        WebDocumentInfo documentInfo = createWebDocumentInfoFromPage();
        mTimingInfo.setDocumentConstructionTime(DomUtil.getTime() - now);

        now = DomUtil.getTime();
        processDocument(documentInfo.document);
        RelevantElements.process(documentInfo.document);
        LeadImageFinder.process(documentInfo.document);
        NestedElementRetainer.process(documentInfo.document);

        mTimingInfo.setArticleProcessingTime(DomUtil.getTime() - now);

        now = DomUtil.getTime();
        String html = documentInfo.document.generateOutput(textOnly);
        mTimingInfo.setFormattingTime(DomUtil.getTime() - now);

        imageUrls = documentInfo.document.getImageUrls();

        if (LogUtil.isLoggable(LogUtil.DEBUG_LEVEL_TIMING_INFO)) {
            for (int i = 0; i < mTimingInfo.getOtherTimesCount(); i++) {
                TimingEntry entry =  mTimingInfo.getOtherTimes(i);
                LogUtil.logToConsole("Timing: " + entry.getName() + " = " + entry.getTime());
            }

            LogUtil.logToConsole(
                    "Timing: MarkupParsingTime = " +
                    mTimingInfo.getMarkupParsingTime() +
                    "\nTiming: DocumentConstructionTime = " +
                    mTimingInfo.getDocumentConstructionTime() +
                    "\nTiming: ArticleProcessingTime = " +
                    mTimingInfo.getArticleProcessingTime() +
                    "\nTiming: FormattingTime = " +
                    mTimingInfo.getFormattingTime()
                    );
        }
        return html;
    }

    /**
     * Returns timing information about the most recent extraction run.
     * @return an instance of DomDistillerProtos.TimingInfo with detailed timing statistics.
     */
    public TimingInfo getTimingInfo() {
        return mTimingInfo;
    }

    /**
     * Returns statistical information about the most recent extraction run.
     * @return an instance of DomDistillerProtos.StatisticsInfo with detailed statistics.
     */
    public StatisticsInfo getStatisticsInfo() {
        return mStatisticsInfo;
    }

    /**
     * Get the page's text directionality ("ltr", "rtl", or "auto").
     * @return The page's text direction (default is "auto").
     */
    public String getTextDirection() {
        if (textDirection == null || textDirection.isEmpty()) {
            textDirection = "auto";
        }
        return textDirection;
    }

    /**
     * Get a list of the content image URLs in the provided document.
     * @return A list of image URLs.
     */
    public List<String> getImageUrls() {
        return imageUrls;
    }

    /**
     * Converts the original HTML page into a WebDocument for analysis.
     */
    private WebDocumentInfo createWebDocumentInfoFromPage() {
        WebDocumentInfo info = new WebDocumentInfo();
        WebDocumentBuilder documentBuilder = new WebDocumentBuilder();

        NodeList<Element> mobileViewport = DomUtil.querySelectorAll(documentElement,
                "meta[name=\"viewport\"][content*=\"width=device-width\"]");
        DomConverter converter = new DomConverter(documentBuilder);
        converter.setIsMobileFriendly(mobileViewport.getLength() > 0);

        Element walkerRoot = DomUtil.getArticleElement(documentElement);
        converter.setHasArticleElement(walkerRoot != null);

        if (walkerRoot != null) {
            if (LogUtil.isLoggable(LogUtil.DEBUG_LEVEL_BOILER_PIPE_PHASES)) {
                LogUtil.logToConsole("Extracted article element: " + walkerRoot);
            }
        } else {
            walkerRoot = documentElement;
        }
        new DomWalker(converter).walk(walkerRoot);
        info.document = documentBuilder.toWebDocument();
        ensureTitleInitialized();
        info.hiddenElements = converter.getHiddenElements();

        return info;
    }

    /**
     * Implements the actual analysis of the page content, identifying the core elements of the
     * page.
     *
     * @param document the WebDocument representation of the page extracted from the DOM.
     */
    private void processDocument(WebDocument document) {
        TextDocument textDocument = document.createTextDocumentView();
        ArticleExtractor.INSTANCE.process(textDocument, candidateTitles);
        mStatisticsInfo.setWordCount(TextDocumentStatistics.countWordsInContent(textDocument));
        textDocument.applyToModel();
    }
}
