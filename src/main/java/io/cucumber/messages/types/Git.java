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

package io.cucumber.messages.types;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Represents the Git message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * Information about Git, provided by the Build/CI server as environment
 * variables.
 */
// Generated code
@SuppressWarnings("unused")
public final class Git {
    private final String remote;
    private final String revision;
    private final String branch;
    private final String tag;

    public Git(
        String remote,
        String revision,
        String branch,
        String tag
    ) {
        this.remote = requireNonNull(remote, "Git.remote cannot be null");
        this.revision = requireNonNull(revision, "Git.revision cannot be null");
        this.branch = branch;
        this.tag = tag;
    }

    public String getRemote() {
        return remote;
    }

    public String getRevision() {
        return revision;
    }

    public Optional<String> getBranch() {
        return Optional.ofNullable(branch);
    }

    public Optional<String> getTag() {
        return Optional.ofNullable(tag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Git that = (Git) o;
        return 
            remote.equals(that.remote) &&         
            revision.equals(that.revision) &&         
            Objects.equals(branch, that.branch) &&         
            Objects.equals(tag, that.tag);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            remote,
            revision,
            branch,
            tag
        );
    }

    @Override
    public String toString() {
        return "Git{" +
            "remote=" + remote +
            ", revision=" + revision +
            ", branch=" + branch +
            ", tag=" + tag +
            '}';
    }
}
