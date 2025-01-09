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

import io.cucumber.messages.IdGenerator;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Comment;
import io.cucumber.messages.types.DataTable;
import io.cucumber.messages.types.DocString;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import static io.cucumber.gherkin.Parser.Builder;
import static io.cucumber.gherkin.Parser.RuleType;
import static io.cucumber.gherkin.Parser.TokenType;

class GherkinDocumentBuilder implements Builder<GherkinDocument> {
    private final List<Comment> comments = new ArrayList<>();
    private final IdGenerator idGenerator;
    private String uri;

    private Deque<AstNode> stack;

    public GherkinDocumentBuilder(IdGenerator idGenerator, String uri) {
        this.idGenerator = idGenerator;
        reset(uri);
    }

    @Override
    public void reset(String uri) {
        this.uri = uri;
        stack = new ArrayDeque<>();
        stack.push(new AstNode(RuleType.None));
    }

    private AstNode currentNode() {
        return stack.peek();
    }

    @Override
    public void build(Token token) {
        RuleType ruleType = RuleType.cast(token.matchedType);
        if (token.matchedType == TokenType.Comment) {
            Comment comment = new Comment(getLocation(token, 0), token.matchedText);
            comments.add(comment);
        } else {
            currentNode().add(ruleType, token);
        }
    }

    @Override
    public void startRule(RuleType ruleType) {
        stack.push(new AstNode(ruleType));
    }

    @Override
    public void endRule(RuleType ruleType) {
        AstNode node = stack.pop();
        Object transformedNode = getTransformedNode(node);
        currentNode().add(node.ruleType, transformedNode);
    }

    private Object getTransformedNode(AstNode node) {
        switch (node.ruleType) {
            case Step: {
                Token stepLine = node.getToken(TokenType.StepLine);
                return new Step(
                        getLocation(stepLine, 0),
                        stepLine.matchedKeyword,
                        stepLine.keywordType,
                        stepLine.matchedText,
                        node.getSingle(RuleType.DocString, null),
                        node.getSingle(RuleType.DataTable, null),
                        idGenerator.newId()
                );
            }
            case DocString: {
                Token separatorToken = node.getTokens(TokenType.DocStringSeparator).get(0);
                String mediaType = separatorToken.matchedText.length() > 0 ? separatorToken.matchedText : null;
                List<Token> lineTokens = node.getTokens(TokenType.Other);
                StringBuilder content = new StringBuilder();
                boolean newLine = false;
                for (Token lineToken : lineTokens) {
                    if (newLine) content.append("\n");
                    newLine = true;
                    content.append(lineToken.matchedText);
                }

                return new DocString(
                        getLocation(separatorToken, 0),
                        mediaType,
                        content.toString(),
                        separatorToken.matchedKeyword
                );
            }
            case DataTable: {
                List<TableRow> rows = getTableRows(node);
                return new DataTable(rows.get(0).getLocation(), rows);
            }
            case Background: {
                Token backgroundLine = node.getToken(TokenType.BackgroundLine);
                return new Background(
                        getLocation(backgroundLine, 0),
                        backgroundLine.matchedKeyword,
                        backgroundLine.matchedText,
                        getDescription(node),
                        getSteps(node),
                        idGenerator.newId()
                );
            }
            case ScenarioDefinition: {
                AstNode scenarioNode = node.getSingle(RuleType.Scenario, null);
                Token scenarioLine = scenarioNode.getToken(TokenType.ScenarioLine);

                return new Scenario(
                        getLocation(scenarioLine, 0),
                        getTags(node),
                        scenarioLine.matchedKeyword,
                        scenarioLine.matchedText,
                        getDescription(scenarioNode),
                        getSteps(scenarioNode),
                        scenarioNode.getItems(RuleType.ExamplesDefinition),
                        idGenerator.newId()
                );
            }
            case ExamplesDefinition: {
                AstNode examplesNode = node.getSingle(RuleType.Examples, null);
                Token examplesLine = examplesNode.getToken(TokenType.ExamplesLine);
                List<TableRow> rows = examplesNode.getSingle(RuleType.ExamplesTable, null);
                TableRow tableHeader = rows != null && !rows.isEmpty() ? rows.get(0) : null;
                List<TableRow> tableBody = rows != null && !rows.isEmpty() ? rows.subList(1, rows.size()) : Collections.emptyList();

                return new Examples(
                        getLocation(examplesLine, 0),
                        getTags(node),
                        examplesLine.matchedKeyword,
                        examplesLine.matchedText,
                        getDescription(examplesNode),
                        tableHeader,
                        tableBody,
                        idGenerator.newId()

                );
            }
            case ExamplesTable: {
                return getTableRows(node);
            }
            case Description: {
                List<Token> lineTokens = node.getTokens(TokenType.Other);
                // Trim trailing empty lines
                int end = lineTokens.size();
                while (end > 0 && lineTokens.get(end - 1).matchedText.matches("\\s*")) {
                    end--;
                }
                lineTokens = lineTokens.subList(0, end);

                return lineTokens.stream()
                        .map(t -> t.matchedText)
                        .collect(Collectors.joining("\n"));
            }
            case Feature: {
                AstNode header = node.getSingle(RuleType.FeatureHeader, new AstNode(RuleType.FeatureHeader));
                if (header == null) return null;
                List<Tag> tags = getTags(header);
                Token featureLine = header.getToken(TokenType.FeatureLine);
                if (featureLine == null) return null;

                List<FeatureChild> children = new ArrayList<>();
                Background background = node.getSingle(RuleType.Background, null);
                if (background != null) {
                    children.add(new FeatureChild(null, background, null));
                }
                for (Scenario scenario : node.<Scenario>getItems(RuleType.ScenarioDefinition)) {
                    children.add(new FeatureChild(null, null, scenario));
                }
                for (Rule rule : node.<Rule>getItems(RuleType.Rule)) {
                    children.add(new FeatureChild(rule, null, null));
                }
                String description = getDescription(header);
                if (featureLine.matchedGherkinDialect == null)
                    return null;
                String language = featureLine.matchedGherkinDialect.getLanguage();

                return new Feature(
                        getLocation(featureLine, 0),
                        tags,
                        language,
                        featureLine.matchedKeyword,
                        featureLine.matchedText,
                        description,
                        children
                );
            }
            case Rule: {
                AstNode header = node.getSingle(RuleType.RuleHeader, new AstNode(RuleType.RuleHeader));
                if (header == null) return null;
                Token ruleLine = header.getToken(TokenType.RuleLine);
                if (ruleLine == null) return null;

                List<RuleChild> children = new ArrayList<>();
                List<Tag> tags = getTags(header);

                Background background = node.getSingle(RuleType.Background, null);
                if (background != null) {
                    children.add(new RuleChild(background, null));
                }
                List<Scenario> scenarios = node.getItems(RuleType.ScenarioDefinition);
                for (Scenario scenario : scenarios) {
                    children.add(new RuleChild(null, scenario));
                }

                return new Rule(
                        getLocation(ruleLine, 0),
                        tags,
                        ruleLine.matchedKeyword,
                        ruleLine.matchedText,
                        getDescription(header),
                        children,
                        idGenerator.newId()
                );

            }
            case GherkinDocument: {
                Feature feature = node.getSingle(RuleType.Feature, null);
                return new GherkinDocument(uri, feature, comments);
            }

        }
        return node;
    }

    private List<TableRow> getTableRows(AstNode node) {
        List<TableRow> rows = new ArrayList<>();

        for (Token token : node.getTokens(TokenType.TableRow)) {
            TableRow tableRow = new TableRow(getLocation(token, 0), getCells(token), idGenerator.newId());
            rows.add(tableRow);
        }
        ensureCellCount(rows);
        return rows;
    }

    private void ensureCellCount(List<TableRow> rows) {
        if (rows.isEmpty())
            return;

        int cellCount = rows.get(0).getCells().size();
        for (TableRow row : rows) {
            if (row.getCells().size() != cellCount) {
                io.cucumber.gherkin.Location location = new io.cucumber.gherkin.Location(
                        row.getLocation().getLine().intValue(),
                        row.getLocation().getColumn().orElse(0L).intValue()
                );
                throw new ParserException.AstBuilderException("inconsistent cell count within the table", location);
            }
        }
    }

    private List<TableCell> getCells(Token token) {
        List<TableCell> cells = new ArrayList<>();
        for (GherkinLineSpan cellItem : token.matchedItems) {
            TableCell tableCell = new TableCell(
                    getLocation(token, cellItem.column),
                    cellItem.text
            );
            cells.add(tableCell);
        }
        return cells;
    }

    private List<Step> getSteps(AstNode node) {
        return node.getItems(RuleType.Step);
    }

    private io.cucumber.messages.types.Location getLocation(Token token, int column) {
        column = column == 0 ? token.location.getColumn() : column;
        return new io.cucumber.messages.types.Location((long) token.location.getLine(), (long) column);
    }

    private String getDescription(AstNode node) {
        return node.getSingle(RuleType.Description, "");
    }

    private List<Tag> getTags(AstNode node) {
        AstNode tagsNode = node.getSingle(RuleType.Tags, new AstNode(RuleType.None));
        if (tagsNode == null)
            return new ArrayList<>();

        List<Token> tokens = tagsNode.getTokens(TokenType.TagLine);
        List<Tag> tags = new ArrayList<>();
        for (Token token : tokens) {
            for (GherkinLineSpan tagItem : token.matchedItems) {
                tags.add(new Tag(getLocation(token, tagItem.column), tagItem.text, idGenerator.newId()));
            }
        }
        return tags;
    }

    @Override
    public GherkinDocument getResult() {
        return currentNode().getSingle(RuleType.GherkinDocument, null);
    }
}
