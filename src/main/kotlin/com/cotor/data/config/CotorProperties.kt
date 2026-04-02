package com.cotor.data.config

/**
 * File overview for CotorProperties.
 *
 * This file belongs to the configuration loading layer that resolves YAML, imports, and overrides.
 * It groups declarations around cotor properties so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

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
