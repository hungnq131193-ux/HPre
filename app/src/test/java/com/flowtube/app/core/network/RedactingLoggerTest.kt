package com.flowtube.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class RedactingLoggerTest {

    @Test
    fun safe_diagnostic_logger_interface_enforces_enum_component_category_operation_status() {
        var loggedComponent: DiagnosticComponent? = null
        var loggedCategory: LogCategory? = null
        var loggedOperation: DiagnosticOperation? = null
        var loggedStatus: DiagnosticStatus? = null

        val logger: SafeDiagnosticLogger = object : SafeDiagnosticLogger {
            override fun logDiagnostic(
                component: DiagnosticComponent,
                category: LogCategory,
                operation: DiagnosticOperation,
                status: DiagnosticStatus
            ) {
                loggedComponent = component
                loggedCategory = category
                loggedOperation = operation
                loggedStatus = status
            }
        }

        logger.logDiagnostic(
            component = DiagnosticComponent.OKHTTP_DOWNLOADER,
            category = LogCategory.NETWORK,
            operation = DiagnosticOperation.EXTRACTION_METADATA,
            status = DiagnosticStatus.Http4xx
        )

        assertEquals(DiagnosticComponent.OKHTTP_DOWNLOADER, loggedComponent)
        assertEquals(LogCategory.NETWORK, loggedCategory)
        assertEquals(DiagnosticOperation.EXTRACTION_METADATA, loggedOperation)
        assertEquals(DiagnosticStatus.Http4xx, loggedStatus)
    }

    @Test
    fun verify_no_reflection_visible_public_methods_or_fields_accepting_raw_diagnostic_strings() {
        // RedactingLogger must have NO public fields except Kotlin runtime generated fields (INSTANCE, $stable)
        val declaredFields = RedactingLogger::class.java.declaredFields
        val allowedFieldNames = setOf("INSTANCE", "\$stable")
        for (field in declaredFields) {
            if (Modifier.isPublic(field.modifiers)) {
                assertTrue("Public field found: ${field.name}", allowedFieldNames.contains(field.name))
            }
        }

        // Check all public methods on RedactingLogger
        val methods = RedactingLogger::class.java.methods
        val harmlessObjectMethods = setOf(
            "equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait"
        )
        for (method in methods) {
            if (harmlessObjectMethods.contains(method.name)) continue
            // Ensure no public method accepts String, Map, or Throwable
            for (paramType in method.parameterTypes) {
                assertFalse(
                    "Method ${method.name}(${method.parameterTypes.map { it.simpleName }.joinToString()}) must not accept String parameter",
                    paramType == String::class.java
                )
                assertFalse(
                    "Method ${method.name}(${method.parameterTypes.map { it.simpleName }.joinToString()}) must not accept Map parameter",
                    Map::class.java.isAssignableFrom(paramType)
                )
                assertFalse(
                    "Method ${method.name}(${method.parameterTypes.map { it.simpleName }.joinToString()}) must not accept Throwable parameter",
                    Throwable::class.java.isAssignableFrom(paramType)
                )
            }
        }

        // Verify SafeDiagnosticLogger interface declared methods
        val interfaceMethods = SafeDiagnosticLogger::class.java.declaredMethods
        assertEquals(1, interfaceMethods.size)
        val logMethod = interfaceMethods[0]
        assertEquals("logDiagnostic", logMethod.name)
        val types = logMethod.parameterTypes
        assertEquals(4, types.size)
        assertEquals(DiagnosticComponent::class.java, types[0])
        assertEquals(LogCategory::class.java, types[1])
        assertEquals(DiagnosticOperation::class.java, types[2])
        assertEquals(DiagnosticStatus::class.java, types[3])
    }
}

