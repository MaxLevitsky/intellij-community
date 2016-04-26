/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.builtInWebServer

import com.intellij.openapi.diagnostic.catchAndLog
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.endsWithName
import com.intellij.openapi.util.io.endsWithSlash
import com.intellij.openapi.util.io.getParentPath
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtilRt
import com.intellij.util.isDirectory
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import org.jetbrains.io.*
import java.nio.file.Path
import java.nio.file.Paths

private class DefaultWebServerPathHandler : WebServerPathHandler() {
  override fun process(path: String,
                       project: Project,
                       request: FullHttpRequest,
                       context: ChannelHandlerContext,
                       projectName: String,
                       decodedRawPath: String,
                       isCustomHost: Boolean): Boolean {
    val extraHttpHeaders = validateToken(request, context.channel()) ?: return true

    val channel = context.channel()
    val pathToFileManager = WebServerPathToFileManager.getInstance(project)
    var pathInfo = pathToFileManager.pathToInfoCache.getIfPresent(path)
    if (pathInfo == null || !pathInfo.isValid) {
      pathInfo = pathToFileManager.doFindByRelativePath(path)
      if (pathInfo == null) {
        if (path.isEmpty()) {
          HttpResponseStatus.NOT_FOUND.send(channel, request, "Index file doesn't exist.", extraHttpHeaders)
          return true
        }
        else {
          return false
        }
      }

      pathToFileManager.pathToInfoCache.put(path, pathInfo)
    }

    var indexUsed = false
    if (pathInfo.isDirectory()) {
      var indexVirtualFile: VirtualFile? = null
      var indexFile: Path? = null
      if (pathInfo.file == null) {
        indexFile = findIndexFile(pathInfo.ioFile!!)
      }
      else {
        indexVirtualFile = findIndexFile(pathInfo.file!!)
      }

      if (indexFile == null && indexVirtualFile == null) {
        HttpResponseStatus.NOT_FOUND.send(channel, request, extraHeaders = extraHttpHeaders)
        return true
      }

      // we must redirect only after index file check to not expose directory status
      if (!endsWithSlash(decodedRawPath)) {
        redirectToDirectory(request, channel, if (isCustomHost) path else "$projectName/$path", extraHttpHeaders)
        return true
      }

      indexUsed = true
      pathInfo = PathInfo(indexFile, indexVirtualFile, pathInfo.root, pathInfo.moduleName, pathInfo.isLibrary)
      pathToFileManager.pathToInfoCache.put(path, pathInfo)
    }

    // if extraHttpHeaders is not empty, it means that we get request wih token in the query
    if (extraHttpHeaders.isEmpty && request.origin == null && request.referrer == null && request.isRegularBrowser() && !canBeAccessedDirectly(pathInfo.name)) {
      HttpResponseStatus.NOT_FOUND.send(context.channel(), request)
      return true
    }

    if (!indexUsed && !endsWithName(path, pathInfo.name)) {
      if (endsWithSlash(decodedRawPath)) {
        indexUsed = true
      }
      else {
        // FallbackResource feature in action, /login requested, /index.php retrieved, we must not redirect /login to /login/
        val parentPath = getParentPath(pathInfo.path)
        if (parentPath != null && endsWithName(path, PathUtilRt.getFileName(parentPath))) {
          redirectToDirectory(request, channel, if (isCustomHost) path else "$projectName/$path", extraHttpHeaders)
          return true
        }
      }
    }

    if (!checkAccess(pathInfo, channel, request)) {
      return true
    }

    val canonicalPath = if (indexUsed) "$path/${pathInfo.name}" else path
    for (fileHandler in WebServerFileHandler.EP_NAME.extensions) {
      LOG.catchAndLog {
        if (fileHandler.process(pathInfo!!, canonicalPath, project, request, channel, if (isCustomHost) null else projectName, extraHttpHeaders)) {
          return true
        }
      }
    }
    return false
  }
}

private fun checkAccess(pathInfo: PathInfo, channel: Channel, request: HttpRequest): Boolean {
  if (pathInfo.ioFile != null || pathInfo.file!!.isInLocalFileSystem) {
    val file = pathInfo.ioFile ?: Paths.get(pathInfo.file!!.path)
    if (file.isDirectory()) {
      HttpResponseStatus.FORBIDDEN.orInSafeMode(HttpResponseStatus.NOT_FOUND).send(channel, request)
      return false
    }
    else if (!checkAccess(file, Paths.get(pathInfo.root.path))) {
      HttpResponseStatus.FORBIDDEN.orInSafeMode(HttpResponseStatus.NOT_FOUND).send(channel, request)
      return false
    }
  }
  else if (pathInfo.file!!.`is`(VFileProperty.HIDDEN)) {
    HttpResponseStatus.FORBIDDEN.orInSafeMode(HttpResponseStatus.NOT_FOUND).send(channel, request)
    return false
  }

  return true
}