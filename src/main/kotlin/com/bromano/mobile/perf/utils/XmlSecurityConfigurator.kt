package com.bromano.mobile.perf.utils

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies XML security overrides once so large Instruments XML exports can be parsed.
 */
object XmlSecurityConfigurator {
    private val configured = AtomicBoolean(false)

    fun configureXMLSecurityProperties() {
        if (configured.compareAndSet(false, true)) {
            val xmlSecurityProperties =
                mapOf(
                    "jdk.xml.maxGeneralEntitySizeLimit" to "0",
                    "jdk.xml.maxParameterEntitySizeLimit" to "0",
                    "jdk.xml.entityExpansionLimit" to "0",
                    "jdk.xml.elementAttributeLimit" to "0",
                    "jdk.xml.maxXMLNameLimit" to "0",
                    "jdk.xml.totalEntitySizeLimit" to "0",
                )

            xmlSecurityProperties.forEach { (property, value) ->
                System.setProperty(property, value)
            }
        }
    }
}
