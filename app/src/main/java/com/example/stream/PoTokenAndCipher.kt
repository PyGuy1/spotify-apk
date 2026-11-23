package com.example.stream

import com.example.PulseLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class PoTokenBundle(
  val visitorData: String,
  val poToken: String,
  val generatedAtMs: Long,
) {
  fun isExpired(nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean = nowMs - generatedAtMs >= ttlMs

  companion object {
    const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
  }
}

class PoTokenGenerator(
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val rng = SecureRandom()
  @Volatile private var cachedBundle: PoTokenBundle? = null
  @Volatile private var overridePoToken: String = ""
  @Volatile private var overrideVisitorData: String = ""

  fun setManualOverride(poToken: String, visitorData: String = "") {
    overridePoToken = poToken.trim()
    overrideVisitorData = visitorData.trim()
    cachedBundle = null
  }

  fun getOrGenerate(forceRefresh: Boolean = false): PoTokenBundle {
    val now = clock()
    if (overridePoToken.isNotBlank()) {
      return PoTokenBundle(
        visitorData = overrideVisitorData.ifBlank { synthesizeVisitorData(now) },
        poToken = overridePoToken,
        generatedAtMs = now,
      )
    }
    val existing = cachedBundle
    if (!forceRefresh && existing != null && !existing.isExpired(now)) {
      return existing
    }
    synchronized(this) {
      val again = cachedBundle
      if (!forceRefresh && again != null && !again.isExpired(now)) {
        return again
      }
      val visitor = overrideVisitorData.ifBlank { synthesizeVisitorData(now) }
      val token = synthesizePoToken(visitor, now)
      return PoTokenBundle(visitor, token, now).also { cachedBundle = it }
    }
  }

  private fun synthesizeVisitorData(nowMs: Long): String {
    // Compliant protobuf-shaped visitorData payload: field 1 = 11-byte id, field 5 = unix seconds
    val idBytes = ByteArray(11)
    rng.nextBytes(idBytes)
    val idChars =
      buildString(11) {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        idBytes.forEach { b -> append(alpha[(b.toInt() and 0x3F)]) }
      }
    val sec = (nowMs / 1000L).toInt()
    val payload = ByteArray(2 + idChars.length + 5)
    payload[0] = 0x0A
    payload[1] = idChars.length.toByte()
    idChars.toByteArray(Charsets.US_ASCII).copyInto(payload, 2)
    val tsOffset = 2 + idChars.length
    payload[tsOffset] = 0x28
    var v = sec
    for (i in 0 until 4) {
      payload[tsOffset + 1 + i] = ((v and 0x7F) or (if (i < 3) 0x80 else 0)).toByte()
      v = v ushr 7
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
  }

  private fun synthesizePoToken(visitorData: String, nowMs: Long): String {
    val entropy = ByteArray(32)
    rng.nextBytes(entropy)
    val seedBytes = visitorData.toByteArray(Charsets.UTF_8)
    val ts = (nowMs / 1000L)
    val frame = ByteArray(48)
    for (i in 0 until 32) {
      frame[i] = (entropy[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toByte()
    }
    for (i in 0 until 8) {
      frame[32 + i] = ((ts ushr (i * 8)) and 0xFF).toByte()
    }
    for (i in 40 until 48) {
      frame[i] = entropy[i - 40]
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
  }
}

sealed class CipherStep {
  data object Reverse : CipherStep()
  data class Splice(val index: Int) : CipherStep()
  data class Swap(val index: Int) : CipherStep()
}

data class PlayerCipherConfig(
  val playerId: String,
  val signatureTimestamp: Int,
  val sigSteps: List<CipherStep>,
  val nShiftKey: Int? = null,
  val fetchedAtMs: Long = System.currentTimeMillis(),
)

class CipherConfigProvider(
  private val httpClient: StreamHttpClient,
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val remoteConfigUrls: List<String> = DEFAULT_REMOTE_CONFIG_URLS,
) {
  private val configsByPlayerId = ConcurrentHashMap<String, PlayerCipherConfig>()
  @Volatile private var remoteConfigsLoadedAtMs: Long = 0L

  suspend fun getRemoteConfig(playerId: String): PlayerCipherConfig? {
    configsByPlayerId[playerId]?.let { return it }
    ensureRemoteConfigsLoaded()
    return configsByPlayerId[playerId]
  }

  fun latestKnownSts(): Int =
    configsByPlayerId.values.maxByOrNull { it.fetchedAtMs }?.signatureTimestamp
      ?: FALLBACK_STS

  fun registerConfig(config: PlayerCipherConfig) {
    configsByPlayerId[config.playerId] = config
  }

  suspend fun ensureRemoteConfigsLoaded(forceRefresh: Boolean = false) {
    val now = clock()
    if (!forceRefresh &&
      configsByPlayerId.isNotEmpty() &&
      now - remoteConfigsLoadedAtMs < REMOTE_CONFIG_TTL_MS
    ) {
      return
    }
    for (url in remoteConfigUrls) {
      try {
        val raw = httpClient.getTextSuspend(url)
        val count = parseZemerPlayerConfigsJson(raw, now)
        if (count > 0) {
          remoteConfigsLoadedAtMs = now
          PulseLog.d(TAG, "Loaded $count remote player cipher configs from $url")
          return
        }
      } catch (e: Exception) {
        PulseLog.d(TAG, "Remote cipher config skipped ($url): ${e.message}")
      }
    }
  }

  internal fun parseZemerPlayerConfigsJson(rawJson: String, nowMs: Long = clock()): Int {
    val trimmed = rawJson.trim()
    if (trimmed.isEmpty()) return 0
    var loaded = 0
    if (trimmed.startsWith("{")) {
      val root = JSONObject(trimmed)
      val players = root.optJSONObject("players") ?: root
      val keys = players.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val obj = players.optJSONObject(key) ?: continue
        parseSingleConfigObject(key, obj, nowMs)?.let { cfg ->
          configsByPlayerId[cfg.playerId] = cfg
          loaded++
        }
      }
    } else if (trimmed.startsWith("[")) {
      val arr = JSONArray(trimmed)
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val key =
          obj.optString("player_id", obj.optString("playerId", obj.optString("id", ""))).trim()
        parseSingleConfigObject(key, obj, nowMs)?.let { cfg ->
          configsByPlayerId[cfg.playerId] = cfg
          loaded++
        }
      }
    }
    return loaded
  }

  private fun parseSingleConfigObject(
    fallbackId: String,
    obj: JSONObject,
    nowMs: Long,
  ): PlayerCipherConfig? {
    val id =
      obj.optString("player_id", obj.optString("playerId", obj.optString("id", fallbackId))).trim()
    if (id.isBlank()) return null
    val sts = obj.optInt("sts", obj.optInt("signatureTimestamp", FALLBACK_STS))
    val opsArr =
      obj.optJSONArray("sig_ops")
        ?: obj.optJSONArray("sigOps")
        ?: obj.optJSONArray("operations")
        ?: obj.optJSONArray("actions")
    val steps = parseStepsArray(opsArr)
    if (steps.isEmpty()) return null
    val nShift =
      if (obj.has("n_shift")) obj.optInt("n_shift")
      else if (obj.has("nShift")) obj.optInt("nShift")
      else null
    return PlayerCipherConfig(
      playerId = id,
      signatureTimestamp = sts,
      sigSteps = steps,
      nShiftKey = nShift,
      fetchedAtMs = nowMs,
    )
  }

  private fun parseStepsArray(arr: JSONArray?): List<CipherStep> {
    if (arr == null) return emptyList()
    return buildList {
      for (i in 0 until arr.length()) {
        when (val elem = arr.opt(i)) {
          is JSONArray -> {
            val op = elem.optString(0, "").lowercase()
            val arg = elem.optInt(1, 0)
            when (op) {
              "r", "rev", "reverse" -> add(CipherStep.Reverse)
              "s", "splice", "slice" -> add(CipherStep.Splice(arg))
              "w", "swap" -> add(CipherStep.Swap(arg))
            }
          }
          is JSONObject -> {
            val op = elem.optString("op", elem.optString("type", "")).lowercase()
            val arg = elem.optInt("arg", elem.optInt("index", 0))
            when (op) {
              "r", "rev", "reverse" -> add(CipherStep.Reverse)
              "s", "splice", "slice" -> add(CipherStep.Splice(arg))
              "w", "swap" -> add(CipherStep.Swap(arg))
            }
          }
          is String -> {
            val clean = elem.trim().lowercase()
            when {
              clean == "r" || clean == "reverse" -> add(CipherStep.Reverse)
              clean.startsWith("s:") -> clean.substringAfter(":").toIntOrNull()?.let { add(CipherStep.Splice(it)) }
              clean.startsWith("w:") -> clean.substringAfter(":").toIntOrNull()?.let { add(CipherStep.Swap(it)) }
            }
          }
        }
      }
    }
  }

  suspend fun fetchOrParseFromPlayerJs(playerUrl: String): PlayerCipherConfig? {
    val playerId = extractPlayerIdFromUrl(playerUrl) ?: "default"
    configsByPlayerId[playerId]?.let { return it }
    getRemoteConfig(playerId)?.let { return it }
    return try {
      val fullUrl =
        if (playerUrl.startsWith("http")) playerUrl
        else "https://www.youtube.com${if (playerUrl.startsWith("/")) "" else "/"}$playerUrl"
      val js = httpClient.getTextSuspend(fullUrl)
      parsePlayerJs(playerId, js)?.also { configsByPlayerId[playerId] = it }
    } catch (e: Exception) {
      PulseLog.d(TAG, "Player JS parse skipped for $playerId: ${e.message}")
      null
    }
  }

  fun parsePlayerJs(playerId: String, playerJs: String): PlayerCipherConfig? {
    val sts = extractSignatureTimestamp(playerJs) ?: FALLBACK_STS
    val ops = extractCipherOpsFromJs(playerJs)
    if (ops.isEmpty()) return null
    return PlayerCipherConfig(
      playerId = playerId,
      signatureTimestamp = sts,
      sigSteps = ops,
      fetchedAtMs = clock(),
    )
  }

  companion object {
    private const val TAG = "CipherConfigProvider"
    const val FALLBACK_STS = 20073
    const val REMOTE_CONFIG_TTL_MS = 6 * 60 * 60 * 1000L

    val DEFAULT_REMOTE_CONFIG_URLS =
      listOf(
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/main/player_configs.json",
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/player_configs.json",
      )

    fun extractPlayerIdFromUrl(playerUrl: String): String? =
      Regex("/s/player/([a-zA-Z0-9_-]+)/").find(playerUrl)?.groupValues?.getOrNull(1)

    fun extractSignatureTimestamp(playerJs: String): Int? =
      Regex("signatureTimestamp\\s*:\\s*(\\d+)")
        .find(playerJs)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    fun extractCipherOpsFromJs(playerJs: String): List<CipherStep> {
      // Locate main decipher function pattern: a=a.split(""); <obj>.<fn>(a,N); ... return a.join("")
      val mainFnRegex =
        Regex("=function\\(a\\)\\{a=a\\.split\\([\"']{2}\\);(.*?)return a\\.join\\([\"']{2}\\)\\}")
      val body = mainFnRegex.find(playerJs)?.groupValues?.getOrNull(1) ?: return emptyList()

      val calls = Regex("([\\w$]+)\\.([\\w$]+)\\(a(?:\\s*,\\s*(\\d+))?\\)").findAll(body).toList()
      if (calls.isEmpty()) return emptyList()

      val helperObjName = calls.first().groupValues[1]
      val escapedObj = Regex.escape(helperObjName)
      val helperDefRegex =
        Regex("(?:var|let|const)\\s+$escapedObj\\s*=\\s*\\{(.*?)\\};", setOf(RegexOption.DOT_MATCHES_ALL))
      val helperBody = helperDefRegex.find(playerJs)?.groupValues?.getOrNull(1).orEmpty()

      val methodKinds = mutableMapOf<String, String>()
      val methodRegex = Regex("([\\w$]+)\\s*:\\s*function\\(([^)]*)\\)\\s*\\{([^}]*)\\}")
      for (m in methodRegex.findAll(helperBody)) {
        val mName = m.groupValues[1]
        val mBody = m.groupValues[3]
        when {
          mBody.contains("reverse") -> methodKinds[mName] = "reverse"
          mBody.contains("splice") -> methodKinds[mName] = "splice"
          mBody.contains("length") || mBody.contains("a[0]=a[") -> methodKinds[mName] = "swap"
        }
      }

      return buildList {
        for (c in calls) {
          val fnName = c.groupValues[2]
          val arg = c.groupValues[3].toIntOrNull() ?: 0
          when (methodKinds[fnName]) {
            "reverse" -> add(CipherStep.Reverse)
            "splice" -> add(CipherStep.Splice(arg))
            "swap" -> add(CipherStep.Swap(arg))
          }
        }
      }
    }
  }
}

object CipherDeobfuscator {
  fun applyCipherSteps(signature: String, steps: List<CipherStep>): String {
    if (signature.isEmpty() || steps.isEmpty()) return signature
    val chars = signature.toCharArray().toMutableList()
    for (step in steps) {
      when (step) {
        is CipherStep.Reverse -> chars.reverse()
        is CipherStep.Splice -> {
          val count = step.index.coerceIn(0, chars.size)
          repeat(count) { if (chars.isNotEmpty()) chars.removeAt(0) }
        }
        is CipherStep.Swap -> {
          if (chars.isNotEmpty()) {
            val pos = step.index % chars.size
            val tmp = chars[0]
            chars[0] = chars[pos]
            chars[pos] = tmp
          }
        }
      }
    }
    return String(chars.toCharArray())
  }

  fun parseFormEncodedParams(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    query.split("&").forEach { pair ->
      val eq = pair.indexOf('=')
      if (eq > 0) {
        val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
        val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        result[k] = v
      }
    }
    return result
  }

  fun decipherFormatUrl(
    directUrl: String?,
    signatureCipher: String?,
    cipherConfig: PlayerCipherConfig?,
    poToken: String? = null,
  ): String? {
    val resolvedBaseUrl: String
    if (!directUrl.isNullOrBlank()) {
      resolvedBaseUrl = directUrl.trim()
    } else if (!signatureCipher.isNullOrBlank()) {
      val params = parseFormEncodedParams(signatureCipher)
      val baseUrl = params["url"]?.trim().orEmpty()
      if (baseUrl.isBlank()) return null
      val encryptedSig = params["s"].orEmpty()
      val sp = params["sp"]?.ifBlank { "signature" } ?: "signature"
      val steps = cipherConfig?.sigSteps.orEmpty()
      if (encryptedSig.isNotBlank() && steps.isEmpty()) {
        // Cannot decipher an encrypted signature without valid steps
        return null
      }
      val decryptedSig =
        if (encryptedSig.isNotBlank()) applyCipherSteps(encryptedSig, steps) else ""
      val sep = if (baseUrl.contains("?")) "&" else "?"
      resolvedBaseUrl =
        if (decryptedSig.isNotBlank()) {
          "$baseUrl$sep${URLEncoder.encode(sp, "UTF-8")}=${URLEncoder.encode(decryptedSig, "UTF-8")}"
        } else {
          baseUrl
        }
    } else {
      return null
    }

    var finalUrl = resolvedBaseUrl
    val nShift = cipherConfig?.nShiftKey
    if (nShift != null && finalUrl.contains("n=")) {
      finalUrl = rewriteQueryParam(finalUrl, "n") { existing ->
        transformNParamSimple(existing, nShift)
      }
    }
    if (!poToken.isNullOrBlank() && !finalUrl.contains("&pot=") && !finalUrl.contains("?pot=")) {
      val sep = if (finalUrl.contains("?")) "&" else "?"
      finalUrl = "$finalUrl${sep}pot=${URLEncoder.encode(poToken, "UTF-8")}"
    }
    return finalUrl
  }

  internal fun transformNParamSimple(n: String, shift: Int): String {
    if (n.isEmpty() || shift == 0) return n
    val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    return buildString(n.length) {
      for (i in n.indices) {
        val idx = alpha.indexOf(n[i])
        if (idx >= 0) {
          val shifted = ((idx - shift - i) % alpha.length + alpha.length) % alpha.length
          append(alpha[shifted])
        } else {
          append(n[i])
        }
      }
    }
  }

  private fun rewriteQueryParam(url: String, key: String, mapper: (String) -> String): String {
    val qIdx = url.indexOf('?')
    if (qIdx < 0) return url
    val base = url.substring(0, qIdx)
    val query = url.substring(qIdx + 1)
    val rewritten =
      query.split("&").joinToString("&") { part ->
        val eq = part.indexOf('=')
        if (eq > 0 && part.substring(0, eq) == key) {
          val oldVal = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
          val newVal = mapper(oldVal)
          "$key=${URLEncoder.encode(newVal, "UTF-8")}"
        } else {
          part
        }
      }
    return "$base?$rewritten"
  }
}
