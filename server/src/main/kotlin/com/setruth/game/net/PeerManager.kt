package com.setruth.game.net

import com.setruth.game.room.Room
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * WebRTC 数据面：每用户一条 PeerConnection + 一条 unreliable/unordered DataChannel。
 * 协商失败 / 60s 未 open / 客户端主动 rtcFail → 该用户快照改走 WS（D1 降级）。
 * native 加载失败 → factory 为 null，全局走 WS（应急方案）。
 */
object PeerManager {
    private val log = LoggerFactory.getLogger("PeerManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peers = ConcurrentHashMap<Long, Peer>()

    /** DC 收到输入消息后回调（由 WsRoutes 装配到 RoomManager.handleInput） */
    var onInputMessage: (Long, String) -> Unit = { _, _ -> }

    private val factory: PeerConnectionFactory? by lazy {
        try {
            val audioModule = AudioDeviceModule(AudioLayer.kDummyAudio)
            PeerConnectionFactory(audioModule).also {
                log.info("WebRTC native 加载成功（dummy audio）")
            }
        } catch (t: Throwable) {
            log.error("WebRTC native 加载失败，数据面全局降级为 WS", t)
            null
        }
    }

    private class Peer(
        val pc: RTCPeerConnection,
        val dc: RTCDataChannel,
    ) {
        @Volatile var dcOpen = false
        @Volatile var fallback = false
    }

    /** 进入房间且 WS 已挂接后调用：创建 PC + DC + offer，经 WS 发出 */
    fun ensurePeer(userId: Long, sendWs: (String) -> Unit) {
        val f = factory ?: return
        closePeer(userId)
        try {
            lateinit var peer: Peer
            val rtcConfiguration = RTCConfiguration()
            val cfg = com.setruth.game.config.appConfig
            rtcConfiguration.portAllocatorConfig.minPort = cfg.rtcMinPort
            rtcConfiguration.portAllocatorConfig.maxPort = cfg.rtcMaxPort
            if (cfg.rtcStunUrl.isNotBlank()) {
                rtcConfiguration.iceServers = listOf(
                    dev.onvoid.webrtc.RTCIceServer().apply {
                        urls = listOf(cfg.rtcStunUrl)
                    }
                )
            }
            val pc = f.createPeerConnection(rtcConfiguration, object : PeerConnectionObserver {

                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    sendWs(buildJsonObject {
                        put("t", "rtcCand")
                        put("cand", buildJsonObject {
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        })
                    }.toString())
                }
            })
            val init = RTCDataChannelInit().apply {
                ordered = false
                maxRetransmits = 0 // 与 maxPacketLifeTime 互斥，只设前者
            }
            val dc = pc.createDataChannel("game", init)
            dc.registerObserver(object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previous: Long) {}
                override fun onStateChange() {
                    if (dc.state == RTCDataChannelState.OPEN) peer.dcOpen = true
                }
                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    // buffer.data 回调返回即释放：必须当场读出来
                    val data = buffer.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    onInputMessage(userId, String(bytes, Charsets.UTF_8))
                }
            })
            peer = Peer(pc, dc)
            peers[userId] = peer
            pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(desc: RTCSessionDescription) {
                    pc.setLocalDescription(desc, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            sendWs(buildJsonObject { put("t", "rtcOffer"); put("sdp", desc.sdp) }.toString())
                        }
                        override fun onFailure(error: String) = log.warn("setLocalDescription 失败 user=$userId: $error")
                    })
                }
                override fun onFailure(error: String) = log.warn("createOffer 失败 user=$userId: $error")
            })
            scope.launch {
                delay(60_000)
                if (!peer.dcOpen && peers[userId] === peer) {
                    peer.fallback = true
                    log.info("DC 60s 未 open，user=$userId 快照降级 WS")
                }
            }
        } catch (t: Throwable) {
            log.error("创建 PeerConnection 失败，user=$userId 走 WS", t)
        }
    }

    fun handleAnswer(userId: Long, sdp: String) {
        peers[userId]?.pc?.setRemoteDescription(
            RTCSessionDescription(RTCSdpType.ANSWER, sdp),
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {}
                override fun onFailure(error: String) = log.warn("setRemoteDescription 失败 user=$userId: $error")
            },
        )
    }

    fun addCandidate(userId: Long, cand: JsonObject) {
        val pc = peers[userId]?.pc ?: return
        try {
            pc.addIceCandidate(
                RTCIceCandidate(
                    cand["sdpMid"]?.jsonPrimitive?.content,
                    cand["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0,
                    cand["candidate"]?.jsonPrimitive?.content ?: return,
                )
            )
        } catch (t: Throwable) {
            log.warn("addIceCandidate 失败 user=$userId: ${t.message}")
        }
    }

    fun markFallback(userId: Long) {
        peers[userId]?.fallback = true
    }

    /** 延迟探测回显：经当前最优通道（DC 优先，降级走 WS）原样返回，客户端算 RTT */
    fun pong(userId: Long, ts: kotlinx.serialization.json.JsonElement?) {
        ts ?: return
        val json = """{"t":"pong","ts":$ts}"""
        val p = peers[userId]
        if (p != null && p.dcOpen && !p.fallback) {
            try {
                p.dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false))
                return
            } catch (t: Throwable) {
                p.fallback = true
            }
        }
        com.setruth.game.room.RoomManager.currentRoom(userId)?.members?.get(userId)?.session?.trySendText(json)
    }

    /** 快照广播：DC open 且未降级走 DC，否则走该成员 WS。同一字符串发所有连接（只序列化一次） */
    fun broadcastSnapshot(room: Room, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        room.members.values.forEach { m ->
            val p = peers[m.userId]
            if (p != null && p.dcOpen && !p.fallback) {
                try {
                    p.dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), false))
                    return@forEach
                } catch (t: Throwable) {
                    p.fallback = true
                    log.warn("DC 发送失败，user=${m.userId} 降级 WS: ${t.message}")
                }
            }
            m.session?.trySendText(json)
        }
    }

    fun closePeer(userId: Long) {
        peers.remove(userId)?.let { p ->
            try { p.dc.close() } catch (_: Throwable) {}
            try { p.pc.close() } catch (_: Throwable) {}
        }
    }
}
