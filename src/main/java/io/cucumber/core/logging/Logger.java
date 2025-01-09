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

package io.cucumber.core.logging;

import java.util.function.Supplier;

/**
 * Logs messages to {@link java.util.logging.Logger}.
 * <p>
 * The methods correspond to {@link java.util.logging.Level} in JUL:
 * <ul>
 * <li>{@code error} maps to {@link java.util.logging.Level#SEVERE}</li>
 * <li>{@code warn} maps to {@link java.util.logging.Level#WARNING}</li>
 * <li>{@code info} maps to {@link java.util.logging.Level#INFO}</li>
 * <li>{@code config} maps to {@link java.util.logging.Level#CONFIG}</li>
 * <li>{@code debug} maps to {@link java.util.logging.Level#FINE}</li>
 * <li>{@code trace} maps to {@link java.util.logging.Level#FINER}</li>
 * </ul>
 */
public interface Logger {

    /**
     * Log the {@code message} at error level.
     *
     * @param message The message to log.
     */
    void error(Supplier<String> message);

    /**
     * Log the {@code message} and {@code throwable} at error level.
     *
     * @param throwable The throwable to log.
     * @param message   The message to log.
     */
    void error(Throwable throwable, Supplier<String> message);

    /**
     * Log the {@code message} at warning level.
     *
     * @param message The message to log.
     */
    void warn(Supplier<String> message);

    /**
     * Log the {@code message} and {@code throwable} at warning level.
     *
     * @param throwable The throwable to log.
     * @param message   The message to log.
     */
    void warn(Throwable throwable, Supplier<String> message);

    /**
     * Log the {@code message} at info level.
     *
     * @param message The message to log.
     */
    void info(Supplier<String> message);

    /**
     * Log the {@code message} and {@code throwable} at info level.
     *
     * @param throwable The throwable to log.
     * @param message   The message to log.
     */
    void info(Throwable throwable, Supplier<String> message);

    /**
     * Log the {@code message} at config level.
     *
     * @param message The message to log.
     */
    void config(Supplier<String> message);

    /**
     * Log the {@code message} and {@code throwable} at config level.
     *
     * @param throwable The throwable to log.
     * @param message   The message to log.
     */
    void config(Throwable throwable, Supplier<String> message);

    /**
     * Log the {@code message} at debug level.
     *
     * @param message The message to log.
     */
    void debug(Supplier<String> message);

    /**
     * Log {@code message} and {@code throwable} at debug level.
     *
     * @param throwable The throwable to log.
     * @param message   The message to log.
     */
    void debug(Throwable throwable, Supplier<String> message);

    /**
     * Log the {@code message} at trace level.
     *
     * @param message The message to log.
     */
    void trace(Supplier<String> message);

    /**
     * Log the {@code message} and {@code throwable} at trace level.
     *
     * @param throwable The throwable to log.
     * @param message   The message to log.
     */
    void trace(Throwable throwable, Supplier<String> message);

}
