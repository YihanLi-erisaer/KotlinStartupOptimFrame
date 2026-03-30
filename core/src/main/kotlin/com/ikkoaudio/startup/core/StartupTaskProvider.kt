package com.ikkoaudio.startup.core

fun interface StartupTaskProvider {
    fun provide(): List<StartupTask>
}
