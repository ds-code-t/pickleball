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

package io.cucumber.cucumberexpressions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

final class PatternCompilerProvider {
    // visible from tests
    static PatternCompiler service;

    private PatternCompilerProvider() {
    }

    static synchronized PatternCompiler getCompiler() {
        if (service == null) {
            ServiceLoader<PatternCompiler> loader = ServiceLoader.load(PatternCompiler.class);
            Iterator<PatternCompiler> iterator = loader.iterator();
            findPatternCompiler(iterator);
        }
        return service;
    }

    static void findPatternCompiler(Iterator<PatternCompiler> iterator) {
        if (iterator.hasNext()) {
            service = iterator.next();
            if (iterator.hasNext()) {
                throwMoreThanOneCompilerException(iterator);
            }
        } else {
            service = new DefaultPatternCompiler();
        }
    }

    private static void throwMoreThanOneCompilerException(Iterator<PatternCompiler> iterator) {
        List<Class<? extends PatternCompiler>> allCompilers = new ArrayList<>();
        allCompilers.add(service.getClass());
        while (iterator.hasNext()) {
            allCompilers.add(iterator.next().getClass());
        }
        throw new IllegalStateException("More than one PatternCompiler: " + allCompilers);
    }
}
