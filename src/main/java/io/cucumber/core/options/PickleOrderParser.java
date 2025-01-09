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

package io.cucumber.core.options;

import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.order.StandardPickleOrders;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PickleOrderParser {

    private static final Logger log = LoggerFactory.getLogger(PickleOrderParser.class);

    private static final Pattern RANDOM_AND_SEED_PATTERN = Pattern.compile("random(?::(\\d+))?");

    static PickleOrder parse(String argument) {
        if ("reverse".equals(argument)) {
            return StandardPickleOrders.reverseLexicalUriOrder();
        }

        if ("lexical".equals(argument)) {
            return StandardPickleOrders.lexicalUriOrder();
        }

        Matcher matcher = RANDOM_AND_SEED_PATTERN.matcher(argument);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid order. Must be either reverse, random or random:<long>");
        }

        final long seed;
        String seedString = matcher.group(1);
        if (seedString != null) {
            seed = Long.parseLong(seedString);
        } else {
            seed = Math.abs(new Random().nextLong());
            log.info(() -> "Using random scenario order. Seed: " + seed);
        }
        return StandardPickleOrders.random(seed);
    }

}
