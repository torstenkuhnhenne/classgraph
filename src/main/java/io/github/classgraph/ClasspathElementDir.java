/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.LogNode;

/** A directory classpath element. */
class ClasspathElementDir extends ClasspathElement {
    /** The directory at the root of the classpath element. */
    private final File classpathEltDir;

    /** The number of characters to ignore to strip the classpath element path and relativize the path. */
    private final int ignorePrefixLen;

    /** Used to ensure that recursive scanning doesn't get into an infinite loop due to a link cycle. */
    private final Set<String> scannedCanonicalPaths = new HashSet<>();

    /**
     * A directory classpath element.
     *
     * @param classpathEltDir
     *            the classpath element directory
     * @param classLoader
     *            the classloader
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementDir(final File classpathEltDir, final ClassLoader classLoader, final ScanSpec scanSpec) {
        super(classLoader, scanSpec);
        this.classpathEltDir = classpathEltDir;
        this.ignorePrefixLen = classpathEltDir.getPath().length() + 1;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#open(
     * nonapi.io.github.classgraph.concurrency.WorkQueue, nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final LogNode log) {
        if (!scanSpec.scanDirs) {
            if (log != null) {
                log.log("Skipping classpath element, since dir scanning is disabled: " + classpathEltDir);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            // Auto-add nested lib dirs
            int childClasspathEntryIdx = 0;
            for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
                final File libDir = new File(classpathEltDir, libDirPrefix);
                if (FileUtils.canReadAndIsDir(libDir)) {
                    // Sort directory entries for consistency
                    final File[] listFiles = libDir.listFiles();
                    if (listFiles != null) {
                        Arrays.sort(listFiles);
                        // Add all jarfiles within lib dir as child classpath entries
                        for (final File file : listFiles) {
                            if (file.isFile() && file.getName().endsWith(".jar")) {
                                if (log != null) {
                                    log.log("Found lib jar: " + file);
                                }
                                workQueue.addWorkUnit(new ClasspathEntryWorkUnit(
                                        /* rawClasspathEntry = */ //
                                        new SimpleEntry<>(file.getPath(), classLoader),
                                        /* parentClasspathElement = */ this,
                                        /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++));
                            }
                        }
                    }
                }
            }
            for (final String packageRootPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES) {
                final File packageRootDir = new File(classpathEltDir, packageRootPrefix);
                if (FileUtils.canReadAndIsDir(packageRootDir)) {
                    if (log != null) {
                        log.log("Found package root: " + packageRootDir);
                    }
                    workQueue
                            .addWorkUnit(new ClasspathEntryWorkUnit(
                                    /* rawClasspathEntry = */ new SimpleEntry<>(packageRootDir.getPath(),
                                            classLoader),
                                    /* parentClasspathElement = */ this,
                                    /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++));
                }
            }
        } catch (final SecurityException e) {
            if (log != null) {
                log.log("Skipping classpath element, since dir cannot be accessed: " + classpathEltDir);
            }
            skipClasspathElement = true;
            return;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param relativePath
     *            the relative path
     * @param classpathResourceFile
     *            the classpath resource file
     * @return the resource
     */
    private Resource newResource(final String relativePath, final File classpathResourceFile) {
        Set<PosixFilePermission> posixFilePermissions = null;
        try {
            posixFilePermissions = Files.readAttributes(classpathResourceFile.toPath(), PosixFileAttributes.class)
                    .permissions();
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // POSIX attributes not supported
        }
        return new Resource(this, classpathResourceFile.length(), classpathResourceFile.lastModified(),
                posixFilePermissions) {
            /** The random access file. */
            private RandomAccessFile randomAccessFile;

            /** The file channel. */
            private FileChannel fileChannel;

            @Override
            public String getPath() {
                return relativePath;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return relativePath;
            }

            @Override
            public synchronized ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Parent directory could not be opened");
                }
                markAsOpen();
                try {
                    randomAccessFile = new RandomAccessFile(classpathResourceFile, "r");
                    fileChannel = randomAccessFile.getChannel();
                    MappedByteBuffer buffer = null;
                    try {
                        buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
                    } catch (final FileNotFoundException e) {
                        throw e;
                    } catch (IOException | OutOfMemoryError e) {
                        // If map failed, try calling System.gc() to free some allocated MappedByteBuffers
                        // (there is a limit to the number of mapped files -- 64k on Linux)
                        // See: http://www.mapdb.org/blog/mmap_files_alloc_and_jvm_crash/
                        System.gc();
                        // Then try calling map again
                        buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
                    }
                    byteBuffer = buffer;
                    length = byteBuffer.remaining();
                    return byteBuffer;
                } catch (final IOException | SecurityException | OutOfMemoryError e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            synchronized InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                    return new InputStreamOrByteBufferAdapter(read());
                } else {
                    return new InputStreamOrByteBufferAdapter(inputStream = new InputStreamResourceCloser(this,
                            Files.newInputStream(classpathResourceFile.toPath())));
                }
            }

            @Override
            public synchronized InputStream open() throws IOException {
                if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                    read();
                    return inputStream = new InputStreamResourceCloser(this, byteBufferToInputStream());
                } else {
                    markAsOpen();
                    try {
                        return inputStream = new InputStreamResourceCloser(this,
                                Files.newInputStream(classpathResourceFile.toPath()));
                    } catch (final IOException | SecurityException e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            public synchronized byte[] load() throws IOException {
                try {
                    final byte[] byteArray;
                    if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                        read();
                        byteArray = byteBufferToByteArray();
                    } else {
                        open();
                        byteArray = FileUtils.readAllBytesAsArray(inputStream, length);
                    }
                    length = byteArray.length;
                    return byteArray;
                } finally {
                    close();
                }
            }

            @Override
            public synchronized void close() {
                super.close(); // Close inputStream
                if (byteBuffer != null) {
                    FileUtils.closeDirectByteBuffer(byteBuffer, /* log = */ null);
                    byteBuffer = null;
                }
                if (fileChannel != null) {
                    try {
                        fileChannel.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                    fileChannel = null;
                }
                if (randomAccessFile != null) {
                    try {
                        randomAccessFile.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                    randomAccessFile = null;
                }
                markAsClosed();
            }
        };
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    Resource getResource(final String relativePath) {
        final File resourceFile = new File(classpathEltDir, relativePath);
        return FileUtils.canReadAndIsFile(resourceFile) ? newResource(relativePath, resourceFile) : null;
    }

    /**
     * Recursively scan a directory for file path patterns matching the scan spec.
     *
     * @param dir
     *            the directory
     * @param log
     *            the log
     */
    private void scanDirRecursively(final File dir, final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        // See if this canonical path has been scanned before, so that recursive scanning doesn't get stuck in an
        // infinite loop due to symlinks
        String canonicalPath;
        try {
            canonicalPath = dir.getCanonicalPath();
            if (!scannedCanonicalPaths.add(canonicalPath)) {
                if (log != null) {
                    log.log("Reached symlink cycle, stopping recursion: " + dir);
                }
                return;
            }
        } catch (final IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not canonicalize path: " + dir, e);
            }
            return;
        }

        final String dirPath = dir.getPath();
        final String dirRelativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";

        if (nestedClasspathRootPrefixes != null && nestedClasspathRootPrefixes.contains(dirRelativePath)) {
            if (log != null) {
                log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                        + dirRelativePath);
            }
            return;
        }

        // Ignore versioned sections in exploded jars -- they are only supposed to be used in jars.
        // TODO: is it necessary to support multi-versioned exploded jars anyway? If so, all the paths in a
        // directory classpath entry will have to be pre-scanned and masked, as happens in ClasspathElementZip.
        if (dirRelativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)) {
            if (log != null) {
                log.log("Found unexpected nested versioned entry in directory classpath element -- skipping: "
                        + dirRelativePath);
            }
            return;
        }

        // Whitelist/blacklist classpath elements based on dir resource paths
        checkResourcePathWhiteBlackList(dirRelativePath, log);
        if (skipClasspathElement) {
            return;
        }

        final ScanSpecPathMatch parentMatchStatus = scanSpec.dirWhitelistMatchStatus(dirRelativePath);
        if (parentMatchStatus == ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (log != null) {
                log.log("Reached blacklisted directory, stopping recursive scan: " + dirRelativePath);
            }
            return;
        }
        if (parentMatchStatus == ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH) {
            // Reached a non-whitelisted and non-blacklisted path -- stop the recursive scan
            return;
        }

        final File[] filesInDir = dir.listFiles();
        if (filesInDir == null) {
            if (log != null) {
                log.log("Invalid directory " + dir);
            }
            return;
        }
        Arrays.sort(filesInDir);
        final LogNode subLog = log == null ? null
                // Log dirs after files (addWhitelistedResources() precedes log entry with "0:")
                : log.log("1:" + canonicalPath, "Scanning directory: " + dir
                        + (dir.getPath().equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));

        // Only scan files in directory if directory is not only an ancestor of a whitelisted path
        if (parentMatchStatus != ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
            // Do preorder traversal (files in dir, then subdirs), to reduce filesystem cache misses
            for (final File fileInDir : filesInDir) {
                // Process files in dir before recursing
                if (fileInDir.isFile()) {
                    final String fileInDirRelativePath = dirRelativePath.isEmpty() || "/".equals(dirRelativePath)
                            ? fileInDir.getName()
                            : dirRelativePath + fileInDir.getName();

                    // Whitelist/blacklist classpath elements based on file resource paths
                    checkResourcePathWhiteBlackList(fileInDirRelativePath, subLog);
                    if (skipClasspathElement) {
                        return;
                    }

                    // If relative path is whitelisted
                    if (parentMatchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                            || parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                            || (parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                    && scanSpec.classfileIsSpecificallyWhitelisted(fileInDirRelativePath))) {
                        // Resource is whitelisted
                        final Resource resource = newResource(fileInDirRelativePath, fileInDir);
                        addWhitelistedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, subLog);

                        // Save last modified time  
                        fileToLastModified.put(fileInDir, fileInDir.lastModified());
                    } else {
                        if (subLog != null) {
                            subLog.log("Skipping non-whitelisted file: " + fileInDirRelativePath);
                        }
                    }
                }
            }
        } else if (scanSpec.enableClassInfo && dirRelativePath.equals("/")) {
            // Always check for module descriptor in package root, even if package root isn't in whitelist
            for (final File fileInDir : filesInDir) {
                if (fileInDir.getName().equals("module-info.class") && fileInDir.isFile()) {
                    final Resource resource = newResource("module-info.class", fileInDir);
                    addWhitelistedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, subLog);
                    fileToLastModified.put(fileInDir, fileInDir.lastModified());
                }
            }
        }
        // Recurse into subdirectories
        for (final File fileInDir : filesInDir) {
            if (fileInDir.isDirectory()) {
                scanDirRecursively(fileInDir, subLog);
                // If a blacklisted classpath element resource path was found, it will set skipClasspathElement
                if (skipClasspathElement) {
                    if (subLog != null) {
                        subLog.addElapsedTime();
                    }
                    return;
                }
            }
        }

        if (subLog != null) {
            subLog.addElapsedTime();
        }

        // Save the last modified time of the directory
        fileToLastModified.put(dir, dir.lastModified());
    }

    /**
     * Hierarchically scan directory structure for classfiles and matching files.
     *
     * @param log
     *            the log
     */
    @Override
    void scanPaths(final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + toString());
        }

        final LogNode subLog = log == null ? null
                : log.log(classpathEltDir.getPath(), "Scanning directory classpath element " + classpathEltDir);

        scanDirRecursively(classpathEltDir, subLog);

        finishScanPaths(subLog);
    }

    /**
     * Get the module name from module descriptor.
     *
     * @return the module name
     */
    @Override
    public String getModuleName() {
        return moduleNameFromModuleDescriptor == null || moduleNameFromModuleDescriptor.isEmpty() ? null
                : moduleNameFromModuleDescriptor;
    }

    /**
     * Get the directory {@link File}.
     *
     * @return The classpath element directory as a {@link File}.
     */
    @Override
    public File getFile() {
        return classpathEltDir;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#getURI()
     */
    @Override
    URI getURI() {
        return classpathEltDir.toURI();
    }

    /**
     * Return the classpath element directory as a String.
     *
     * @return the string
     */
    @Override
    public String toString() {
        return classpathEltDir.toString();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClasspathElementDir)) {
            return false;
        }
        final ClasspathElementDir other = (ClasspathElementDir) obj;
        return this.classpathEltDir.equals(other.classpathEltDir);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return classpathEltDir.hashCode();
    }
}
