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

import io.cucumber.plugin.ColorAware;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.Plugin;
import io.cucumber.plugin.StrictAware;
import io.cucumber.plugin.event.Event;
import io.cucumber.plugin.event.EventPublisher;

import java.util.ArrayList;
import java.util.List;

public final class Plugins {

    private final List<Plugin> plugins;
    private final PluginFactory pluginFactory;
    private final Options pluginOptions;
    private boolean pluginNamesInstantiated;
    private EventPublisher orderedEventPublisher;

    public Plugins(PluginFactory pluginFactory, Options pluginOptions) {
        this.pluginFactory = pluginFactory;
        this.pluginOptions = pluginOptions;
        this.plugins = createPlugins();
    }

    private List<Plugin> createPlugins() {
        List<Plugin> plugins = new ArrayList<>();
        if (!pluginNamesInstantiated) {
            for (Options.Plugin pluginOption : pluginOptions.plugins()) {
                Plugin plugin = pluginFactory.create(pluginOption);
                addPlugin(plugins, plugin);
            }
            pluginNamesInstantiated = true;
        }
        return plugins;
    }

    private void addPlugin(List<Plugin> plugins, Plugin plugin) {
        plugins.add(plugin);
        setMonochromeOnColorAwarePlugins(plugin);
        setStrictOnStrictAwarePlugins(plugin);
    }

    private void setMonochromeOnColorAwarePlugins(Plugin plugin) {
        if (plugin instanceof ColorAware) {
            ColorAware colorAware = (ColorAware) plugin;
            colorAware.setMonochrome(pluginOptions.isMonochrome());
        }
    }

    private void setStrictOnStrictAwarePlugins(Plugin plugin) {
        if (plugin instanceof StrictAware) {
            StrictAware strictAware = (StrictAware) plugin;
            strictAware.setStrict(true);
        }
    }

    public List<Plugin> getPlugins() {
        return plugins;
    }

    public void addPlugin(Plugin plugin) {
        addPlugin(plugins, plugin);
    }

    public void setEventBusOnEventListenerPlugins(EventPublisher eventPublisher) {
        for (Plugin plugin : plugins) {
            if (plugin instanceof ConcurrentEventListener) {
                ((ConcurrentEventListener) plugin).setEventPublisher(eventPublisher);
            } else if (plugin instanceof EventListener) {
                ((EventListener) plugin).setEventPublisher(eventPublisher);
            }
        }
    }

    public void setSerialEventBusOnEventListenerPlugins(EventPublisher eventPublisher) {
        for (Plugin plugin : plugins) {
            if (plugin instanceof ConcurrentEventListener) {
                ((ConcurrentEventListener) plugin).setEventPublisher(eventPublisher);
            } else if (plugin instanceof EventListener) {
                EventPublisher orderedEventPublisher = getOrderedEventPublisher(eventPublisher);
                ((EventListener) plugin).setEventPublisher(orderedEventPublisher);
            }
        }
    }

    private EventPublisher getOrderedEventPublisher(EventPublisher eventPublisher) {
        // The ordered event publisher stores all events
        // so don't create it unless we need it.
        if (orderedEventPublisher == null) {
            orderedEventPublisher = createCanonicalOrderEventPublisher(eventPublisher);
        }
        return orderedEventPublisher;
    }

    private static EventPublisher createCanonicalOrderEventPublisher(EventPublisher eventPublisher) {
        final CanonicalOrderEventPublisher canonicalOrderEventPublisher = new CanonicalOrderEventPublisher();
        eventPublisher.registerHandlerFor(Event.class, canonicalOrderEventPublisher::handle);
        return canonicalOrderEventPublisher;
    }

}
