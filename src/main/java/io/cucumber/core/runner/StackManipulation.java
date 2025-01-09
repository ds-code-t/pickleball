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

import io.cucumber.core.backend.CucumberInvocationTargetException;
import io.cucumber.core.backend.Located;

final class StackManipulation {

    private StackManipulation() {

    }

    static Throwable removeFrameworkFrames(CucumberInvocationTargetException invocationException) {
        Throwable error = invocationException.getInvocationTargetExceptionCause();
        StackTraceElement[] stackTraceElements = error.getStackTrace();
        Located located = invocationException.getLocated();

        int newStackTraceLength = findIndexOf(located, stackTraceElements);
        if (newStackTraceLength == -1) {
            return error;
        }

        StackTraceElement[] newStackTrace = new StackTraceElement[newStackTraceLength];
        System.arraycopy(stackTraceElements, 0, newStackTrace, 0, newStackTraceLength);
        error.setStackTrace(newStackTrace);
        return error;
    }

    private static int findIndexOf(Located located, StackTraceElement[] stackTraceElements) {
        if (stackTraceElements.length == 0) {
            return -1;
        }

        int newStackTraceLength;
        for (newStackTraceLength = 1; newStackTraceLength < stackTraceElements.length; ++newStackTraceLength) {
            if (located.isDefinedAt(stackTraceElements[newStackTraceLength - 1])) {
                break;
            }
        }
        return newStackTraceLength;
    }

    static Throwable removeFrameworkFramesAndAppendStepLocation(
            CucumberInvocationTargetException invocationException, StackTraceElement stepLocation
    ) {
        Located located = invocationException.getLocated();
        Throwable error = invocationException.getInvocationTargetExceptionCause();
        if (stepLocation == null) {
            return error;
        }
        StackTraceElement[] stackTraceElements = error.getStackTrace();
        int newStackTraceLength = findIndexOf(located, stackTraceElements);
        if (newStackTraceLength == -1) {
            return error;
        }
        StackTraceElement[] newStackTrace = new StackTraceElement[newStackTraceLength + 1];
        System.arraycopy(stackTraceElements, 0, newStackTrace, 0, newStackTraceLength);
        newStackTrace[newStackTraceLength] = stepLocation;
        error.setStackTrace(newStackTrace);
        return error;
    }

}
