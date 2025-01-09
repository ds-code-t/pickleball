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

package io.cucumber.core.filter;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.tagexpressions.Expression;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class Filters implements Predicate<Pickle> {

    private Predicate<Pickle> filter = t -> true;

    public Filters(Options options) {
        List<Expression> tagExpressions = options.getTagExpressions();
        if (!tagExpressions.isEmpty()) {
            this.filter = this.filter.and(new TagPredicate(tagExpressions));
        }
        List<Pattern> nameFilters = options.getNameFilters();
        if (!nameFilters.isEmpty()) {
            this.filter = this.filter.and(new NamePredicate(nameFilters));
        }
        Map<URI, ? extends Collection<Integer>> lineFilters = options.getLineFilters();
        if (!lineFilters.isEmpty()) {
            this.filter = this.filter.and(new LinePredicate(lineFilters));
        }
    }

    @Override
    public boolean test(Pickle pickle) {
        return this.filter.test(pickle);
    }

}
