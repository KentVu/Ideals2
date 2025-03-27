package org.rri.ideals.server

import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Objects
import java.util.regex.Pattern

class LspPath private constructor(uri: String) {
    private val normalizedUri: String

    fun toLspUri(): String {
        return normalizedUri
    }

    fun toPath(): Path {
        try {
            return Paths.get(URI(normalizedUri.replace(" ", "%20")))
        } catch (e: URISyntaxException) {
            throw RuntimeException(e)
        }
    }

    fun refreshAndFindVirtualFile(): VirtualFile? {
        return VirtualFileManager.getInstance().refreshAndFindFileByUrl(normalizedUri)
    }

    fun findVirtualFile(): VirtualFile? {
        return VirtualFileManager.getInstance().findFileByUrl(normalizedUri)
    }

    override fun toString(): String {
        return normalizedUri
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val lspPath = o as LspPath
        return normalizedUri == lspPath.normalizedUri
    }

    override fun hashCode(): Int {
        return Objects.hash(normalizedUri)
    }

    init {
        this.normalizedUri = normalizeUri(uri)
    }

    companion object {
        fun fromLocalPath(localPath: Path): LspPath {
            return LspPath(localPath.toUri().toString())
        }

        fun fromLspUri(uri: String): LspPath {
            return LspPath(uri)
        }

        fun fromVirtualFile(virtualFile: VirtualFile): LspPath {
            return fromLspUri(virtualFile.url)
        }

        private val schemeRegex: Pattern = Pattern.compile("^(\\w[\\w+-.]+):/+")

        /**
         * Converts URIs to have forward slashes and ensures the protocol has three slashes.
         *
         *
         * Important for testing URIs for equality across platforms.
         */
        private fun normalizeUri(uriString: String): String {
            var uriString = uriString
            uriString = uriString.replace('\\', '/')
            uriString = StringUtil.trimTrailing(uriString, '/')

            val matcher = schemeRegex.matcher(uriString)
            if (!matcher.find()) return uriString // if not a well-formed uri just leave as is


            val schemePlusColonPlusSlashes = matcher.group(0)

            // get rid of url-encoded parts like %20 etc.
            var rest = URLDecoder.decode(
                uriString.substring(schemePlusColonPlusSlashes.length),
                StandardCharsets.UTF_8
            )

            // lsp-mode expects paths to match with exact case.
            // This includes the Windows drive letter if the system is Windows.
            // So, always lowercase the drive letter to avoid any differences.
            if (rest.length > 1 && rest[1] == ':') {
                rest = rest[0].lowercaseChar().toString() + ':' + rest.substring(2)
            }

            return StringUtil.trimTrailing(schemePlusColonPlusSlashes, '/') + "///" + rest
        }
    }
}