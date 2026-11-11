package com.anilili.data.profiles

import android.content.Context
import android.util.Base64
import com.anilili.data.pairing.localPairingAddress
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Receives a profile picture from a phone on the same network, with nothing installed on the phone.
 *
 * The TV serves one small page at a secret path on an ephemeral port, and shows that address as a
 * QR code. The phone's browser opens it, the viewer picks a photo, the page shrinks it and posts it
 * back to the same path. Anything at another path is answered 404, and the server exists only while
 * the upload screen is open, so the secret in the code is the whole of the access control — the
 * same bar as a pairing code shown on screen.
 *
 * Plain HTTP on purpose: a phone's browser cannot be made to trust a certificate the TV minted, and
 * what crosses the network is a picture the viewer chose to show on a shared screen.
 */
class PhonePictureUpload(
    context: Context,
    private val kind: PhoneUploadKind,
) : Closeable {
    constructor(context: Context, profileName: String) : this(context, PhoneUploadKind.profile(profileName))

    private val app = context.applicationContext
    private val server = ServerSocket()
    private val token = ByteArray(16).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    private val uploading = Mutex()

    sealed interface State {
        data object Waiting : State

        /** [fraction] is null until the size is known. */
        data class Receiving(val fraction: Float?) : State
        data class Received(val url: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Waiting)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starts listening; the address to show, or null when the TV has no local network address. */
    suspend fun start(): String? = withContext(Dispatchers.IO) {
        val address = localPairingAddress() ?: return@withContext null
        server.reuseAddress = true
        server.bind(InetSocketAddress(0))
        DiagnosticsLog.event("Profile picture upload listening port=${server.localPort}")
        "http://$address:${server.localPort}/$token"
    }

    /** Back to waiting, for another photo after one was received or refused. */
    fun reset() {
        _state.value = State.Waiting
    }

    /** Serves until cancelled. Each connection gets its own coroutine: a browser opens spare ones. */
    suspend fun serve(): Unit = coroutineScope {
        launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val socket = server.accept()
                    launch { handle(socket) }
                }
            } catch (e: IOException) {
                // close() is how a blocked accept() is ended.
                if (!server.isClosed) DiagnosticsLog.throwable("Profile picture upload accept failed", e)
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use {
            try {
                it.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                val head = readHead(input) ?: return@use
                val requestLine = head.substringBefore("\r\n").split(' ')
                val method = requestLine.getOrNull(0).orEmpty()
                val path = requestLine.getOrNull(1).orEmpty().substringBefore('?')
                val headers = head.split("\r\n").drop(1).mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }.toMap()
                when {
                    path != "/$token" && path != "/$token/" ->
                        respondText(output, 404, "This link has expired. Scan the code on your TV again.")
                    method == "GET" -> respond(output, 200, "text/html; charset=utf-8", page().toByteArray())
                    method == "POST" -> receive(input, output, headers["content-length"]?.toLongOrNull())
                    else -> respondText(output, 405, "Not supported.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                DiagnosticsLog.event("Profile picture upload connection dropped: ${e.message}")
            }
        }
    }

    private suspend fun receive(input: InputStream, output: OutputStream, length: Long?) {
        if (length == null) {
            respondText(output, 411, "Your browser didn't say how big the photo is. Try another browser.")
            return
        }
        if (length > kind.maxBytes) {
            fail(output, 413, kind.tooLargeMessage)
            return
        }
        if (!uploading.tryLock()) {
            respondText(output, 409, "The TV is still receiving another photo. Wait a moment and try again.")
            return
        }
        try {
            _state.value = State.Receiving(0f)
            val body = CountedBody(input, length) { received ->
                _state.value = State.Receiving(received.toFloat() / length.coerceAtLeast(1))
            }
            when (val result = kind.import(app, body)) {
                is ProfilePictures.Result.Saved -> {
                    respondText(output, 200, "Sent! Check your TV to use it.")
                    _state.value = State.Received(result.url)
                }
                is ProfilePictures.Result.Rejected -> if (body.truncated) {
                    fail(output, 400, "The photo didn't arrive completely. Try sending it again.")
                } else {
                    fail(output, result.status, result.message)
                }
            }
        } finally {
            uploading.unlock()
        }
    }

    private fun fail(output: OutputStream, status: Int, message: String) {
        DiagnosticsLog.event("Profile picture upload refused status=$status: $message")
        _state.value = State.Failed(message)
        runCatching { respondText(output, status, message) }
    }

    private fun respondText(output: OutputStream, status: Int, message: String) = respond(
        output,
        status,
        "application/json; charset=utf-8",
        """{"message":${JsonPrimitive(message)}}""".toByteArray(),
    )

    private fun respond(output: OutputStream, status: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        422 -> "Unprocessable Content"
        else -> "Error"
    }

    /** The request line and headers, or null for a request that is not HTTP or never finishes them. */
    private fun readHead(input: InputStream): String? {
        val bytes = StringBuilder()
        while (bytes.length < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) return null
            bytes.append(next.toChar())
            if (bytes.endsWith("\r\n\r\n")) return bytes.toString()
        }
        return null
    }

    /** The request body: exactly [length] bytes, noting whether the phone went away before sending them. */
    private class CountedBody(
        private val input: InputStream,
        private val length: Long,
        private val onProgress: (Long) -> Unit,
    ) : InputStream() {
        private var received = 0L
        private var lastReported = 0L
        var truncated = false
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (received >= length) return -1
            val read = input.read(buffer, offset, minOf(count.toLong(), length - received).toInt())
            if (read < 0) {
                truncated = true
                throw IOException("Upload ended after $received of $length bytes")
            }
            received += read
            if (received - lastReported >= PROGRESS_STEP_BYTES || received == length) {
                lastReported = received
                onProgress(received)
            }
            return read
        }
    }

    private fun page(): String = PAGE
        .replace("{{HEADING}}", escapeHtml(kind.heading))
        .replace("{{INTRO}}", escapeHtml(kind.intro))
        .replace("{{PREVIEW}}", if (kind.wide) WIDE_PREVIEW else ROUND_PREVIEW)
        .replace("{{SHORT}}", kind.shrinkShortSide.toString())
        .replace("{{MAX}}", kind.maxBytes.toString())
        .replace("{{MIN}}", kind.minSide.toString())
        .replace("{{MAX_MB}}", (kind.maxBytes / (1024 * 1024)).toString())

    private fun escapeHtml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val ROUND_PREVIEW = "width:176px;height:176px;border-radius:50%"
        const val WIDE_PREVIEW = "width:100%;aspect-ratio:16/9;border-radius:14px"
        const val SOCKET_TIMEOUT_MS = 30_000
        const val MAX_HEAD_BYTES = 8 * 1024
        const val PROGRESS_STEP_BYTES = 32L * 1024

        /**
         * The phone's side. It shrinks the photo before sending (short side at most
         * [PhoneUploadKind.shrinkShortSide], JPEG, quality lowered until it fits
         * [PhoneUploadKind.maxBytes]) so a 12 MB camera original
         * crosses the network as a few hundred KB, and so the browser — which already
         * understands the photo's rotation — decides which way up it is. A photo the browser cannot
         * open itself (HEIC on some Android phones) is sent as it is and the TV tries instead.
         */
        const val PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Anilili profile picture</title>
<style>
body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#0b0b0f;color:#eee;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:420px;width:100%;padding:28px 24px;box-sizing:border-box;text-align:center}
h1{font-size:22px;margin:0 0 8px}
p{color:#a8a8b3;margin:0 0 24px;line-height:1.45}
#pv{{{PREVIEW}};background:#1c1c24 center/cover no-repeat;margin:0 auto 24px}
.btn{display:block;width:100%;padding:16px;border-radius:14px;background:#fff;color:#000;font-size:17px;font-weight:600;cursor:pointer;box-sizing:border-box;user-select:none}
input{display:none}
.bar{height:6px;background:#26262e;border-radius:3px;overflow:hidden;margin-top:18px;visibility:hidden}
.bar i{display:block;height:100%;width:0;background:#fff;transition:width .15s}
#st{min-height:2.9em;margin-top:14px;line-height:1.45}
.err{color:#ff8a80}.ok{color:#9be89b}
small{display:block;color:#6f6f7a;margin-top:18px}
</style></head><body><main>
<h1>{{HEADING}}</h1>
<p>{{INTRO}}</p>
<div id="pv"></div>
<label class="btn" for="f" id="pick">Choose a photo</label>
<input id="f" type="file" accept="image/*">
<div class="bar" id="bar"><i id="fill"></i></div>
<div id="st"></div>
<small>Big photos are shrunk on your phone before sending. The TV takes up to {{MAX_MB}} MB.</small>
</main><script>
const MAX={{MAX}},MIN={{MIN}};
const $=id=>document.getElementById(id);
const f=$('f'),st=$('st'),pv=$('pv'),bar=$('bar'),fill=$('fill'),pick=$('pick');
let busy=false,shown=null;
function say(t,c){st.textContent=t;st.className=c||'';}
function setBusy(b){busy=b;pick.style.opacity=b?.5:1;pick.style.pointerEvents=b?'none':'';}
async function shrink(file){
  let bmp;
  try{bmp=await createImageBitmap(file);}catch(e){return null;}
  const w=bmp.width,h=bmp.height;
  if(Math.min(w,h)<MIN){const e=new Error('small');e.size=w+'×'+h;throw e;}
  const s=Math.min(1,{{SHORT}}/Math.min(w,h));
  const c=document.createElement('canvas');
  c.width=Math.round(w*s);c.height=Math.round(h*s);
  c.getContext('2d').drawImage(bmp,0,0,c.width,c.height);
  // Down in quality until it fits the TV's limit; a busy photo at 0.9 can run past it.
  let out=null;
  for(const q of [0.9,0.8,0.7,0.6,0.5]){
    out=await new Promise(r=>c.toBlob(r,'image/jpeg',q));
    if(!out||out.size<=MAX)break;
  }
  return out;
}
f.onchange=async()=>{
  const file=f.files[0];f.value='';
  if(!file||busy)return;
  if(file.type&&!file.type.startsWith('image/')){say("That isn't a picture. Choose a photo.",'err');return;}
  setBusy(true);say('Getting the photo ready…');
  let body=null;
  try{body=await shrink(file);}catch(e){
    setBusy(false);
    say('That picture is too small ('+e.size+'). Pick one at least '+MIN+' pixels on each side.','err');
    return;
  }
  if(!body)body=file;
  if(body.size>MAX){setBusy(false);say('That photo is too large. Pick one under '+Math.round(MAX/1048576)+' MB.','err');return;}
  if(shown)URL.revokeObjectURL(shown);
  shown=URL.createObjectURL(body);
  pv.style.backgroundImage='url('+shown+')';
  send(body);
};
function send(body){
  const x=new XMLHttpRequest();
  x.open('POST',location.pathname);
  x.timeout=120000;
  x.upload.onprogress=e=>{if(e.lengthComputable)fill.style.width=(100*e.loaded/e.total)+'%';};
  const lost=()=>{bar.style.visibility='hidden';setBusy(false);
    say("Couldn't reach the TV. Check your phone is on the same Wi-Fi and the TV is still showing the code.",'err');};
  x.onerror=lost;x.ontimeout=lost;
  x.onload=()=>{
    bar.style.visibility='hidden';setBusy(false);
    let m='';try{m=JSON.parse(x.responseText).message;}catch(e){}
    if(x.status===200){say(m||'Sent! Check your TV to use it.','ok');pick.textContent='Send a different photo';}
    else say(m||('The TV refused the photo ('+x.status+').'),'err');
  };
  fill.style.width='0';bar.style.visibility='visible';say('Sending to your TV…');setBusy(true);
  x.setRequestHeader('Content-Type','application/octet-stream');
  x.send(body);
}
</script></body></html>"""
    }
}

/**
 * What a [PhonePictureUpload] is receiving a picture for: the words on the phone's page, the limits
 * the page shrinks to, and where the TV stores what arrives.
 */
class PhoneUploadKind(
    val heading: String,
    val intro: String,
    val maxBytes: Long,
    val minSide: Int,
    /** The phone shrinks the photo until its shorter side is at most this. */
    val shrinkShortSide: Int,
    /** A wide preview on the phone's page instead of a round one. */
    val wide: Boolean,
    val tooLargeMessage: String,
    val import: suspend (Context, InputStream) -> ProfilePictures.Result,
) {
    companion object {
        fun profile(name: String) = PhoneUploadKind(
            heading = "Profile picture for $name",
            intro = "Pick a photo. You'll see it on your TV before it's used.",
            maxBytes = ProfilePictures.MAX_BYTES,
            minSide = ProfilePictures.MIN_SIDE_PX,
            shrinkShortSide = 1024,
            wide = false,
            tooLargeMessage = ProfilePictures.tooLargeMessage,
            import = { context, input -> ProfilePictures.import(context, input) },
        )
    }
}
