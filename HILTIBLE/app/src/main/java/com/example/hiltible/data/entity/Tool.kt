package com.example.hiltible.data.entity

data class Tool(
    val id: String = "",
    val name: String = "",
    val tagUuid: String? = null,
    val lastLat: Double = 0.0,
    val lastLng: Double = 0.0,
    val lastSeenAt: Long? = null,
    val lastAnchorId: String? = null,
    val lastAnchorName: String? = null,
    val lastRssi: Int? = null,
    val selectedAnchorId: String? = null,
    val selectedAnchorName: String? = null,
    val isOnline: Boolean = false,
    val anchorCandidates: List<AnchorCandidate>? = null
) {
    data class AnchorCandidate(
        val anchorId: String = "",
        val anchorName: String = "",
        val averageRssi: Int = 0,
        val lastRssi: Int = 0,
        val isActive: Boolean = false
    )
}
