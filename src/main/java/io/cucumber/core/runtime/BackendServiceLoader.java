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

package io.cucumber.core.runtime;

import io.cucumber.core.backend.Backend;
import io.cucumber.core.backend.BackendProviderService;
import io.cucumber.core.backend.ObjectFactory;
import io.cucumber.core.exception.CucumberException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Supplies instances of {@link Backend} created by using a
 * {@link ServiceLoader} to locate instance of {@link BackendSupplier}.
 */
public final class BackendServiceLoader implements BackendSupplier {

    private final Supplier<ClassLoader> classLoaderSupplier;
    private final ObjectFactorySupplier objectFactorySupplier;

    public BackendServiceLoader(
            Supplier<ClassLoader> classLoaderSupplier, ObjectFactorySupplier objectFactorySupplier
    ) {
        this.classLoaderSupplier = classLoaderSupplier;
        this.objectFactorySupplier = objectFactorySupplier;
    }

    @Override
    public Collection<? extends Backend> get() {
        ClassLoader classLoader = classLoaderSupplier.get();
        return get(ServiceLoader.load(BackendProviderService.class, classLoader));
    }

    Collection<? extends Backend> get(Iterable<BackendProviderService> serviceLoader) {
        Collection<? extends Backend> backends = loadBackends(serviceLoader);
        if (backends.isEmpty()) {
            throw new CucumberException(
                "No backends were found. Please make sure you have a backend module on your CLASSPATH.");
        }
        return backends;
    }

    private Collection<? extends Backend> loadBackends(Iterable<BackendProviderService> serviceLoader) {
        List<Backend> backends = new ArrayList<>();
        for (BackendProviderService backendProviderService : serviceLoader) {
            ObjectFactory objectFactory = objectFactorySupplier.get();
            backends.add(backendProviderService.create(objectFactory, objectFactory, classLoaderSupplier));
        }
        return backends;
    }

}
