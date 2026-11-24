package com.inspiredandroid.kai.mcp

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

internal actual fun mcpOAuthProof(): OAuthProof? {
    val random = SecureRandom()
    fun encode(data: ByteArray) = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun secret() = encode(ByteArray(32).also(random::nextBytes))
    val verifier = secret()
    return OAuthProof(secret(), verifier, encode(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())))
}
