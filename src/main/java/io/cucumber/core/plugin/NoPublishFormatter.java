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

package io.cucumber.core.plugin;

import io.cucumber.messages.types.Envelope;
import io.cucumber.plugin.ColorAware;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;

import java.io.PrintStream;

import static io.cucumber.core.options.Constants.PLUGIN_PUBLISH_ENABLED_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;
import static java.util.Arrays.asList;

public final class NoPublishFormatter implements ConcurrentEventListener, ColorAware {

    private final PrintStream out;
    private boolean monochrome = false;

    public NoPublishFormatter() {
        this(System.err);
    }

    NoPublishFormatter(PrintStream out) {
        this.out = out;
    }

    @Override
    public void setMonochrome(boolean monochrome) {
        this.monochrome = monochrome;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(Envelope.class, this::writeMessage);
    }

    private void writeMessage(Envelope envelope) {
        if (envelope.getTestRunFinished().isPresent()) {
            printBanner();
        }
    }

    private void printBanner() {
        Banner banner = new Banner(out, monochrome);
        banner.print(
            asList(
                new Banner.Line(
                    new Banner.Span("Share your Cucumber Report with your team at "),
                    new Banner.Span("https://reports.cucumber.io", AnsiEscapes.CYAN, AnsiEscapes.INTENSITY_BOLD,
                        AnsiEscapes.UNDERLINE)),
                new Banner.Line("Activate publishing with one of the following:"),
                new Banner.Line(""),
                new Banner.Line(
                    new Banner.Span("src/test/resources/cucumber.properties:          "),
                    new Banner.Span(PLUGIN_PUBLISH_ENABLED_PROPERTY_NAME, AnsiEscapes.CYAN),
                    new Banner.Span("="),
                    new Banner.Span("true", AnsiEscapes.CYAN)),
                new Banner.Line(
                    new Banner.Span("src/test/resources/junit-platform.properties:    "),
                    new Banner.Span(PLUGIN_PUBLISH_ENABLED_PROPERTY_NAME, AnsiEscapes.CYAN),
                    new Banner.Span("="),
                    new Banner.Span("true", AnsiEscapes.CYAN)),
                new Banner.Line(
                    new Banner.Span("Environment variable:                            "),
                    new Banner.Span(PLUGIN_PUBLISH_ENABLED_PROPERTY_NAME.toUpperCase().replace('.', '_'),
                        AnsiEscapes.CYAN),
                    new Banner.Span("="),
                    new Banner.Span("true", AnsiEscapes.CYAN)),
                new Banner.Line(
                    new Banner.Span("JUnit:                                           "),
                    new Banner.Span("@CucumberOptions", AnsiEscapes.CYAN),
                    new Banner.Span("(publish = "),
                    new Banner.Span("true", AnsiEscapes.CYAN),
                    new Banner.Span(")")),
                new Banner.Line(""),
                new Banner.Line(
                    new Banner.Span("More information at "),
                    new Banner.Span("https://cucumber.io/docs/cucumber/environment-variables/", AnsiEscapes.CYAN)),
                new Banner.Line(""),
                new Banner.Line(
                    new Banner.Span("Disable this message with one of the following:")),
                new Banner.Line(""),
                new Banner.Line(
                    new Banner.Span("src/test/resources/cucumber.properties:          "),
                    new Banner.Span(PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, AnsiEscapes.CYAN),
                    new Banner.Span("="),
                    new Banner.Span("true", AnsiEscapes.CYAN)),
                new Banner.Line(
                    new Banner.Span("src/test/resources/junit-platform.properties:    "),
                    new Banner.Span(PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, AnsiEscapes.CYAN),
                    new Banner.Span("="),
                    new Banner.Span("true", AnsiEscapes.CYAN))),
            AnsiEscapes.GREEN, AnsiEscapes.INTENSITY_BOLD);
    }
}
