package com.setruth.game.net

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession

/** 非阻塞发送：outgoing 通道满/关闭时静默丢弃（快照场景可丢帧，下一帧覆盖） */
fun WebSocketSession.trySendText(text: String) {
    outgoing.trySend(Frame.Text(text))
}
