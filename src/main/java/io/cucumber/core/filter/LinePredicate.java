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
import io.cucumber.plugin.event.Location;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

final class LinePredicate implements Predicate<Pickle> {

    private final Map<URI, ? extends Collection<Integer>> lineFilters;

    LinePredicate(Map<URI, ? extends Collection<Integer>> lineFilters) {
        this.lineFilters = lineFilters;
    }

    @Override
    public boolean test(Pickle pickle) {
        URI picklePath = pickle.getUri();
        if (!lineFilters.containsKey(picklePath)) {
            return true;
        }
        for (Integer line : lineFilters.get(picklePath)) {
            if (Objects.equals(line, pickle.getLocation().getLine())
                    || Objects.equals(line, pickle.getScenarioLocation().getLine())
                    || pickle.getExamplesLocation().map(Location::getLine).map(line::equals).orElse(false)
                    || pickle.getRuleLocation().map(Location::getLine).map(line::equals).orElse(false)
                    || pickle.getFeatureLocation().map(Location::getLine).map(line::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

}
