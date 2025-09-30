/*
 * #%L
 * Native library loader for extracting and loading native libraries from Java.
 * %%
 * Copyright (C) 2010 - 2015 Board of Regents of the University of
 * Wisconsin-Madison and Glencoe Software, Inc.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

// This code is derived from Richard van der Hoff's mx-native-loader project:
// http://opensource.mxtelecom.com/maven/repo/com/wapmx/native/mx-native-loader/1.7/
// See NOTICE.txt for details.

// Copyright 2009 MX Telecom Ltd

package com.baidu.openrasp.nativelib;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JniExtractor suitable for multiple application deployments on the same
 * virtual machine (such as webapps)
 * <p>
 * Designed to avoid the restriction that jni library can be loaded by at most
 * one classloader at a time.
 * <p>
 * Works by extracting each library to a different location for each
 * classloader.
 * <p>
 * WARNING: This can expose strange and wonderful bugs in jni code. These bugs
 * generally stem from transitive dependencies of the jni library and can be
 * solved by linking these dependencies statically to form a single library
 * 
 * @author <a href="mailto:markjh@mxtelecom.com">markjh</a>
 */
public class WebappJniExtractor extends BaseJniExtractor {

    private static final Logger LOGGER = Logger.getLogger("com.baidu.openrasp.nativelib.WebappJniExtractor");

    private final File nativeDir;
    private final File jniSubDir;

    /**
     * @param classloaderName is a friendly name for your classloader which will be
     *                        embedded in the directory name of the
     *                        classloader-specific subdirectory which will be
     *                        created.
     */
    public WebappJniExtractor(final String classloaderName) throws IOException {
        // 🛡️ CONTEXT-AWARE INITIALIZATION
        String contextInfo = "WebappJniExtractor('" + classloaderName + "')";
        boolean isStartup = isStartupPhase();
        
        try {
            info(contextInfo + " - Starting webapp extractor initialization (startup=" + isStartup + ")");
            
            nativeDir = getTempDir();
            // Order of operations is such that we do not error if we are racing with
            // another thread to create the directory.
            nativeDir.mkdirs();
            if (!nativeDir.isDirectory()) {
                String errorMsg = "Unable to create native library working directory " + nativeDir;
                error(contextInfo + " - " + errorMsg, null);
                throw new IOException(errorMsg);
            }

            final long now = System.currentTimeMillis();
            File trialJniSubDir;
            int attempt = 0;
            while (true) {
                trialJniSubDir = new File(nativeDir, classloaderName + "." + now + "." + attempt);
                if (trialJniSubDir.mkdir())
                    break;
                if (trialJniSubDir.exists()) {
                    attempt++;
                    continue;
                }
                String errorMsg = "Unable to create native library working directory " + trialJniSubDir;
                error(contextInfo + " - " + errorMsg, null);
                throw new IOException(errorMsg);
            }
            jniSubDir = trialJniSubDir;
            jniSubDir.deleteOnExit();
            
            info(contextInfo + " - Webapp extractor initialization successful: " + jniSubDir.getAbsolutePath());
            
        } catch (IOException e) {
            error(contextInfo + " - IOException during webapp initialization", e);
            throw e; // Always throw IOException in constructor (startup phase)
        } catch (Exception e) {
            error(contextInfo + " - Exception during webapp initialization", e);
            throw new IOException("WebappJniExtractor initialization failed: " + e.getMessage(), e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        
        // 🛡️ SAFE CLEANUP
        try {
            if (jniSubDir != null && jniSubDir.exists()) {
                final File[] files = jniSubDir.listFiles();
                if (files != null) {
                    for (final File file : files) {
                        try {
                            file.delete();
                        } catch (Exception e) {
                            // Ignore individual file deletion failures
                        }
                    }
                }
                jniSubDir.delete();
            }
        } catch (Exception e) {
            // Don't let finalize cleanup crash - log if possible
            try {
                error("Error during WebappJniExtractor finalize cleanup", e);
            } catch (Exception logError) {
                // Completely ignore if logging also fails in finalize
            }
        }
    }

    @Override
    public File getJniDir() {
        return jniSubDir;
    }

    @Override
    public File getNativeDir() {
        return nativeDir;
    }

    // 🔄 MINIMAL ADDITION - STARTUP DETECTION METHOD
    private boolean isStartupPhase() {
        try {
            // Use NativeLibraryUtil's startup detection if available
            return NativeLibraryUtil.isStartupPhase();
        } catch (Exception e) {
            // Fallback: assume startup if NativeLibraryUtil not available
            return true;
        }
    }
    
    // 🔄 MINIMAL ADDITION - LOGGING METHODS
    private void info(String message) {
        try {
            LOGGER.log(Level.INFO, message);
        } catch (Exception e) {
            // Fail silently - don't let logging crash initialization
        }
    }
    
    private void error(String message, Throwable throwable) {
        try {
            if (throwable != null) {
                LOGGER.log(Level.SEVERE, message, throwable);
            } else {
                LOGGER.log(Level.SEVERE, message);
            }
        } catch (Exception e) {
            // Fail silently - don't let logging crash initialization
        }
    }
}