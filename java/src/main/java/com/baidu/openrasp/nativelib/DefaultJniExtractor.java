// filepath: e:\chuyenlab\rasp_decom\openrasp-custom\openrasp-v8\java\src\main\java\com\baidu\openrasp\nativelib\DefaultJniExtractor.java
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
 * JniExtractor suitable for single application deployments per virtual machine
 * <p>
 * WARNING: This extractor can result in UnsatisifiedLinkError if it is used in
 * more than one classloader.
 * 
 * @author Richard van der Hoff (richardv@mxtelecom.com)
 */
public class DefaultJniExtractor extends BaseJniExtractor {

    private static final Logger LOGGER = Logger.getLogger("com.baidu.openrasp.nativelib.DefaultJniExtractor");

    /**
     * this is where native dependencies are extracted to (e.g. tmplib/).
     */
    private File nativeDir;

    public DefaultJniExtractor(final Class<?> libraryJarClass) throws IOException {
        super(libraryJarClass);

        // 🛡️ CONTEXT-AWARE INITIALIZATION
        String contextInfo = "DefaultJniExtractor(" + (libraryJarClass != null ? libraryJarClass.getName() : "null") + ")";
        boolean isStartup = isStartupPhase();
        
        try {
            info(contextInfo + " - Starting initialization (startup=" + isStartup + ")");
            
            nativeDir = getTempDir();
            
            // Order of operations is such that we do not error if we are racing with
            // another thread to create the directory.
            nativeDir.mkdirs();
            if (!nativeDir.isDirectory()) {
                String errorMsg = "Unable to create native library working directory " + nativeDir;
                error(contextInfo + " - " + errorMsg, null);
                throw new IOException(errorMsg);
            }
            
            nativeDir.deleteOnExit();
            info(contextInfo + " - Initialization successful: " + nativeDir.getAbsolutePath());
            
        } catch (IOException e) {
            error(contextInfo + " - IOException during initialization", e);
            throw e; // Always throw IOException in constructor (startup phase)
        } catch (Exception e) {
            error(contextInfo + " - Exception during initialization", e);
            throw new IOException("DefaultJniExtractor initialization failed: " + e.getMessage(), e);
        }
    }

    @Override
    public File getJniDir() {
        return nativeDir;
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