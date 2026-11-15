package com.pococast

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL

/** Backs NewPipeExtractor with plain HttpURLConnection, so it needs no Android extras. */
class NpeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = true
            connection.requestMethod = request.httpMethod()
            for ((name, values) in request.headers()) {
                for (value in values) connection.addRequestProperty(name, value)
            }
            val body = request.dataToSend()
            if (body != null && body.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Diag.log("npe: HTTP " + code + " for " + request.url().substringBefore('?'))
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.toList() }
            return Response(code, connection.responseMessage ?: "", headers, text,
                connection.url.toString())
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
