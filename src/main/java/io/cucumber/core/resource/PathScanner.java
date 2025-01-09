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

package io.cucumber.core.resource;

import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.walkFileTree;

class PathScanner {

    private static final Logger log = LoggerFactory.getLogger(PathScanner.class);

    void findResourcesForUri(URI baseUri, Predicate<Path> filter, Function<Path, Consumer<Path>> consumer) {
        try (CloseablePath closeablePath = open(baseUri)) {
            Path baseDir = closeablePath.getPath();
            findResourcesForPath(baseDir, filter, consumer);
        } catch (FileSystemNotFoundException e) {
            log.warn(e, () -> "Failed to find resources for '" + baseUri + "'");
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private CloseablePath open(URI uri) throws IOException, URISyntaxException {
        if (JarUriFileSystemService.supports(uri)) {
            return JarUriFileSystemService.open(uri);
        }

        return CloseablePath.open(uri);
    }

    void findResourcesForPath(Path path, Predicate<Path> filter, Function<Path, Consumer<Path>> consumer) {
        if (!exists(path)) {
            throw new IllegalArgumentException("path must exist: " + path);
        }

        try {
            walkFileTree(path, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new ResourceFileVisitor(filter, consumer.apply(path)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class ResourceFileVisitor extends SimpleFileVisitor<Path> {

        private static final Logger logger = LoggerFactory.getLogger(ResourceFileVisitor.class);

        private final Predicate<Path> resourceFileFilter;
        private final Consumer<Path> resourceFileConsumer;

        ResourceFileVisitor(Predicate<Path> resourceFileFilter, Consumer<Path> resourceFileConsumer) {
            this.resourceFileFilter = resourceFileFilter;
            this.resourceFileConsumer = resourceFileConsumer;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (resourceFileFilter.test(file)) {
                resourceFileConsumer.accept(file);
            }
            return CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
            logger.warn(e, () -> "IOException visiting file: " + file);
            return CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException e) {
            if (e != null) {
                logger.warn(e, () -> "IOException visiting directory: " + dir);
            }
            return CONTINUE;
        }

    }

}
