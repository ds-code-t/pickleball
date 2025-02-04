/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.gherkin;

import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;

import static io.cucumber.gherkin.GherkinLanguageConstants.COMMENT_PREFIX;
import static io.cucumber.gherkin.GherkinLanguageConstants.TAG_PREFIX;
import static io.cucumber.gherkin.StringUtils.ltrim;
import static io.cucumber.gherkin.StringUtils.ltrimKeepNewLines;
import static io.cucumber.gherkin.StringUtils.rtrim;
import static io.cucumber.gherkin.StringUtils.rtrimKeepNewLines;
import static io.cucumber.gherkin.StringUtils.symbolCount;
import static io.cucumber.gherkin.StringUtils.trim;

class GherkinLine implements IGherkinLine {
    // TODO: set this to 0 when/if we change to 0-indexed columns
    private static final int OFFSET = 1;
    private final String lineText;
    private final String trimmedLineText;
    private final int indent;
    private final int line;

    public GherkinLine(String lineText, int line) {
        this.lineText = lineText;
        this.trimmedLineText = trim(lineText);
        this.line = line;
        indent = symbolCount(lineText) - symbolCount(ltrim(lineText));
    }

    @Override
    public int indent() {
        return indent;
    }

    @Override
    public void detach() {

    }

    @Override
    public String getLineText(int indentToRemove) {
        if (indentToRemove < 0 || indentToRemove > indent())
            return trimmedLineText;
        return lineText.substring(indentToRemove);
    }

    @Override
    public boolean isEmpty() {
        return trimmedLineText.length() == 0;
    }

    @Override
    public boolean startsWith(String prefix) {
        return trimmedLineText.startsWith(prefix);
    }

    @Override
    public String getRestTrimmed(int length) {
        return trimmedLineText.substring(length).trim();
    }

    @Override
    public List<GherkinLineSpan> getTags() {

        String uncommentedLine = trimmedLineText.split("\\s" + COMMENT_PREFIX, 2)[0];
        List<GherkinLineSpan> tags = new ArrayList<>();
        int indexInUncommentedLine = 0;

        String[] elements = uncommentedLine.split(TAG_PREFIX);
        for (String element : elements) {
            String token = rtrim(element);
            if (token.isEmpty()) {
                continue;
            }
            int symbolLength = uncommentedLine.codePointCount(0, indexInUncommentedLine);
            int column = indent() + symbolLength + 1;
            if (!token.matches("^\\S+$")) {
                throw new ParserException("A tag may not contain whitespace", new Location(line, column));
            }
            tags.add(new GherkinLineSpan(column, TAG_PREFIX + token));
            indexInUncommentedLine += element.length() + 1;
        }
        return tags;
    }

    @Override
    public List<GherkinLineSpan> getTableCells() {
        List<GherkinLineSpan> lineSpans = new ArrayList<>();
        StringBuilder cellBuilder = new StringBuilder();
        boolean beforeFirst = true;
        int col = 0;
        int cellStart = 0;
        boolean escape = false;
        PrimitiveIterator.OfInt iterator = lineText.codePoints().iterator();
        while (iterator.hasNext()) {
            int c = iterator.next();
            if (escape) {
                switch (c) {
                    case 'n':
                        cellBuilder.append('\n');
                        break;
                    case '\\':
                        cellBuilder.append('\\');
                        break;
                    case '|':
                        cellBuilder.append('|');
                        break;
                    default:
                        // Invalid escape. We'll just ignore it.
                        cellBuilder.append("\\");
                        cellBuilder.appendCodePoint(c);
                        break;
                }
                escape = false;
            } else {
                if (c == '\\') {
                    escape = true;
                } else if (c == '|') {
                    if (beforeFirst) {
                        // Skip the first empty span
                        beforeFirst = false;
                    } else {
                        String cell = cellBuilder.toString();
                        String leftTrimmedCell = ltrimKeepNewLines(cell);
                        int cellIndent = symbolCount(cell) - symbolCount(leftTrimmedCell);
                        lineSpans.add(new GherkinLineSpan(cellStart + cellIndent + OFFSET, rtrimKeepNewLines(leftTrimmedCell)));
                    }
                    cellBuilder = new StringBuilder();
                    cellStart = col + 1;
                } else {
                    cellBuilder.appendCodePoint(c);
                }
            }
            col++;
        }
        return lineSpans;
    }

    @Override
    public boolean startsWithTitleKeyword(String text) {
        int textLength = text.length();
        return trimmedLineText.length() > textLength &&
                trimmedLineText.startsWith(text) &&
                trimmedLineText.substring(textLength, textLength + GherkinLanguageConstants.TITLE_KEYWORD_SEPARATOR.length())
                        .equals(GherkinLanguageConstants.TITLE_KEYWORD_SEPARATOR);
    }

}
