package com.alexdiab.stadiatv.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.SportsBaseball
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.SportsFootball
import androidx.compose.material.icons.rounded.SportsGolf
import androidx.compose.material.icons.rounded.SportsHockey
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.SportsTennis
import androidx.compose.ui.graphics.vector.ImageVector
import com.alexdiab.stadiatv.domain.SportGroup

val SportGroup.icon: ImageVector
    get() = when (this) {
        SportGroup.Football -> Icons.Rounded.SportsFootball
        SportGroup.Basketball -> Icons.Rounded.SportsBasketball
        SportGroup.Baseball -> Icons.Rounded.SportsBaseball
        SportGroup.Hockey -> Icons.Rounded.SportsHockey
        SportGroup.Soccer -> Icons.Rounded.SportsSoccer
        SportGroup.Tennis -> Icons.Rounded.SportsTennis
        SportGroup.Golf -> Icons.Rounded.SportsGolf
        SportGroup.Racing -> Icons.Rounded.Flag
    }
