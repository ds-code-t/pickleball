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

package io.cucumber.core.gherkin;

import io.cucumber.plugin.event.Location;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public interface Pickle {

    String getKeyword();

    String getLanguage();

    String getName();

    /**
     * Returns the location in the feature file of the Scenario this pickle was
     * created from. If this pickle was created from a Scenario Outline this
     * location is the location in the Example section used to fill in the place
     * holders.
     *
     * @return location in the feature file
     */
    Location getLocation();

    /**
     * Returns the location in the feature file of the Scenario this pickle was
     * created from. If this pickle was created from a Scenario Outline this
     * location is that of the Scenario
     *
     * @return location in the feature file
     */
    Location getScenarioLocation();

    /**
     * Returns the location in the feature file of the Rule this pickle was
     * created from.
     *
     * @return location in the feature file
     */
    default Optional<Location> getRuleLocation() {
        return Optional.empty();
    }

    /**
     * Returns the location in the feature file of the Feature this pickle was
     * created from.
     *
     * @return location in the feature file
     */
    default Optional<Location> getFeatureLocation() {
        return Optional.empty();
    }

    /**
     * Returns the location in the feature file of the examples this pickle was
     * created from.
     *
     * @return location in the feature file
     */
    default Optional<Location> getExamplesLocation() {
        return Optional.empty();
    }

    List<Step> getSteps();

    List<String> getTags();

    URI getUri();

    String getId();

}
