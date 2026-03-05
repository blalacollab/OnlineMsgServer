package com.onlinemsg.client.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class OnlineMsgSocketClient {

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onBinaryMessage(payload: ByteArray)
        fun onClosed(code: Int, reason: String)
        fun onFailure(throwable: Throwable)
    }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var listener: Listener? = null

    fun connect(url: String, listener: Listener) {
        close(1000, "reconnect")
        this.listener = listener
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OnlineMsg-Android/1.0")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@OnlineMsgSocketClient.listener?.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                this@OnlineMsgSocketClient.listener?.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                this@OnlineMsgSocketClient.listener?.onBinaryMessage(raw)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) {
                    socket = null
                }
                this@OnlineMsgSocketClient.listener?.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) {
                    socket = null
                }
                this@OnlineMsgSocketClient.listener?.onFailure(t)
            }
        })
    }

    fun send(text: String): Boolean = socket?.send(text) == true

    fun close(code: Int = 1000, reason: String = "manual_close") {
        socket?.close(code, reason)
        socket = null
    }

    fun shutdown() {
        close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
