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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.gherkin.Parser.RuleType;
import static io.cucumber.gherkin.Parser.TokenType;

class AstNode {
    private final Map<RuleType, List<Object>> subItems = new HashMap<RuleType, List<Object>>();
    public final RuleType ruleType;

    public AstNode(RuleType ruleType) {
        this.ruleType = ruleType;
    }

    public void add(RuleType ruleType, Object obj) {
        List<Object> items = subItems.computeIfAbsent(ruleType, k -> new ArrayList<>());
        items.add(obj);
    }

    public <T> T getSingle(RuleType ruleType, T defaultResult) {
        List<Object> items = getItems(ruleType);
        return (T) (items.isEmpty() ? defaultResult : items.get(0));
    }

    public <T> List<T> getItems(RuleType ruleType) {
        List<T> items = (List<T>) subItems.get(ruleType);
        if (items == null) {
            return Collections.emptyList();
        }
        return items;
    }

    public Token getToken(TokenType tokenType) {
        RuleType ruleType = RuleType.cast(tokenType);
        return getSingle(ruleType, new Token(null, null));
    }

    public List<Token> getTokens(TokenType tokenType) {
        return getItems(RuleType.cast(tokenType));
    }
}
