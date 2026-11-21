package com.example.pokemongrader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcgDexIdentityTest {
    @Test fun localizedAndGenderedNamesKeepTheirDistinctIdentity() {
        assertFalse(TcgDexClient.isPokemonCardNameMatch("Nidoran♀", "Nidoran♂"))
        assertTrue(TcgDexClient.isPokemonCardNameMatch("Nidoran♀", "Nidoran♀"))
        assertTrue(TcgDexClient.isPokemonCardNameMatch("リザードン", "リザードン"))
        assertFalse(TcgDexClient.isPokemonCardNameMatch("Hisuian Zorua", "Zorua"))
        assertTrue(TcgDexClient.isPokemonCardNameMatch("Hisuian Zorua", "Hisuian Zorua"))
    }
}
