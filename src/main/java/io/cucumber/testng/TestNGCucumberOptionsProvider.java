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

package io.cucumber.testng;

import io.cucumber.core.backend.ObjectFactory;
import io.cucumber.core.eventbus.UuidGenerator;
import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.options.CucumberOptionsAnnotationParser;
import io.cucumber.core.snippets.SnippetType;

import java.lang.annotation.Annotation;

final class TestNGCucumberOptionsProvider implements CucumberOptionsAnnotationParser.OptionsProvider {

    private static final Logger log = LoggerFactory.getLogger(TestNGCucumberOptionsProvider.class);

    @Override
    public CucumberOptionsAnnotationParser.CucumberOptions getOptions(Class<?> clazz) {
        CucumberOptions annotation = clazz.getAnnotation(CucumberOptions.class);
        if (annotation != null) {
            return new TestNGCucumberOptions(annotation);
        }
        warnWhenJUnitCucumberOptionsAreUsed(clazz);
        return null;
    }

    private static void warnWhenJUnitCucumberOptionsAreUsed(Class<?> clazz) {
        for (Annotation clazzAnnotation : clazz.getAnnotations()) {
            String name = clazzAnnotation.annotationType().getName();
            if ("io.cucumber.junit.CucumberOptions".equals(name)) {
                log.warn(() -> "Ignoring options provided by " + name + " on " + clazz.getName() + ". " +
                        "It is recommend to use separate runner classes for JUnit and TestNG.");
            }
        }
    }

    private static class TestNGCucumberOptions implements CucumberOptionsAnnotationParser.CucumberOptions {

        private final CucumberOptions annotation;

        TestNGCucumberOptions(CucumberOptions annotation) {
            this.annotation = annotation;
        }

        @Override
        public boolean dryRun() {
            return annotation.dryRun();
        }

        @Override
        public String[] features() {
            return annotation.features();
        }

        @Override
        public String[] glue() {
            return annotation.glue();
        }

        @Override
        public String[] extraGlue() {
            return annotation.extraGlue();
        }

        @Override
        public String tags() {
            return annotation.tags();
        }

        @Override
        public String[] plugin() {
            return annotation.plugin();
        }

        @Override
        public boolean publish() {
            return annotation.publish();
        }

        @Override
        public boolean monochrome() {
            return annotation.monochrome();
        }

        @Override
        public String[] name() {
            return annotation.name();
        }

        @Override
        public SnippetType snippets() {
            switch (annotation.snippets()) {
                case UNDERSCORE:
                    return SnippetType.UNDERSCORE;
                case CAMELCASE:
                    return SnippetType.CAMELCASE;
                default:
                    throw new IllegalArgumentException("" + annotation.snippets());
            }
        }

        @Override
        public Class<? extends ObjectFactory> objectFactory() {
            return (annotation.objectFactory() == NoObjectFactory.class) ? null : annotation.objectFactory();
        }

        @Override
        public Class<? extends UuidGenerator> uuidGenerator() {
            return (annotation.uuidGenerator() == NoUuidGenerator.class) ? null : annotation.uuidGenerator();
        }
    }

}
