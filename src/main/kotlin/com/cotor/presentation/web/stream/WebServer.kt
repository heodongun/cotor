
package com.cotor.presentation.web.stream

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
// ... (rest of the imports)

@Serializable
data class SaveResponse(val ok: Boolean, val path: String)

@Serializable
data class EditorStagePayload(
// ... (rest of the file)
