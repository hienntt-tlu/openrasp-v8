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

 package com.baidu.openrasp.nativelib;

 import java.io.File;
 import java.io.IOException;
 import java.util.Arrays;
 import java.util.LinkedList;
 import java.util.List;
 import java.util.concurrent.TimeUnit;
 import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.locks.Lock;
 import java.util.concurrent.locks.ReentrantLock;
 import java.util.logging.Level;
 import java.util.logging.Logger;
 
 /**
  * This class is a utility for loading native libraries.
  * <p>
  * Native libraries should be packaged into a single jar file, with the
  * following directory and file structure:
  *
  * <pre>
  * natives
  *   linux_32
  *     libxxx[-vvv].so
  *   linux_64
  *     libxxx[-vvv].so
  *   linux_musl32
  *     libxxx[-vvv].so
  *   linux_musl64
  *     libxxx[-vvv].so
  *   linux_arm
  *     libxxx[-vvv].so
  *   linux_arm64
  *     libxxx[-vvv].so
  *   osx_32
  *     libxxx[-vvv].dylib
  *   osx_64
  *     libxxx[-vvv].dylib
  *   windows_32
  *     xxx[-vvv].dll
  *   windows_64
  *     xxx[-vvv].dll
  *   aix_32
  *     libxxx[-vvv].so
  *     libxxx[-vvv].a
  *   aix_64
  *     libxxx[-vvv].so
  *     libxxx[-vvv].a
  * </pre>
  * <p>
  * Here "xxx" is the name of the native library and "-vvv" is an optional
  * version number.
  * <p>
  * Current approach is to unpack the native library into a temporary file and
  * load from there.
  *
  * @author Aivar Grislis
  */
 public class NativeLibraryUtil {
 
     public static enum Architecture {
         UNKNOWN, LINUX_32, LINUX_64, LINUX_MUSL32, LINUX_MUSL64, LINUX_ARM, LINUX_ARM64, WINDOWS_32, WINDOWS_64, OSX_32,
         OSX_64, OSX_PPC, AIX_32, AIX_64
     }
 
     private static enum Processor {
         UNKNOWN, INTEL_32, INTEL_64, PPC, PPC_64, ARM, AARCH_64
     }
 
     public static final String DELIM = "/";
     public static final String DEFAULT_SEARCH_PATH = "natives" + DELIM;
 
     private static Architecture architecture = Architecture.UNKNOWN;
     private static String archStr = null;
     private static final Logger LOGGER = Logger.getLogger("com.baidu.openrasp.nativelib.NativeLibraryUtil");
     
     // 🛡️ THREAD-SAFE LIBRARY LOADING TRACKING
     private static final ConcurrentHashMap<String, Boolean> loadedLibraries = new ConcurrentHashMap<>();
     private static final Lock loadLock = new ReentrantLock();
     
     // 🔄 STARTUP PHASE DETECTION
     private static volatile boolean startupPhaseCompleted = false;
     private static final Object startupLock = new Object();
     private static volatile long startupCompletionTime = 0L;

     /**
      * Mark that application startup phase has completed.
      * Call this when app server is ready to process requests.
      */
     public static void markStartupCompleted() {
         synchronized (startupLock) {
             if (!startupPhaseCompleted) {
                 startupPhaseCompleted = true;
                 startupCompletionTime = System.currentTimeMillis();
                 LOGGER.log(Level.INFO, "NativeLibraryUtil: Application startup phase completed");
             }
         }
     }
     
     /**
      * Check if we are still in startup phase
      * @return true if in startup phase
      */
     public static boolean isStartupPhase() {
         // Always consider startup phase until explicitly marked as completed
         return !startupPhaseCompleted;
     }

     /**
      * Determines the underlying hardware platform and architecture.
      *
      * @return enumerated architecture value
      */
     public static Architecture getArchitecture() {
         if (Architecture.UNKNOWN == architecture) {
             final Processor processor = getProcessor();
             if (Processor.UNKNOWN != processor) {
                 final String name = System.getProperty("os.name").toLowerCase();
                 if (name.contains("nix") || name.contains("nux")) {
                     Boolean isMusl = false;
                     try {
                         ProcessBuilder builder = new ProcessBuilder();
                         builder.command("sh", "-c", "case `ldd --version 2>&1` in *musl*) exit 0 ;; *) exit 1 ;; esac");
                         Process process = builder.start();
                         isMusl = process.waitFor() == 0;
                     } catch (Exception e) {
                         LOGGER.log(Level.WARNING, "Problem with detecting libc", e);
                     }
                     if (isMusl) {
                         if (Processor.INTEL_32 == processor) {
                             architecture = Architecture.LINUX_MUSL32;
                         } else if (Processor.INTEL_64 == processor) {
                             architecture = Architecture.LINUX_MUSL64;
                         }
                     } else if (Processor.INTEL_32 == processor) {
                         architecture = Architecture.LINUX_32;
                     } else if (Processor.INTEL_64 == processor) {
                         architecture = Architecture.LINUX_64;
                     } else if (Processor.ARM == processor) {
                         architecture = Architecture.LINUX_ARM;
                     } else if (Processor.AARCH_64 == processor) {
                         architecture = Architecture.LINUX_ARM64;
                     }
                 } else if (name.contains("aix")) {
                     if (Processor.PPC == processor) {
                         architecture = Architecture.AIX_32;
                     } else if (Processor.PPC_64 == processor) {
                         architecture = Architecture.AIX_64;
                     }
                 } else if (name.contains("win")) {
                     if (Processor.INTEL_32 == processor) {
                         architecture = Architecture.WINDOWS_32;
                     } else if (Processor.INTEL_64 == processor) {
                         architecture = Architecture.WINDOWS_64;
                     }
                 } else if (name.contains("mac")) {
                     if (Processor.INTEL_32 == processor) {
                         architecture = Architecture.OSX_32;
                     } else if (Processor.INTEL_64 == processor) {
                         architecture = Architecture.OSX_64;
                     } else if (Processor.PPC == processor) {
                         architecture = Architecture.OSX_PPC;
                     }
                 }
             }
         }
         LOGGER.log(Level.FINE,
                 "architecture is " + architecture + " os.name is " + System.getProperty("os.name").toLowerCase());
         return architecture;
     }
 
     /**
      * Determines what processor is in use.
      *
      * @return The processor in use.
      */
     private static Processor getProcessor() {
         Processor processor = Processor.UNKNOWN;
         int bits;
 
         // Note that this is actually the architecture of the installed JVM.
         final String arch = System.getProperty("os.arch").toLowerCase();
 
         if (arch.contains("arm")) {
             processor = Processor.ARM;
         } else if (arch.contains("aarch64")) {
             processor = Processor.AARCH_64;
         } else if (arch.contains("ppc")) {
             bits = 32;
             if (arch.contains("64")) {
                 bits = 64;
             }
             processor = (32 == bits) ? Processor.PPC : Processor.PPC_64;
         } else if (arch.contains("86") || arch.contains("amd")) {
             bits = 32;
             if (arch.contains("64")) {
                 bits = 64;
             }
             processor = (32 == bits) ? Processor.INTEL_32 : Processor.INTEL_64;
         }
         LOGGER.log(Level.FINE, "processor is " + processor + " os.arch is " + System.getProperty("os.arch").toLowerCase());
         return processor;
     }
 
     /**
      * Returns the path to the native library.
      * 
      * @param searchPath the path to search for &lt;platform&gt; directory. Pass in
      *                   <code>null</code> to get default path
      *                   (natives/&lt;platform&gt;).
      *
      * @return path
      */
     public static String getPlatformLibraryPath(String searchPath) {
         if (archStr == null)
             archStr = NativeLibraryUtil.getArchitecture().name().toLowerCase();
 
         // foolproof
         String fullSearchPath = (searchPath.equals("") || searchPath.endsWith(DELIM) ? searchPath : searchPath + DELIM)
                 + archStr + DELIM;
         LOGGER.log(Level.FINE, "platform specific path is " + fullSearchPath);
         return fullSearchPath;
     }
 
     /**
      * Returns the full file name (without path) of the native library.
      *
      * @param libName name of library
      * @return file name
      */
     public static String getPlatformLibraryName(final String libName) {
         String name = null;
         switch (getArchitecture()) {
             case AIX_32:
             case AIX_64:
             case LINUX_32:
             case LINUX_64:
             case LINUX_MUSL32:
             case LINUX_MUSL64:
             case LINUX_ARM:
             case LINUX_ARM64:
                 name = "lib" + libName + ".so";
                 break;
             case WINDOWS_32:
             case WINDOWS_64:
                 name = libName + ".dll";
                 break;
             case OSX_32:
             case OSX_64:
                 name = "lib" + libName + ".dylib";
                 break;
             default:
                 break;
         }
         LOGGER.log(Level.FINE, "native library name " + name);
         return name;
     }
 
     /**
      * Returns the Maven-versioned file name of the native library. In order for
      * this to work Maven needs to save its version number in the jar manifest. The
      * version of the library-containing jar and the version encoded in the native
      * library names should agree.
      *
      * <pre>
      * {@code
      * <build>
      *   <plugins>
      *     <plugin>
      *       <artifactId>maven-jar-plugin</artifactId>
      *         <inherited>true</inherited> *
      *         <configuration>
      *            <archive>
      *              <manifest>
      *                <packageName>com.example.package</packageName>
      *                <addDefaultImplementationEntries>true</addDefaultImplementationEntries> *
      *              </manifest>
      *           </archive>
      *         </configuration>
      *     </plugin>
      *   </plugins>
      * </build>
      *
      * * = necessary to save version information in manifest
      * }
      * </pre>
      *
      * @param libraryJarClass any class within the library-containing jar
      * @param libName         name of library
      * @return The Maven-versioned file name of the native library.
      */
     public static String getVersionedLibraryName(final Class<?> libraryJarClass, String libName) {
         final String version = libraryJarClass.getPackage().getImplementationVersion();
         if (null != version && version.length() > 0) {
             libName += "-" + version;
         }
         return libName;
     }
 
     /**
      * Loads the native library. Picks up the version number to specify from the
      * library-containing jar.
      *
      * @param libraryJarClass any class within the library-containing jar
      * @param libName         name of library
      * @return whether or not successful
      */
     public static boolean loadVersionedNativeLibrary(final Class<?> libraryJarClass, String libName) {
         // append version information to native library name
         libName = getVersionedLibraryName(libraryJarClass, libName);
 
         return loadNativeLibrary(libraryJarClass, libName);
     }
 
     /**
      * 🛡️ MAIN CONTEXT-AWARE LIBRARY LOADING METHOD
      *
      * @param jniExtractor the extractor to use
      * @param libName      name of library
      * @param searchPaths  a list of additional paths to search for the library
      * @return whether or not successful
      */
     public static boolean loadNativeLibrary(final JniExtractor jniExtractor, final String libName,
             final String... searchPaths) {
         
         // 🔄 CONTEXT DETECTION
         boolean isStartup = isStartupPhase();
         String contextInfo = "NativeLibraryUtil.loadNativeLibrary('" + libName + "', startup=" + isStartup + ")";
         
         // 🛡️ INPUT VALIDATION
         if (jniExtractor == null) {
             String errorMsg = "JniExtractor is null";
             LOGGER.log(Level.SEVERE, contextInfo + " - " + errorMsg);
             if (isStartup) {
                 throw new RuntimeException("STARTUP FAILURE: " + errorMsg);
             }
             return false;
         }
         
         if (libName == null || libName.trim().isEmpty()) {
             String errorMsg = "Library name is null or empty";
             LOGGER.log(Level.SEVERE, contextInfo + " - " + errorMsg);
             if (isStartup) {
                 throw new RuntimeException("STARTUP FAILURE: " + errorMsg);
             }
             return false;
         }
         
         String validatedLibName = libName.trim();
         
         // 🛡️ CHECK IF ALREADY LOADED
         Boolean isLoaded = loadedLibraries.get(validatedLibName);
         if (isLoaded != null && isLoaded) {
             LOGGER.log(Level.FINE, contextInfo + " - Library already loaded: " + validatedLibName);
             return true;
         }
         
         // 🛡️ CRITICAL ARCHITECTURE CHECK
         Architecture arch = getArchitecture();
         if (Architecture.UNKNOWN == arch) {
             String errorMsg = "No native library available for this platform";
             LOGGER.log(Level.WARNING, contextInfo + " - " + errorMsg);
             if (isStartup) {
                 throw new RuntimeException("STARTUP FAILURE: " + errorMsg);
             }
             return false;
         }
         
         loadLock.lock();
         try {
             // Double-check after acquiring lock
             Boolean isLoadedDoubleCheck = loadedLibraries.get(validatedLibName);
             if (isLoadedDoubleCheck != null && isLoadedDoubleCheck) {
                 LOGGER.log(Level.FINE, contextInfo + " - Library already loaded (double-check): " + validatedLibName);
                 return true;
             }
             
             LOGGER.log(Level.INFO, contextInfo + " - Attempting to load library: " + validatedLibName);
             
             try {
                 final List<String> libPaths = searchPaths == null ? new LinkedList<>()
                         : new LinkedList<>(Arrays.asList(searchPaths));
                 libPaths.add(0, NativeLibraryUtil.DEFAULT_SEARCH_PATH);
                 // for backward compatibility
                 libPaths.add(1, "");
                 libPaths.add(2, "META-INF" + NativeLibraryUtil.DELIM + "lib");
                 // NB: Although the documented behavior of this method is to load
                 // native library from META-INF/lib/, what it actually does is
                 // to load from the root dir. See: https://github.com/scijava/
                 // native-lib-loader/blob/6c303443cf81bf913b1732d42c74544f61aef5d1/
                 // src/main/java/org/scijava/nativelib/NativeLoader.java#L126
 
                 // search in each path in {natives/, /, META-INF/lib/, ...}
                 for (String libPath : libPaths) {
                     File extracted = null;
                     try {
                         extracted = jniExtractor.extractJni(NativeLibraryUtil.getPlatformLibraryPath(libPath), validatedLibName);
                     } catch (IOException e) {
                         if (isStartup) {
                             throw new RuntimeException("STARTUP FAILURE: extractJni IOException - " + e.getMessage(), e);
                         }
                         // Runtime: log and continue to next path
                         LOGGER.log(Level.FINE, contextInfo + " - IOException extracting from path: " + libPath + " - " + e.getMessage());
                         continue;
                     } catch (IllegalAccessException e) {
                         if (isStartup) {
                             throw new RuntimeException("STARTUP FAILURE: extractJni IllegalAccessException - " + e.getMessage(), e);
                         }
                         LOGGER.log(Level.FINE, contextInfo + " - IllegalAccessException extracting from path: " + libPath + " - " + e.getMessage());
                         continue;
                     } catch (ClassNotFoundException e) {
                         if (isStartup) {
                             throw new RuntimeException("STARTUP FAILURE: extractJni ClassNotFoundException - " + e.getMessage(), e);
                         }
                         LOGGER.log(Level.FINE, contextInfo + " - ClassNotFoundException extracting from path: " + libPath + " - " + e.getMessage());
                         continue;
                     } catch (NoSuchFieldException e) {
                         if (isStartup) {
                             throw new RuntimeException("STARTUP FAILURE: extractJni NoSuchFieldException - " + e.getMessage(), e);
                         }
                         LOGGER.log(Level.FINE, contextInfo + " - NoSuchFieldException extracting from path: " + libPath + " - " + e.getMessage());
                         continue;
                     } catch (Exception e) {
                         if (isStartup) {
                             throw new RuntimeException("STARTUP FAILURE: extractJni Exception - " + e.getMessage(), e);
                         }
                         LOGGER.log(Level.WARNING, contextInfo + " - Exception extracting from path: " + libPath + " - " + e.getMessage(), e);
                         continue;
                     }
                     
                     //   IF EXTRACTION SUCCESSFUL, TRY TO LOAD
                     if (extracted != null) {
                         try {
                             System.load(extracted.getAbsolutePath());
                             loadedLibraries.put(validatedLibName, true);
                             LOGGER.log(Level.INFO, contextInfo + " - Successfully loaded library: " + validatedLibName + 
                                 " from " + extracted.getAbsolutePath());
                             return true;
                         } catch (UnsatisfiedLinkError e) {
                             if (isStartup) {
                                 throw new RuntimeException("STARTUP FAILURE: System.load UnsatisfiedLinkError - " + e.getMessage(), e);
                             }
                             LOGGER.log(Level.WARNING, contextInfo + " - UnsatisfiedLinkError loading library: " + validatedLibName + " - " + e.getMessage());
                             // Continue trying other paths for runtime
                         } catch (SecurityException e) {
                             if (isStartup) {
                                 throw new RuntimeException("STARTUP FAILURE: System.load SecurityException - " + e.getMessage(), e);
                             }
                             LOGGER.log(Level.WARNING, contextInfo + " - SecurityException loading library: " + validatedLibName + " - " + e.getMessage());
                             // Continue trying other paths for runtime
                         }
                     }
                 }
                 
                 // 🛡️ ALL PATHS FAILED
                 String errorMsg = "Failed to load library after trying all paths: " + validatedLibName;
                 LOGGER.log(Level.SEVERE, contextInfo + " - " + errorMsg);
                 if (isStartup) {
                     throw new RuntimeException("STARTUP FAILURE: " + errorMsg);
                 }
                 return false;
                 
             } catch (RuntimeException e) {
                 if (isStartup) {
                     throw e; // Re-throw startup failures
                 }
                 LOGGER.log(Level.SEVERE, contextInfo + " - Runtime exception during library loading", e);
                 return false;
             } catch (Exception e) {
                 String errorMsg = "Unexpected exception during library loading: " + e.getMessage();
                 LOGGER.log(Level.SEVERE, contextInfo + " - " + errorMsg, e);
                 if (isStartup) {
                     throw new RuntimeException("STARTUP FAILURE: " + errorMsg, e);
                 }
                 return false;
             }
             
         } finally {
             loadLock.unlock();
         }
     }
 
     /**
      * Loads the native library.
      *
      * @param libraryJarClass any class within the library-containing jar
      * @param libName         name of library
      * @return whether or not successful
      */
     @Deprecated
     public static boolean loadNativeLibrary(final Class<?> libraryJarClass, final String libName) {
         // 🔄 CONTEXT-AWARE WRAPPER FOR DEPRECATED METHOD
         boolean isStartup = isStartupPhase();
         String contextInfo = "NativeLibraryUtil.loadNativeLibrary(Class, startup=" + isStartup + ")";
         
         try {
             LOGGER.log(Level.INFO, contextInfo + " - Creating DefaultJniExtractor for deprecated method");
             DefaultJniExtractor extractor = new DefaultJniExtractor(libraryJarClass);
             return loadNativeLibrary(extractor, libName);
         } catch (IOException e) {
             if (isStartup) {
                 LOGGER.log(Level.SEVERE, contextInfo + " - STARTUP FAILURE: IOException creating DefaultJniExtractor", e);
                 throw new RuntimeException("STARTUP FAILURE: " + e.getMessage(), e);
             } else {
                 LOGGER.log(Level.FINE, contextInfo + " - IOException creating DefaultJniExtractor", e);
                 return false;
             }
         } catch (Exception e) {
             if (isStartup) {
                 LOGGER.log(Level.SEVERE, contextInfo + " - STARTUP FAILURE: Exception creating DefaultJniExtractor", e);
                 throw new RuntimeException("STARTUP FAILURE: " + e.getMessage(), e);
             } else {
                 LOGGER.log(Level.WARNING, contextInfo + " - Exception creating DefaultJniExtractor", e);
                 return false;
             }
         }
     }
     
     // 🔄 UTILITY METHODS
     
     /**
      * Check if library is already loaded
      * @param libName library name
      * @return true if loaded
      */
     public static boolean isLibraryLoaded(final String libName) {
         if (libName == null || libName.trim().isEmpty()) {
             return false;
         }
         Boolean isLoaded = loadedLibraries.get(libName.trim());
         return isLoaded != null && isLoaded;
     }
     
     /**
      * Get startup completion time
      * @return startup completion timestamp, 0 if not completed
      */
     public static long getStartupCompletionTime() {
         return startupCompletionTime;
     }
 }