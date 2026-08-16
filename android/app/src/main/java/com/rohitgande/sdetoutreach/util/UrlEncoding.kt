package com.rohitgande.sdetoutreach.util

// Java's URLEncoder.encode is application/x-www-form-urlencoded (space -> '+', different
// reserved-char handling) which doesn't match the Node tool's encodeURIComponent (space ->
// %20). Replicated by hand here so the built search URL is byte-identical to what the
// Node scraper sends.
private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

fun encodeURIComponent(value: String): String {
    val sb = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        if (c < 128 && UNRESERVED.indexOf(c.toChar()) >= 0) {
            sb.append(c.toChar())
        } else {
            sb.append('%')
            sb.append(String.format("%02X", c))
        }
    }
    return sb.toString()
}
