package com.phairplay.airplay

/** DNS-SD instance names are case-insensitive; DACP IDs are hexadecimal integers. */
internal object DacpServiceIdentity {
    fun normalize(value: String): String? = value.trim().takeIf {
        it.length in 1..16 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' }
    }?.trimStart('0')?.ifEmpty { "0" }?.uppercase(java.util.Locale.ROOT)

    fun matches(serviceName: String, targetName: String): Boolean {
        fun id(name: String): String? = if (name.startsWith("iTunes_Ctrl_", true))
            normalize(name.substring(12)) else null
        val expected = id(targetName) ?: return false
        return id(serviceName) == expected
    }
}
