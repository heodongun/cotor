package com.cotor.data.config

import java.util.Properties

object CotorProperties {
    val version: String

    init {
        val props = Properties()
        val inputStream = javaClass.classLoader.getResourceAsStream("cotor.properties")
        props.load(inputStream)
        version = props.getProperty("version", "unknown")
    }
}
