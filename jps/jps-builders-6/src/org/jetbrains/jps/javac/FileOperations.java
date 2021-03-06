// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.jps.javac;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * Abstract some file operations that can be differently implemented depending on the JVM version
 *
 * @author Eugene Zhuravlev
 * Date: 18-Oct-18
 */
public interface FileOperations {
  interface Archive {
    @NotNull
    Iterable<JavaFileObject> list(String relPath, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException;

    void close() throws IOException;
  }

  /**
   * @param file with archived data
   * @return - a corresponding archive object in case it has been already opened or null otherwise
   */
  @Nullable
  Archive lookupArchive(File file);
  
  Archive openArchive(File file, final String contentEncoding) throws IOException;

  boolean isFile(File file);

  @NotNull
  Iterable<File> listFiles(File file, boolean recursively) throws IOException;


  /**
   * @param file a file for which all associated cache data should be cleared; if null, all caches should be cleared
   */
  void clearCaches(@Nullable File file);

}
