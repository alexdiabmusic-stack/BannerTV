package com.alexdiab.stadiatv.ui

import androidx.compose.ui.input.key.Key
import com.alexdiab.stadiatv.ui.player.PlayerPanelKind
import com.alexdiab.stadiatv.ui.player.PlayerRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerRemoteTest {
    @Test
    fun upShowsTheScoreboardWhenAGameIsLiveAndWhatIsOnOtherwise() {
        assertEquals(PlayerPanelKind.Sports, PlayerRemote.panelFor(Key.DirectionUp, hasLiveGame = true))
        assertEquals(PlayerPanelKind.Guide, PlayerRemote.panelFor(Key.DirectionUp, hasLiveGame = false))
    }

    @Test
    fun downOpensTheChannelSwitcher() {
        assertEquals(PlayerPanelKind.Channels, PlayerRemote.panelFor(Key.DirectionDown, hasLiveGame = true))
        assertEquals(PlayerPanelKind.Channels, PlayerRemote.panelFor(Key.DirectionDown, hasLiveGame = false))
    }

    @Test
    fun selectIsNotADirectionSoItJustShowsTheControls() {
        assertNull(PlayerRemote.panelFor(Key.DirectionCenter, hasLiveGame = true))
        assertNull(PlayerRemote.panelFor(Key.Enter, hasLiveGame = false))
    }
}
