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

package io.cucumber.core.cli;

import org.apiguardian.api.API;

/**
 * Contains all available command line options for
 * {@link io.cucumber.core.cli.Main}
 * <p>
 * After being passed to {@link io.cucumber.core.cli.Main#main} function, these
 * options will be parsed by
 * {@link io.cucumber.core.options.CommandlineOptionsParser} to provide running
 * options to CLI.
 * <p>
 * All the options are defined as static string variables to allow other
 * programs to call {@link io.cucumber.core.cli.Main#main} function in a more
 * consistent way. E.g.
 * {@code io.cucumber.core.cli.Main.run(NAME, "TestName", THREADS, "2")}
 */

@API(status = API.Status.STABLE)
public final class CommandlineOptions {
    public static final String HELP = "--help";
    public static final String HELP_SHORT = "-h";

    public static final String VERSION = "--version";
    public static final String VERSION_SHORT = "-v";

    public static final String I18N = "--i18n";

    public static final String THREADS = "--threads";

    public static final String GLUE = "--glue";
    public static final String GLUE_SHORT = "-g";

    public static final String TAGS = "--tags";
    public static final String TAGS_SHORT = "-t";

    public static final String PUBLISH = "--publish";

    public static final String PLUGIN = "--plugin";
    public static final String PLUGIN_SHORT = "-p";

    public static final String NO_SUMMARY = "--no-summary";

    public static final String NO_DRY_RUN = "--no-dry-run";

    public static final String DRY_RUN = "--dry-run";
    public static final String DRY_RUN_SHORT = "-d";

    public static final String NO_MONOCHROME = "--no-monochrome";

    public static final String MONOCHROME = "--monochrome";
    public static final String MONOCHROME_SHORT = "-m";

    public static final String SNIPPETS = "--snippets";

    public static final String NAME = "--name";
    public static final String NAME_SHORT = "-n";

    public static final String WIP = "--wip";
    public static final String WIP_SHORT = "-w";

    public static final String ORDER = "--order";

    public static final String COUNT = "--count";

    public static final String OBJECT_FACTORY = "--object-factory";

    public static final String UUID_GENERATOR = "--uuid-generator";

    private CommandlineOptions() {
    }
}
