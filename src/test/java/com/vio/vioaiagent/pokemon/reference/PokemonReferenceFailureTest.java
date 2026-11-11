package com.vio.vioaiagent.pokemon.reference;
import org.junit.jupiter.api.Test;
import com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PokemonReferenceFailureTest {
 @Test void onlyTypedSpeciesAbsenceGetsStableMarker(){
  var repo=mock(LocalPokeApiRepository.class);var tool=new PokemonReferenceTools(repo);
  when(repo.detail("unknown","pokeapi-reference-v1")).thenThrow(new PokemonResourceNotFoundException("404"));
  assertEquals("POKEMON_REFERENCE_NOT_FOUND",assertThrows(PokemonResourceNotFoundException.class,()->tool.lookupPokemonReference("unknown","pokeapi-reference-v1")).getMessage());
  when(repo.detail("known","pokeapi-reference-v1")).thenThrow(new IllegalStateException("timeout"));
  assertEquals("timeout",assertThrows(IllegalStateException.class,()->tool.lookupPokemonReference("known","pokeapi-reference-v1")).getMessage());
 }
}
