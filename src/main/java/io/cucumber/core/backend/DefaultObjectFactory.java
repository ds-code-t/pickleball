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

package io.cucumber.core.backend;

import io.cucumber.core.exception.CucumberException;
import org.apiguardian.api.API;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * Default factory to instantiate glue classes. Loaded via SPI.
 * <p>
 * This object factory instantiates glue classes by using their public
 * no-argument constructor. As such it does not provide any dependency
 * injection.
 * <p>
 * Note: This class is intentionally an explicit part of the public api. It
 * allows the default object factory to be used even when another object factory
 * implementation is present through the
 * {@value io.cucumber.core.options.Constants#OBJECT_FACTORY_PROPERTY_NAME}
 * property or equivalent configuration options.
 *
 * @see ObjectFactory
 */
@API(status = API.Status.STABLE, since = "7.1.0")
public final class DefaultObjectFactory implements ObjectFactory {

    private final Map<Class<?>, Object> instances = new HashMap<>();

    public void start() {
        // No-op
    }

    public void stop() {
        instances.clear();
    }

    public boolean addClass(Class<?> clazz) {
        return true;
    }

    public <T> T getInstance(Class<T> type) {
        T instance = type.cast(instances.get(type));
        if (instance == null) {
            instance = cacheNewInstance(type);
        }
        return instance;
    }

    private <T> T cacheNewInstance(Class<T> type) {
        try {
            Constructor<T> constructor = type.getConstructor();
            T instance = constructor.newInstance();
            instances.put(type, instance);
            return instance;
        } catch (NoSuchMethodException e) {
            throw new CucumberException(String.format("" +
                    "%s does not have a public zero-argument constructor.\n" +
                    "\n" +
                    "To use dependency injection add an other ObjectFactory implementation such as:\n" +
                    " * cucumber-picocontainer\n" +
                    " * cucumber-spring\n" +
                    " * cucumber-jakarta-cdi\n" +
                    " * ...etc\n",
                type), e);
        } catch (Exception e) {
            throw new CucumberException(String.format("Failed to instantiate %s", type), e);
        }
    }

}
