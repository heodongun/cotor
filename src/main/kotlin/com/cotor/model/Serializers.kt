package com.cotor.model

/**
 * File overview for PathSerializer.
 *
 * This file belongs to the shared model layer that defines configuration and execution contracts.
 * It groups declarations around serializers so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Serializer for java.nio.file.Path
 */
object PathSerializer : KSerializer<Path> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Path) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Path {
        return Path(decoder.decodeString())
    }
}

/**
 * Serializer for nullable java.nio.file.Path values.
 */
@OptIn(ExperimentalSerializationApi::class)
object NullablePathSerializer : KSerializer<Path?> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Path?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.toString())
        }
    }

    override fun deserialize(decoder: Decoder): Path? {
        return if (decoder.decodeNotNullMark()) {
            Path(decoder.decodeString())
        } else {
            decoder.decodeNull()
            null
        }
    }
}

/**
 * Serializer for List<Path>
 */
object PathListSerializer : KSerializer<List<Path>> {
    private val listSerializer = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: List<Path>) {
        encoder.encodeSerializableValue(listSerializer, value.map { it.toString() })
    }

    override fun deserialize(decoder: Decoder): List<Path> {
        return decoder.decodeSerializableValue(listSerializer).map { Path(it) }
    }
}
