package com.simonproyt.legacysignal

import org.signal.libsignal.protocol.util.KeyHelper

fun test() {
    val methods = KeyHelper::class.java.declaredMethods
    methods.forEach { println(it.name) }
}
