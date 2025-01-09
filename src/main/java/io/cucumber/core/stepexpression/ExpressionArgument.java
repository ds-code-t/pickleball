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

package io.cucumber.core.stepexpression;

import io.cucumber.cucumberexpressions.Group;

import java.lang.reflect.Type;

public final class ExpressionArgument implements Argument {

    private final io.cucumber.cucumberexpressions.Argument<?> argument;

    ExpressionArgument(io.cucumber.cucumberexpressions.Argument<?> argument) {
        this.argument = argument;
    }

    @Override
    public Object getValue() {
        return argument.getValue();
    }

    public Group getGroup() {
        return argument.getGroup();
    }

    public Type getType() {
        return argument.getType();
    }

    public String getParameterTypeName() {
        return argument.getParameterType().getName();
    }

    @Override
    public String toString() {
        return argument.getGroup() == null ? null : argument.getGroup().getValue();
    }

}
