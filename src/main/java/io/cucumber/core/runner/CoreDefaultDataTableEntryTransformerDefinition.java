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

package io.cucumber.core.runner;

import io.cucumber.core.backend.DefaultDataTableEntryTransformerDefinition;
import io.cucumber.core.backend.ScenarioScoped;
import io.cucumber.datatable.TableCellByTypeTransformer;
import io.cucumber.datatable.TableEntryByTypeTransformer;

import java.lang.reflect.Type;
import java.util.Map;

class CoreDefaultDataTableEntryTransformerDefinition implements DefaultDataTableEntryTransformerDefinition {

    protected final DefaultDataTableEntryTransformerDefinition delegate;
    private final TableEntryByTypeTransformer transformer;

    private CoreDefaultDataTableEntryTransformerDefinition(DefaultDataTableEntryTransformerDefinition delegate) {
        this.delegate = delegate;
        TableEntryByTypeTransformer transformer = delegate.tableEntryByTypeTransformer();
        this.transformer = delegate.headersToProperties() ? new ConvertingTransformer(transformer) : transformer;
    }

    public static CoreDefaultDataTableEntryTransformerDefinition create(
            DefaultDataTableEntryTransformerDefinition definition
    ) {
        // Ideally we would avoid this by keeping the scenario scoped
        // glue in a different bucket from the globally scoped glue.
        if (definition instanceof ScenarioScoped) {
            return new CoreDefaultDataTableEntryTransformerDefinition.ScenarioCoreDefaultDataTableEntryTransformerDefinition(
                definition);
        }
        return new CoreDefaultDataTableEntryTransformerDefinition(definition);
    }

    @Override
    public boolean headersToProperties() {
        return delegate.headersToProperties();
    }

    @Override
    public TableEntryByTypeTransformer tableEntryByTypeTransformer() {
        return transformer;
    }

    @Override
    public boolean isDefinedAt(StackTraceElement stackTraceElement) {
        return delegate.isDefinedAt(stackTraceElement);
    }

    @Override
    public String getLocation() {
        return delegate.getLocation();
    }

    private static class ScenarioCoreDefaultDataTableEntryTransformerDefinition
            extends CoreDefaultDataTableEntryTransformerDefinition implements ScenarioScoped {

        ScenarioCoreDefaultDataTableEntryTransformerDefinition(DefaultDataTableEntryTransformerDefinition delegate) {
            super(delegate);
        }

        @Override
        public void dispose() {
            if (delegate instanceof ScenarioScoped) {
                ScenarioScoped scenarioScoped = (ScenarioScoped) delegate;
                scenarioScoped.dispose();
            }
        }
    }

    private static class ConvertingTransformer implements TableEntryByTypeTransformer {

        private final CamelCaseStringConverter converter = new CamelCaseStringConverter();
        private final TableEntryByTypeTransformer delegate;

        ConvertingTransformer(TableEntryByTypeTransformer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object transform(
                Map<String, String> entryValue, Type toValueType, TableCellByTypeTransformer cellTransformer
        ) throws Throwable {
            return delegate.transform(converter.toCamelCase(entryValue), toValueType, cellTransformer);
        }

    }

}
