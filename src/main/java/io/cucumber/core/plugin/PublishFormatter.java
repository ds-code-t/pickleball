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

import io.cucumber.core.options.CucumberProperties;
import io.cucumber.core.options.CurlOption;
import io.cucumber.plugin.ColorAware;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;

import java.io.IOException;
import java.util.Map;

import static io.cucumber.core.options.Constants.PLUGIN_PUBLISH_PROXY_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.PLUGIN_PUBLISH_URL_PROPERTY_NAME;

public final class PublishFormatter implements ConcurrentEventListener, ColorAware {

    /**
     * Where to publishes messages by default
     */
    public static final String DEFAULT_CUCUMBER_MESSAGE_STORE_URL = "https://messages.cucumber.io/api/reports -X GET";

    private final UrlReporter urlReporter = new UrlReporter(System.err);
    private final MessageFormatter delegate;

    public PublishFormatter() throws IOException {
        this(createCurlOption(null));
    }

    public PublishFormatter(String token) throws IOException {
        this(createCurlOption(token));
    }

    private PublishFormatter(CurlOption curlOption) throws IOException {
        UrlOutputStream outputStream = new UrlOutputStream(curlOption, urlReporter);
        this.delegate = new MessageFormatter(outputStream);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        delegate.setEventPublisher(publisher);
    }

    @Override
    public void setMonochrome(boolean monochrome) {
        urlReporter.setMonochrome(monochrome);
    }

    private static CurlOption createCurlOption(String token) {
        // Note: This only includes properties from the environment and
        // cucumber.properties. It does not include junit-platform.properties
        // Fixing this requires an overhaul of the plugin system.
        Map<String, String> properties = CucumberProperties.create();
        String url = properties.getOrDefault(PLUGIN_PUBLISH_URL_PROPERTY_NAME, DEFAULT_CUCUMBER_MESSAGE_STORE_URL);
        if (token != null) {
            url += String.format(" -H 'Authorization: Bearer %s'", token);
        }
        String proxy = properties.get(PLUGIN_PUBLISH_PROXY_PROPERTY_NAME);
        if (proxy != null) {
            url += String.format(" -x '%s'", proxy);
        }
        return CurlOption.parse(url);
    }

}
