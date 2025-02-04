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

import io.cucumber.core.backend.ObjectFactory;
import io.cucumber.core.eventbus.UuidGenerator;
import io.cucumber.core.feature.FeatureWithLines;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.order.StandardPickleOrders;
import io.cucumber.core.plugin.DefaultSummaryPrinter;
import io.cucumber.core.plugin.NoPublishFormatter;
import io.cucumber.core.plugin.PublishFormatter;
import io.cucumber.core.snippets.SnippetType;
import io.cucumber.tagexpressions.Expression;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.cucumber.core.resource.ClasspathSupport.rootPackageUri;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

public final class RuntimeOptions implements
        io.cucumber.core.feature.Options,
        io.cucumber.core.runner.Options,
        io.cucumber.core.plugin.Options,
        io.cucumber.core.filter.Options,
        io.cucumber.core.backend.Options,
        io.cucumber.core.eventbus.Options {

    private final List<URI> glue = new ArrayList<>();
    private final List<Expression> tagExpressions = new ArrayList<>();
    private final List<Pattern> nameFilters = new ArrayList<>();
    private final List<FeatureWithLines> featurePaths = new ArrayList<>();
    private final Set<Plugin> plugins = new LinkedHashSet<>();
    private boolean dryRun;
    private boolean monochrome = false;
    private boolean wip = false;
    private SnippetType snippetType = SnippetType.UNDERSCORE;
    private int threads = 1;
    private PickleOrder pickleOrder = StandardPickleOrders.lexicalUriOrder();
    private int count = 0;
    private Class<? extends ObjectFactory> objectFactoryClass;
    private Class<? extends UuidGenerator> uuidGeneratorClass;
    private String publishToken;
    private Boolean publish;
    // Disable the banner advertising the hosted cucumber reports by default
    // until the uncertainty around the projects future is resolved. It would
    // not be proper to advertise a service that may be discontinued to new
    // users.
    // For context see: https://mattwynne.net/new-beginning
    private boolean publishQuiet = true;
    private boolean enablePublishPlugin;

    private RuntimeOptions() {

    }

    public static RuntimeOptions defaultOptions() {
        return new RuntimeOptions();
    }

    void addDefaultSummaryPrinter() {
        plugins.add(PluginOption.forClass(DefaultSummaryPrinter.class));
    }

    void addDefaultGlueIfAbsent() {
        if (glue.isEmpty()) {
            glue.add(rootPackageUri());
        }
    }

    void addDefaultFeaturePathIfAbsent() {
        if (featurePaths.isEmpty()) {
            featurePaths.add(FeatureWithLines.create(rootPackageUri(), emptyList()));
        }
    }

    void addPlugins(List<Plugin> plugins) {
        this.plugins.addAll(plugins);
    }

    public boolean isMultiThreaded() {
        return getThreads() > 1;
    }

    public int getThreads() {
        return threads;
    }

    void setThreads(int threads) {
        this.threads = threads;
    }

    @Override
    public List<Plugin> plugins() {
        Set<Plugin> plugins = new LinkedHashSet<>();
        plugins.addAll(this.plugins);
        plugins.addAll(getPublishPlugin());
        return new ArrayList<>(plugins);
    }

    private List<Plugin> getPublishPlugin() {
        if (!enablePublishPlugin) {
            return emptyList();
        }
        // Implicitly enabled by the token if not explicitly disabled
        if (!FALSE.equals(publish) && publishToken != null) {
            return singletonList(PluginOption.forClass(PublishFormatter.class, publishToken));
        }
        if (TRUE.equals(publish)) {
            return singletonList(PluginOption.forClass(PublishFormatter.class));
        }
        if (publishQuiet) {
            return emptyList();
        }
        return singletonList(PluginOption.forClass(NoPublishFormatter.class));
    }

    @Override
    public boolean isMonochrome() {
        return monochrome;
    }

    public boolean isWip() {
        return wip;
    }

    void setWip(boolean wip) {
        this.wip = wip;
    }

    void setMonochrome(boolean monochrome) {
        this.monochrome = monochrome;
    }

    @Override
    public List<URI> getGlue() {
        return unmodifiableList(glue);
    }

    @Override
    public boolean isDryRun() {
        return dryRun;
    }

    @Override
    public SnippetType getSnippetType() {
        return snippetType;
    }

    @Override
    public Class<? extends ObjectFactory> getObjectFactoryClass() {
        return objectFactoryClass;
    }

    void setObjectFactoryClass(Class<? extends ObjectFactory> objectFactoryClass) {
        this.objectFactoryClass = objectFactoryClass;
    }

    @Override
    public Class<? extends UuidGenerator> getUuidGeneratorClass() {
        return uuidGeneratorClass;
    }

    void setUuidGeneratorClass(Class<? extends UuidGenerator> uuidGeneratorClass) {
        this.uuidGeneratorClass = uuidGeneratorClass;
    }

    void setSnippetType(SnippetType snippetType) {
        this.snippetType = snippetType;
    }

    void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public void setGlue(List<URI> parsedGlue) {
        glue.clear();
        glue.addAll(parsedGlue);
    }

    @Override
    public List<URI> getFeaturePaths() {
        return unmodifiableList(featurePaths.stream()
                .map(FeatureWithLines::uri)
                .sorted()
                .distinct()
                .collect(Collectors.toList()));
    }

    public void setFeaturePaths(List<FeatureWithLines> featurePaths) {
        this.featurePaths.clear();
        this.featurePaths.addAll(featurePaths);
    }

    @Override
    public List<Expression> getTagExpressions() {
        return unmodifiableList(tagExpressions);
    }

    @Override
    public List<Pattern> getNameFilters() {
        return unmodifiableList(nameFilters);
    }

    public void setNameFilters(List<Pattern> nameFilters) {
        this.nameFilters.clear();
        this.nameFilters.addAll(nameFilters);
    }

    @Override
    public Map<URI, Set<Integer>> getLineFilters() {
        Map<URI, Set<Integer>> lineFilters = new HashMap<>();
        featurePaths.forEach(featureWithLines -> {
            SortedSet<Integer> lines = featureWithLines.lines();
            URI uri = featureWithLines.uri();
            if (lines.isEmpty()) {
                return;
            }
            lineFilters.putIfAbsent(uri, new TreeSet<>());
            lineFilters.get(uri).addAll(lines);
        });
        return unmodifiableMap(lineFilters);
    }

    @Override
    public int getLimitCount() {
        return getCount();
    }

    public int getCount() {
        return count;
    }

    void setCount(int count) {
        this.count = count;
    }

    public void setTagExpressions(List<Expression> tagExpressions) {
        this.tagExpressions.clear();
        this.tagExpressions.addAll(tagExpressions);
    }

    public PickleOrder getPickleOrder() {
        return pickleOrder;
    }

    void setPickleOrder(PickleOrder pickleOrder) {
        this.pickleOrder = pickleOrder;
    }

    void setPublishToken(String token) {
        this.publishToken = token;
    }

    void setPublish(Boolean publish) {
        this.publish = publish;
    }

    void setPublishQuiet(boolean publishQuiet) {
        this.publishQuiet = publishQuiet;
    }

    void setEnablePublishPlugin(boolean enablePublishPlugin) {
        this.enablePublishPlugin = enablePublishPlugin;
    }

}
