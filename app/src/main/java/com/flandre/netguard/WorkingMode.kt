package com.flandre.netguard

enum class WorkingMode {
    SHIZUKU,
    ROOT;

    companion object {
        fun fromName(name: String?): WorkingMode {
            return try {
                if (name == null) SHIZUKU else valueOf(name)
            } catch (e: Exception) {
                SHIZUKU
            }
        }
    }
}
