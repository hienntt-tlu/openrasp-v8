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

// Copyright 2006 MX Telecom Ltd

package com.baidu.openrasp.nativelib;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides a means of loading JNI libraries which are stored within a jar.
 * <p>
 * The library is first extracted to a temporary file, and then loaded with
 * <code>System.load()</code>.
 * <p>
 * The extractor implementation can be replaced, but the default implementation
 * expects to find the library in natives/, with its OS-dependent name. It
 * extracts the library underneath a temporary directory, whose name is given by
 * the System property "java.library.tmpdir", defaulting to "tmplib".
 * <p>
 * This is complicated by <a href=
 * "http://docs.oracle.com/javase/6/docs/technotes/guides/jni/jni-12.html#libmanage"
 * >Java's library and version management</a> - specifically "The same JNI
 * native library cannot be loaded into more than one class loader" . In
 * practice this appears to mean "A JNI library on a given absolute path cannot
 * be loaded by more than one classloader" . Native libraries that are loaded by
 * the OS dynamic linker as dependencies of JNI libraries are not subject to
 * this restriction.
 * <p>
 * Native libraries that are loaded as dependencies must be extracted using the
 * library identifier a.k.a. soname (which usually includes a major version
 * number) instead of what was linked against (this can be found using ldd on
 * linux or using otool on OS X). Because they are loaded by the OS dynamic
 * linker and not by explicit invocation within Java, this extractor needs to be
 * aware of them to extract them by alternate means. This is accomplished by
 * listing the base filename in a META-INF/lib/AUTOEXTRACT.LIST classpath
 * resource. This is useful for shipping libraries which are used by code which
 * is not itself aware of the NativeLoader system. The application must call
 * {@link #extractRegistered()} at some suitably early point in its
 * initialization (before loading any JNI libraries which might require these
 * dependencies), and ensure that JVM is launched with the LD_LIBRARY_PATH
 * environment variable (or other OS-dependent equivalent) set to include the
 * "tmplib" directory (or other directory as overridden by "java.library.tmpdir"
 * as above).
 *
 * @author Richard van der Hoff (richardv@mxtelecom.com)
 */
public class NativeLoader {

    private static final Logger LOGGER = Logger.getLogger("com.baidu.openrasp.nativelib.NativeLoader");
    private static JniExtractor jniExtractor = null;

    static {
        String contextInfo = "NativeLoader static initialization";
        
        try {
            info(contextInfo + " - Starting critical native loader initialization");
            
            /*
             * We provide two implementations of JniExtractor
             * 
             * The first will work with transitively, dynamically linked libraries with
             * shared global variables (e.g. dynamically linked c++) but can only be used by
             * one ClassLoader in the JVM.
             * 
             * The second can be used by multiple ClassLoaders in the JVM but will only work
             * if global variables are not shared between transitively, dynamically linked
             * libraries.
             * 
             * For convenience we assume that if the NativeLoader is loaded by the system
             * ClassLoader then it should be use the first form, and that if it is loaded by
             * a different ClassLoader then it should use the second.
             */
            
            // 🛡️ STARTUP CRITICAL - HANDLE WebappJniExtractor IOException
            try {
                if (NativeLoader.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
                    info(contextInfo + " - Using DefaultJniExtractor (system classloader)");
                    jniExtractor = new DefaultJniExtractor(null);
                } else {
                    info(contextInfo + " - Using WebappJniExtractor (webapp classloader)");
                    try {
                        jniExtractor = new WebappJniExtractor("Classloader");
                    } catch (IOException webappEx) {
                        error(contextInfo + " - IOException creating WebappJniExtractor, falling back to DefaultJniExtractor", webappEx);
                        try {
                            jniExtractor = new DefaultJniExtractor(null);
                            info(contextInfo + " - Successfully created fallback DefaultJniExtractor");
                        } catch (IOException defaultEx) {
                            error(contextInfo + " - Both WebappJniExtractor and DefaultJniExtractor failed", defaultEx);
                            throw new ExceptionInInitializerError("All JniExtractor implementations failed: " + defaultEx.getMessage());
                        }
                    }
                }
                
                // 🛡️ VALIDATE EXTRACTOR CREATION
                if (jniExtractor == null) {
                    String errorMsg = "JniExtractor creation returned null";
                    error(contextInfo + " - " + errorMsg, null);
                    throw new ExceptionInInitializerError(errorMsg);
                }
                
                info(contextInfo + " - JniExtractor created successfully: " + jniExtractor.getClass().getSimpleName());
                
            } catch (ExceptionInInitializerError e) {
                throw e; // Re-throw initialization errors
            } catch (Exception e) {
                String errorMsg = "Unexpected exception during JniExtractor creation: " + e.getMessage();
                error(contextInfo + " - " + errorMsg, e);
                throw new ExceptionInInitializerError(errorMsg);
            }
            
        } catch (ExceptionInInitializerError e) {
            throw e; // Re-throw without wrapping
        } catch (Exception e) {
            String errorMsg = "Fatal error during NativeLoader static initialization: " + e.getMessage();
            error(contextInfo + " - " + errorMsg, e);
            throw new ExceptionInInitializerError(errorMsg);
        }
    }

    /**
     * Extract the given library from a jar, and load it.
     * <p>
     * The default jni extractor expects libraries to be in
     * natives/&lt;platform&gt;/ with their platform-dependent name (e.g.
     * natives/osx_64/libnative.dylib).
     * <p>
     * If natives/ does not exists or does not contain the directory structure,
     * &lt;platform&gt;/&lt;lib_binary&gt; will be searched in the root,
     * META-INF/lib/ and <code>searchPaths</code>.
     * 
     * @param libName     platform-independent library name (as would be passed to
     *                    System.loadLibrary)
     * @param searchPaths a list of additional paths relative to the jar's root to
     *                    search for the specified native library in case it does
     *                    not exist in natives/, root or META-INF/lib/
     * @throws IOException       if there is a problem extracting the jni library
     * @throws SecurityException if a security manager exists and its
     *                           <code>checkLink</code> method doesn't allow loading
     *                           of the specified dynamic library
     */
    public static void loadLibrary(final String libName, final String... searchPaths) throws IOException {
        // 🔄 CONTEXT-AWARE LOADING
        boolean isStartup = isStartupPhase();
        String contextInfo = "NativeLoader.loadLibrary('" + libName + "', startup=" + isStartup + ")";
        
        try {
            fine(contextInfo + " - Attempting System.loadLibrary first");
            // try to load library from classpath
            System.loadLibrary(libName);
            fine(contextInfo + " - System.loadLibrary successful");
        } catch (final UnsatisfiedLinkError e) {
            fine(contextInfo + " - System.loadLibrary failed, trying extraction: " + e.getMessage());
            
            // 🛡️ CONTEXT-AWARE EXTRACTION
            boolean loadSuccess = false;
            try {
                loadSuccess = NativeLibraryUtil.loadNativeLibrary(jniExtractor, libName, searchPaths);
            } catch (Exception ex) {
                String errorMsg = "Exception during library extraction: " + ex.getMessage();
                if (isStartup) {
                    error(contextInfo + " - STARTUP FAILURE: " + errorMsg, ex);
                    throw new IOException("STARTUP FAILURE: " + errorMsg, ex);
                } else {
                    error(contextInfo + " - RUNTIME FAILURE: " + errorMsg, ex);
                    throw new IOException("Library loading failed: " + errorMsg, ex);
                }
            }
            
            if (!loadSuccess) {
                String errorMsg = "Failed to load library after extraction attempts";
                if (isStartup) {
                    error(contextInfo + " - STARTUP FAILURE: " + errorMsg, e);
                    throw new IOException("STARTUP FAILURE: " + errorMsg, e);
                } else {
                    error(contextInfo + " - RUNTIME FAILURE: " + errorMsg, e);
                    throw new IOException("Couldn't load library " + libName, e);
                }
            }
            
            info(contextInfo + " - Library loaded successfully via extraction");
        }
    }

    /**
     * Extract all libraries registered for auto-extraction by way of
     * META-INF/lib/AUTOEXTRACT.LIST resources. The application must call
     * {@link #extractRegistered()} at some suitably early point in its
     * initialization if it is using libraries packaged in this way.
     * 
     * @throws IOException if there is a problem extracting the libraries
     */
    public static void extractRegistered() throws IOException {
        // 🔄 CONTEXT-AWARE EXTRACTION
        boolean isStartup = isStartupPhase();
        String contextInfo = "NativeLoader.extractRegistered(startup=" + isStartup + ")";
        
        try {
            info(contextInfo + " - Starting registered library extraction");
            jniExtractor.extractRegistered();
            info(contextInfo + " - Registered library extraction completed");
        } catch (IOException e) {
            String errorMsg = "IOException during registered extraction: " + e.getMessage();
            if (isStartup) {
                error(contextInfo + " - STARTUP FAILURE: " + errorMsg, e);
                throw e; // Re-throw for startup
            } else {
                error(contextInfo + " - RUNTIME FAILURE: " + errorMsg, e);
                throw e; // IOException is part of method contract - caller must handle
            }
        } catch (Exception e) {
            String errorMsg = "Unexpected exception during registered extraction: " + e.getMessage();
            if (isStartup) {
                error(contextInfo + " - STARTUP FAILURE: " + errorMsg, e);
                throw new IOException("STARTUP FAILURE: " + errorMsg, e);
            } else {
                error(contextInfo + " - RUNTIME FAILURE: " + errorMsg, e);
                throw new IOException("Registered extraction failed: " + errorMsg, e);
            }
        }
    }

    /**
     * @return the JniExtractor implementation object.
     */
    public static JniExtractor getJniExtractor() {
        return jniExtractor;
    }

    /**
     * @param jniExtractor JniExtractor implementation to use instead of the
     *                     default.
     */
    public static void setJniExtractor(final JniExtractor jniExtractor) {
        String contextInfo = "NativeLoader.setJniExtractor";
        info(contextInfo + " - Replacing JniExtractor: " + 
            (NativeLoader.jniExtractor != null ? NativeLoader.jniExtractor.getClass().getSimpleName() : "null") + 
            " -> " + 
            (jniExtractor != null ? jniExtractor.getClass().getSimpleName() : "null"));
        NativeLoader.jniExtractor = jniExtractor;
    }

    // 🔄 MINIMAL ADDITION - STARTUP DETECTION METHOD
    private static boolean isStartupPhase() {
        try {
            // Use NativeLibraryUtil's startup detection if available
            return NativeLibraryUtil.isStartupPhase();
        } catch (Exception e) {
            // Fallback: assume startup if NativeLibraryUtil not available
            return true;
        }
    }
    
    // 🔄 MINIMAL ADDITION - LOGGING METHODS
    private static void info(String message) {
        try {
            LOGGER.log(Level.INFO, message);
        } catch (Exception e) {
            // Fail silently - don't let logging crash initialization
        }
    }
    
    private static void fine(String message) {
        try {
            LOGGER.log(Level.FINE, message);
        } catch (Exception e) {
            // Fail silently - don't let logging crash operations
        }
    }
    
    private static void error(String message, Throwable throwable) {
        try {
            if (throwable != null) {
                LOGGER.log(Level.SEVERE, message, throwable);
            } else {
                LOGGER.log(Level.SEVERE, message);
            }
        } catch (Exception e) {
            // Fail silently - don't let logging crash operations
        }
    }
}