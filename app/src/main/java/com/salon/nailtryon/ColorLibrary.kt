package com.salon.nailtryon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class BrandColor(
    val brand: String,
    val name: String,
    val color: Color,
    val isCustom: Boolean = false
)

val DefaultBrandColors = listOf(
    BrandColor("OPI", "Big Apple Red", Color(0xFF8D0014)),
    BrandColor("OPI", "Lincoln Park After Dark", Color(0xFF2E1A47)),
    BrandColor("Essie", "Ballet Slippers", Color(0xFFF3E5E2)),
    BrandColor("Essie", "Mint Candy Apple", Color(0xFFB2D3C2)),
    BrandColor("Zoya", "Mauve", Color(0xFFC0445C)),
    BrandColor("Zoya", "Tinsley", Color(0xFFE1B9B4)),
    BrandColor("Revlon", "Deep Plum", Color(0xFF2E1A47)),
    BrandColor("Sally Hansen", "Pacific Blue", Color(0xFF003366)),
)
